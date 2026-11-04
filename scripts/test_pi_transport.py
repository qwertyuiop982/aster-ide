"""Fault-injection tests for Pi's shared provider transport; no API credentials."""
from pathlib import Path
import contextlib
import errno
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import socket
import ssl
import sys
import threading
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / 'core/app/src/main/assets/bridge'
sys.path[:0] = [str(BRIDGE / 'agent_vendor'), str(BRIDGE)]
import aster_model
import aster_transport


class Session:
    def __init__(self):
        self.events = []
        self.steps = self.tokens = 0
        self.tools = []
        self.cancelled = False

    def check_cancelled(self):
        if self.cancelled:
            raise RuntimeError('test cancellation')

    def emit(self, kind, **data):
        self.events.append((kind, data))


class AbortStream:
    def __init__(self, chunks):
        self.lines = iter(('data: ' + json.dumps(chunk) + '\n\n').encode() for chunk in chunks)
        self.headers = {'Content-Type': 'text/event-stream'}

    def readline(self, limit):
        try:
            return next(self.lines)
        except StopIteration:
            raise ConnectionAbortedError(errno.ECONNABORTED, 'Software caused connection abort')

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


@contextlib.contextmanager
def server(abort=False):
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            requests.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
            if abort:
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            body = json.dumps({'choices': [{'message': {'content': 'complete'}, 'finish_reason': 'stop'}]}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    httpd = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        yield 'http://127.0.0.1:%d/v1' % httpd.server_port, requests
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(2)


def model(endpoint='https://provider.invalid/v1', name='fixture'):
    return aster_model.OpenAIModel({'endpoint': endpoint, 'model': name, 'apiKey': 'fixture-private-key'}, Session())


class PiTransportTests(unittest.TestCase):
    def test_default_tls_negotiation_without_preflight_requests(self):
        adapter = model()
        with patch.object(aster_model.urllib.request, 'urlopen', side_effect=AssertionError('HEAD must not be sent')):
            context = adapter.select_tls_context()
        self.assertEqual(context.minimum_version, ssl.TLSVersion.TLSv1_2)
        self.assertNotEqual(context.maximum_version, ssl.TLSVersion.TLSv1_3)
        self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
        self.assertTrue(context.check_hostname)
        self.assertIs(adapter.select_tls_context(), context)

    def test_errno103_during_connect_recovers_with_one_post(self):
        with server() as (endpoint, requests):
            original = socket.create_connection
            attempts = []

            def connect(*args, **kwargs):
                attempts.append(1)
                if len(attempts) <= 2:
                    raise ConnectionAbortedError(103, 'Software caused connection abort')
                return original(*args, **kwargs)

            adapter = model(endpoint)
            with patch('socket.create_connection', side_effect=connect):
                result = adapter.request({'model': 'fixture', 'messages': [], 'stream': False})
            self.assertEqual(result['choices'][0]['message']['content'], 'complete')
            self.assertEqual(len(attempts), 3)
            self.assertEqual(len(requests), 1)
            self.assertEqual(len(adapter.session.events), 2)

    def test_connect_retry_exhausted_never_sends_post(self):
        with server() as (endpoint, requests):
            with patch('socket.create_connection', side_effect=ConnectionAbortedError(103, 'aborted')) as connect:
                with self.assertRaisesRegex(RuntimeError, '模型连接中断'):
                    model(endpoint).request({'messages': []})
            self.assertEqual(connect.call_count, 3)
            self.assertEqual(requests, [])

    def test_post_accepted_then_socket_closed_is_not_replayed(self):
        with server(abort=True) as (endpoint, requests):
            with self.assertRaisesRegex(RuntimeError, '已有命令不会自动重跑'):
                model(endpoint).request({'messages': []})
            self.assertEqual(len(requests), 1)

    def test_certificate_errors_never_retry_or_disable_verification(self):
        connection = aster_transport._HTTPSConnection('invalid.example', session=Session(), context=ssl.create_default_context())
        with patch.object(http.client.HTTPSConnection, 'connect', side_effect=ssl.SSLCertVerificationError(1, 'certificate verify failed')) as call:
            with self.assertRaises(ssl.SSLCertVerificationError):
                connection.connect()
        self.assertEqual(call.call_count, 1)
        self.assertEqual(connection._context.verify_mode, ssl.CERT_REQUIRED)
        self.assertTrue(connection._context.check_hostname)

    def test_connect_retry_can_be_cancelled(self):
        session = Session()
        connection = aster_transport._HTTPConnection('invalid.example', session=session)

        def fail():
            session.cancelled = True
            raise ConnectionAbortedError(103, 'aborted')

        with patch.object(http.client.HTTPConnection, 'connect', side_effect=fail) as call:
            with self.assertRaisesRegex(RuntimeError, 'test cancellation'):
                connection.connect()
        self.assertEqual(call.call_count, 1)

    def test_tls_alert_fallback_occurs_before_any_http_write(self):
        connection = aster_transport._HTTPSConnection('invalid.example', session=Session(), context=ssl.create_default_context())
        with patch.object(http.client.HTTPSConnection, 'connect', side_effect=[ssl.SSLError('TLSV1_ALERT_INTERNAL_ERROR'), None]) as call:
            connection.connect()
        self.assertEqual(call.call_count, 2)
        self.assertEqual(connection._context.minimum_version, ssl.TLSVersion.TLSv1_2)
        self.assertEqual(connection._context.maximum_version, ssl.TLSVersion.TLSv1_2)
        self.assertEqual(connection._context.verify_mode, ssl.CERT_REQUIRED)

    def test_completed_stream_survives_errno103_in_optional_trailer(self):
        for provider in ['lunara', 'OpenRouter', 'OpenAI']:
            with self.subTest(provider=provider):
                adapter = model(name=provider)
                stream = AbortStream([{'choices': [{'delta': {'content': 'done'}, 'finish_reason': None}]},
                                      {'choices': [{'delta': {}, 'finish_reason': 'stop'}]}])
                with patch.object(aster_model, 'open_response', return_value=stream) as opened:
                    result = adapter.request({'model': provider, 'stream': True})
                self.assertEqual(result['choices'][0]['message']['content'], 'done')
                self.assertEqual(opened.call_count, 1)
                self.assertEqual(adapter.session.events[-1][0], 'message_end')

    def test_partial_tool_call_is_never_returned_on_connection_abort(self):
        adapter = model()
        stream = AbortStream([{'choices': [{'delta': {'tool_calls': [{'index': 0, 'id': 'call-1', 'function': {'name': 'run_command', 'arguments': '{"command":"echo'}}]}, 'finish_reason': None}]}])
        with patch.object(aster_model, 'open_response', return_value=stream) as opened:
            with self.assertRaisesRegex(RuntimeError, '本轮|未完成的工具调用不会执行'):
                adapter.request({'messages': [], 'stream': True})
        self.assertEqual(opened.call_count, 1)

    def test_complete_tool_call_preserved_on_trailer_abort(self):
        adapter = model()
        stream = AbortStream([{'choices': [{'delta': {'tool_calls': [{'index': 0, 'id': 'call-1', 'function': {'name': 'read_file', 'arguments': '{"path":"a.txt"}'}}]}, 'finish_reason': None}]},
                              {'choices': [{'delta': {}, 'finish_reason': 'tool_calls'}]}])
        result = adapter.read_stream(stream)
        self.assertEqual(json.loads(result['choices'][0]['message']['tool_calls'][0]['function']['arguments']), {'path': 'a.txt'})

    def test_unfinished_text_is_marked_interrupted(self):
        adapter = model()
        with self.assertRaises(ConnectionAbortedError):
            adapter.read_stream(AbortStream([{'choices': [{'delta': {'content': 'partial reply'}, 'finish_reason': None}]}]))
        event = adapter.session.events[-1]
        self.assertEqual(event[0], 'message_end')
        self.assertIn('回复未完成', event[1]['text'])

    def test_network_error_redacts_provider_key(self):
        adapter = model()
        with patch.object(aster_model, 'open_response', side_effect=ConnectionAbortedError(103, 'fixture-private-key')):
            with self.assertRaises(RuntimeError) as error:
                adapter.request({'messages': []})
        self.assertNotIn('fixture-private-key', str(error.exception))
        self.assertIn('[hidden]', str(error.exception))

    def test_query_retries_transient_request_error_before_tools_run(self):
        adapter = model()
        first = aster_model.ModelRequestError('HTTP 502', retryable=True)
        second = {'choices': [{'message': {'content': '完成', 'tool_calls': []}, 'finish_reason': 'stop'}],
                  'usage': {'total_tokens': 4}}
        with patch.object(adapter, 'request', side_effect=[first, second]) as request, \
             patch.object(aster_model.time, 'sleep') as sleep:
            result = adapter.query([{'role': 'user', 'content': '继续'}])
        self.assertEqual(result['content'], '完成')
        self.assertEqual(request.call_count, 2)
        sleep.assert_called_once_with(aster_model.MODEL_RETRY_DELAYS[0])
        self.assertTrue(any(kind == 'status' and '自动重试' in data['text'] for kind, data in adapter.session.events))

    def test_query_repairs_missing_required_tool_arguments_before_execution(self):
        tools = [{'type': 'function', 'function': {
            'name': 'read_file', 'description': 'Read a file',
            'parameters': {'type': 'object', 'properties': {'path': {'type': 'string'}},
                           'required': ['path'], 'additionalProperties': False}}}]
        adapter = model()
        adapter.session.tools = tools
        malformed = {'choices': [{'message': {'content': '', 'tool_calls': [{
            'id': 'call-1', 'type': 'function', 'function': {'name': 'read_file', 'arguments': '{}'}}]},
            'finish_reason': 'tool_calls'}], 'usage': {'total_tokens': 3}}
        repaired = {'choices': [{'message': {'content': '已修复', 'tool_calls': []}, 'finish_reason': 'stop'}],
                    'usage': {'total_tokens': 4}}
        with patch.object(adapter, 'request', side_effect=[malformed, repaired]) as request:
            result = adapter.query([{'role': 'user', 'content': '读取文件'}], tools=['read_file'])
        self.assertEqual(result['content'], '已修复')
        self.assertEqual(request.call_count, 2)
        repair_payload = request.call_args_list[1].args[0]
        self.assertIn('工具参数缺少：path', repair_payload['messages'][-1]['content'])
        self.assertTrue(any(kind == 'status' and '工具参数无效' in data['text'] for kind, data in adapter.session.events))


if __name__ == '__main__':
    unittest.main()