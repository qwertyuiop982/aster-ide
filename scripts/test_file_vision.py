"""Provider wire tests for read_file images; no real credentials or paid calls."""
import base64
import http.server
import json
from pathlib import Path
import struct
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / 'core/app/src/main/assets/bridge'
sys.path.insert(0, str(BRIDGE))
sys.path.insert(0, str(BRIDGE / 'agent_vendor'))
import agent_vision
from aster_tools import ProjectEnvironment, READ_TOOLS


def png(width=2, height=1):
    def chunk(name, data):
        return struct.pack('>I', len(data)) + name + data + struct.pack('>I', zlib.crc32(name + data))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress(b'\0\xff\0\0\0\0\xff' * height)) + chunk(b'IEND', b''))


class ImageReadTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.requests = []
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                cls.requests.append((self.path, dict(self.headers), body))
                if self.path.endswith('/messages'):
                    value = {'content': [{'type': 'text', 'text': 'red left, blue right'}]}
                elif ':generateContent' in self.path:
                    value = {'candidates': [{'content': {'parts': [{'thought': True, 'text': 'private'}, {'text': 'red left, blue right'}]}}]}
                elif self.path.endswith('/responses'):
                    value = {'output': [{'content': [{'type': 'output_text', 'text': 'red left, blue right'}]}]}
                else:
                    value = {'choices': [{'message': {'content': 'red left, blue right'}}]}
                encoded = json.dumps(value).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.worker = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.worker.start()
        cls.endpoint = 'http://127.0.0.1:' + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.worker.join()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.stage = Path(self.temp.name)
        (self.stage / 'colors.png').write_bytes(png())
        self.session = SimpleNamespace(stage=self.stage, excluded=set(), engine_id='pi', allowed_tools=READ_TOOLS,
            request={'configuration': {'endpoint': self.endpoint, 'model': 'fixture-vision', 'apiKey': 'fixture-secret'}},
            check_cancelled=lambda: None, emit=lambda *args, **kwargs: None, redact_event=lambda value: value)

    def read(self, name='colors.png'):
        return ProjectEnvironment(self.session).execute({'name': 'read_file', 'arguments': {'path': name, 'intent': 'Compare the colors and positions.'}})

    def test_actual_read_file_sends_image_bytes_to_each_provider_contract(self):
        for engine, protocol, expected in [('pi', 'openai', '/v1/chat/completions'), ('codex', 'openai', '/v1/responses'),
                ('openclaude', 'anthropic', '/v1/messages'), ('gemini', 'gemini', '/v1beta/models/fixture-vision:generateContent'),
                ('gemini', 'openai', '/v1/chat/completions')]:
            with self.subTest(engine=engine, protocol=protocol):
                self.session.engine_id = engine
                self.session.request['configuration']['protocol'] = protocol
                result = self.read()
                self.assertEqual(result['type'], 'image')
                self.assertEqual(result['source'], 'model_vision')
                self.assertEqual(result['text'], 'red left, blue right')
                path, headers, body = self.requests[-1]
                headers = {key.lower(): value for key, value in headers.items()}
                self.assertEqual(path, expected)
                wire = json.dumps(body)
                self.assertIn(base64.b64encode(png()).decode(), wire)
                self.assertIn('Compare the colors and positions.', wire)
                if engine == 'openclaude':
                    self.assertEqual(headers.get('x-api-key'), 'fixture-secret')
                elif engine == 'gemini' and protocol == 'gemini':
                    self.assertEqual(headers.get('x-goog-api-key'), 'fixture-secret')
                else:
                    self.assertEqual(headers.get('authorization'), 'Bearer fixture-secret')
                self.assertNotIn('fixture-secret', json.dumps(result))

    def test_signature_detected_without_image_extension(self):
        (self.stage / 'asset.bin').write_bytes(png())
        self.assertEqual(self.read('asset.bin')['mimeType'], 'image/png')

    def test_text_read_still_uses_numbered_lines_without_network(self):
        (self.stage / 'code.py').write_text('first\nsecond\n')
        count = len(self.requests)
        self.assertEqual(self.read('code.py')['text'], '1: first\n2: second')
        self.assertEqual(len(self.requests), count)

    def test_free_gateway_and_invalid_image_do_not_send_network_requests(self):
        count = len(self.requests)
        self.session.request['configuration']['freeMode'] = True
        self.assertIn('免费接口', self.read()['error'])
        self.session.request['configuration']['freeMode'] = False
        (self.stage / 'colors.png').write_text('not an image')
        self.assertFalse(self.read()['ok'])
        self.assertEqual(len(self.requests), count)

    def test_declared_mime_must_match_bytes_and_dimensions_are_bounded(self):
        capture = {'mimeType': 'image/jpeg', 'base64': base64.b64encode(png()).decode(), 'width': 2, 'height': 1, 'screenWidth': 2, 'screenHeight': 1}
        with self.assertRaisesRegex(ValueError, 'signature'):
            agent_vision.analyze_capture('pi', self.session.request['configuration'], capture, 'inspect', file_image=True)
        capture.update(mimeType='image/png', width=3)
        with self.assertRaisesRegex(ValueError, 'dimensions'):
            agent_vision.analyze_capture('pi', self.session.request['configuration'], capture, 'inspect', file_image=True)
        with self.assertRaisesRegex(ValueError, 'dimensions'):
            agent_vision.image_dimensions(png(20001, 1))
        with self.assertRaisesRegex(ValueError, 'dimensions'):
            agent_vision.image_dimensions(png(10000, 10000))

    def test_all_supported_dimension_headers(self):
        cases = [(png(), ('image/png', 2, 1)), (b'GIF89a' + struct.pack('<HH', 7, 9), ('image/gif', 7, 9)),
            (b'RIFF' + bytes(4) + b'WEBPVP8X' + bytes(8) + (6).to_bytes(3, 'little') + (8).to_bytes(3, 'little'), ('image/webp', 7, 9)),
            (b'\xff\xd8\xff\xc0\0\x11\x08' + struct.pack('>HH', 9, 7) + bytes(10), ('image/jpeg', 7, 9))]
        for raw, expected in cases:
            self.assertEqual(agent_vision.image_dimensions(raw), expected)

    def test_upload_limit_and_project_path_are_enforced(self):
        (self.stage / 'large.png').write_bytes(b'\0' * (5 * 1024 * 1024 + 1))
        self.assertIn('5 MiB', self.read('large.png')['error'])
        self.assertFalse(self.read('../outside.png')['ok'])
        (self.stage / 'linked.png').symlink_to(self.stage / 'colors.png')
        self.assertFalse(self.read('linked.png')['ok'])


if __name__ == '__main__':
    unittest.main()
