"""Offline contract checks for the account gateway / Pi adapter.

Run with python3 -B -m unittest scripts.test_account_free from the project root.
"""
import json
import pathlib
import re
import sys
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "core/app/src/main/assets/bridge"))
sys.path.insert(0, str(ROOT / "core/app/src/main/assets/bridge/agent_vendor"))
from aster_free import FreeProtocolError, build_request, decode_response  # noqa: E402
from aster_model import OpenAIModel  # noqa: E402


class Session:
    def __init__(self):
        self.tools = [{"function": {"name": "read_file", "parameters": {"type": "object"}}}]
        self.tokens = 0
        self.events = []

    def check_cancelled(self):
        pass

    def emit(self, *args, **kwargs):
        self.events.append((args, kwargs))


class AccountFreeTests(unittest.TestCase):
    def setUp(self):
        self.session = Session()
        self.config = {"endpoint": "https://asteride.cn/api/v1/free", "model": "Aster-free", "apiKey": "session-only", "freeMode": True, "stream": True, "maxTokens": 2048, "thinkingMode": "off", "thinkingLevel": "off"}

    def test_gateway_request_contract_and_tool_round_trip(self):
        messages = [
            {"role": "system", "content": "Project instructions"},
            {"role": "user", "content": "Read the file"},
            {"role": "assistant", "content": "", "tool_calls": [{"id": "c1", "function": {"name": "read_file", "arguments": "{}"}}]},
            {"role": "tool", "tool_call_id": "c1", "content": "contents"},
        ]
        request = build_request(messages, self.session.tools)
        self.assertEqual(set(request), {"request_id", "messages"})
        self.assertEqual(len(request["request_id"]), 32)
        self.assertEqual(request["messages"][-1]["role"], "user")
        self.assertEqual({message["role"] for message in request["messages"]}, {"user", "assistant"})
        self.assertIn("contents", request["messages"][-1]["content"])
        reply = {"choices": [{"message": {"role": "assistant", "content": json.dumps({"content": "reading", "tool_calls": [{"name": "read_file", "arguments": {"path": "README.md"}}]})}, "finish_reason": "stop"}], "usage": {"input_tokens": 50, "output_tokens": 20, "total_tokens": 70}}
        decoded = decode_response(reply, {"read_file"})
        call = decoded["choices"][0]["message"]["tool_calls"][0]
        self.assertEqual(call["function"]["name"], "read_file")
        self.assertEqual(json.loads(call["function"]["arguments"]), {"path": "README.md"})
        self.assertEqual(decoded["usage"]["prompt_tokens"], 50)

    def test_unavailable_tools_rejected(self):
        reply = {"choices": [{"message": {"content": '{"tool_calls":[{"name":"shell","arguments":{}}]}'}}]}
        with self.assertRaisesRegex(ValueError, "shell"):
            decode_response(reply, {"read_file"})

    def test_free_profile_sends_only_public_contract(self):
        model = OpenAIModel(self.config, self.session)
        self.assertEqual(model.endpoint, "https://asteride.cn/api/v1/free/chat")
        self.assertFalse(model.streaming)
        payloads = []
        model.request = lambda payload: (payloads.append(payload) or {"choices": [{"message": {"content": '{"content":"done","tool_calls":[]}'}, "finish_reason": "stop"}], "usage": {"input_tokens": 3, "output_tokens": 2, "total_tokens": 5}})
        result = model.query([{"role": "user", "content": "hello"}])
        self.assertEqual(result["extra"]["actions"][0]["name"], "finish")
        self.assertEqual(set(payloads[0]), {"request_id", "messages"})
        self.assertEqual(self.session.tokens, 5)

    def test_free_go_uses_only_public_gateway(self):
        config = dict(self.config, endpoint="https://asteride.cn/api/v1/free-go", model="Aster-free go")
        model = OpenAIModel(config, self.session)
        self.assertEqual(model.endpoint, "https://asteride.cn/api/v1/free-go/chat")
        self.assertEqual(model.model, "Aster-free go")
        self.assertFalse(model.streaming)
        untrusted = OpenAIModel(dict(config, endpoint="https://example.invalid/v1"), self.session)
        self.assertEqual(untrusted.endpoint, "https://asteride.cn/api/v1/free/chat")

    def test_plain_tool_prose_is_repaired_without_executing_it(self):
        model = OpenAIModel(self.config, self.session)
        payloads = []
        def reply(payload):
            payloads.append(payload)
            text = '继续读取。read_file 参数：{"path":"next.txt"}' if len(payloads) == 1 else '{"content":"reading","tool_calls":[{"name":"read_file","arguments":{"path":"next.txt"}}]}'
            return {"choices": [{"message": {"content": text}, "finish_reason": "stop"}], "usage": {"input_tokens": 7, "output_tokens": 3, "total_tokens": 10}}
        model.request = reply
        result = model.query([{"role": "user", "content": "Read the chain"}, {"role": "tool", "tool_call_id": "previous", "content": "next.txt"}])
        self.assertEqual(len(payloads), 2)
        self.assertNotEqual(payloads[0]["request_id"], payloads[1]["request_id"])
        self.assertIn("尚未执行", payloads[1]["messages"][-1]["content"])
        self.assertEqual(result["tool_calls"][0]["function"]["name"], "read_file")
        self.assertEqual(self.session.tokens, 20)

    def test_format_recovery_is_bounded_and_usage_is_recorded(self):
        model = OpenAIModel(self.config, self.session)
        payloads = []
        def reply(payload):
            payloads.append(payload)
            return {"choices": [{"message": {"content": "read_file 参数：next.txt"}}], "usage": {"total_tokens": 10}}
        model.request = reply
        with self.assertRaisesRegex(FreeProtocolError, "连续三次"):
            model.query([{"role": "user", "content": "Read a file"}])
        self.assertEqual(len(payloads), 3)
        self.assertEqual(self.session.tokens, 30)

    def test_a_tool_observation_requires_explicit_finish(self):
        reply = {"choices": [{"message": {"content": '{"content":"I will continue","tool_calls":[]}'}}]}
        with self.assertRaises(FreeProtocolError):
            decode_response(reply, {"read_file", "finish"}, require_finish=True)

    def test_compaction_preserves_full_task(self):
        request = build_request([{"role": "user", "content": "original goal"}, {"role": "assistant", "content": "previous work"}], [], purpose="compaction")
        self.assertIn("original goal", json.dumps(request, ensure_ascii=False))
        self.assertEqual(request["messages"][-1]["role"], "user")

    def test_apk_assets_do_not_contain_upstream_configuration(self):
        apk = ROOT / "core/build/application/outputs/apk/debug/app-debug.apk"
        if not apk.exists():
            self.skipTest("APK has not been built")
        with zipfile.ZipFile(apk) as archive:
            assets = [name for name in archive.namelist() if name.startswith("assets/") and name.endswith((".py", ".js", ".json", ".sh", ".txt", ".xml"))]
            self.assertIn("assets/bridge/agent_vendor/aster_free.py", assets)
            for name in assets:
                content = archive.read(name)
                self.assertIsNone(re.search(rb"sk-[0-9a-f]{32,}|zzz-proxy[.]pages[.]dev|api[.]uselunora[.]com|cn:deepseek-v4[.]1-flash", content), name)

    def test_no_upstream_credentials_in_client_sources(self):
        source = (ROOT / "core/app/src/main/assets/bridge/agent_vendor/aster_free.py").read_text() + (ROOT / "core/app/src/main/assets/bridge/agent_vendor/aster_model.py").read_text()
        for endpoint in re.findall(r'''https?://[^\s"']+''', source):
            self.assertTrue(endpoint.startswith("https://asteride.cn/api/v1/"), endpoint)
        self.assertNotRegex(source, r"(?:cn:)?(?:deepseek|kimi)-")
        self.assertIsNone(re.search(r"sk-[0-9a-f]{32,}", source))


if __name__ == "__main__":
    unittest.main()
