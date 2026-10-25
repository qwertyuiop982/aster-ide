"""Exercise the shipped Pi loop and Python free adapter, without network or Android."""
import json
import queue
from pathlib import Path
import threading
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / "core/app/src/main/assets/bridge/agent_vendor"), str(ROOT / "core/app/src/main/assets/bridge")]
from aster_model import OpenAIModel

TOOLS = [
    {"type": "function", "function": {"name": "read_file", "description": "Read a project file", "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"], "additionalProperties": False}}},
    {"type": "function", "function": {"name": "finish", "description": "Finish the task", "parameters": {"type": "object", "properties": {"summary": {"type": "string"}}, "required": ["summary"], "additionalProperties": False}}},
]


class Session:
    tools = TOOLS
    tokens = 0

    def check_cancelled(self):
        pass

    def emit(self, *args, **kwargs):
        pass


class FreeToolLoopTests(unittest.TestCase):
    def run_loop(self, go=False, reads=3, batch=1, finish=True, malformed=False):
        session = Session()
        label = "Aster-free go" if go else "Aster-free"
        configuration = {"endpoint": "https://asteride.cn/api/v1/" + ("free-go" if go else "free"), "model": label,
                         "apiKey": "offline-fixture", "freeMode": True, "maxTokens": 2048, "thinkingMode": "off", "thinkingLevel": "off"}
        model = OpenAIModel(configuration, session)
        observed = []
        model_requests = []
        repaired = False

        def reply(payload):
            nonlocal repaired
            model_requests.append(payload)
            self.assertEqual(set(payload), {"request_id", "messages"})
            transcript = json.dumps(payload["messages"], ensure_ascii=False)
            self.assertIn("Read the linked files and report their values", transcript)
            self.assertIn('"name":"read_file"', payload["messages"][0]["content"])
            if observed:
                self.assertIn("tool-observation-" + str(len(observed)), transcript)
            if malformed and observed and not repaired:
                repaired = True
                return {"choices": [{"message": {"content": '继续读取下一个文件。read_file 参数：{"path":"file-2.txt"}'}, "finish_reason": "stop"}], "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}}
            if repaired and len(model_requests) == 3:
                self.assertIn("尚未执行", transcript)
            remaining = reads - len(observed)
            if remaining > 0:
                calls = [{"name": "read_file", "arguments": {"path": f"file-{len(observed) + n + 1}.txt"}} for n in range(min(batch, remaining))]
            elif finish:
                calls = [{"name": "finish", "arguments": {"summary": "Read all linked files"}}]
            else:
                calls = [{"name": "read_file", "arguments": {"path": "one-too-many.txt"}}]
            return {"choices": [{"message": {"content": json.dumps({"content": "Continue the task", "tool_calls": calls})}, "finish_reason": "stop"}],
                    "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}}

        model.request = reply
        with tempfile.TemporaryDirectory(prefix="aster-tool-loop-") as directory:
            folder = Path(directory)
            process = subprocess.Popen([shutil.which("node"), str(ROOT / "core/app/src/main/assets/bridge/agent_vendor/pi-agent.mjs")],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
            lines = queue.Queue()

            def read_packets():
                for line in process.stdout:
                    lines.put(line)
                lines.put("")

            reader = threading.Thread(target=read_packets, daemon=True)
            reader.start()
            deadline = time.monotonic() + 45

            def send(value):
                process.stdin.write(json.dumps(value, ensure_ascii=False) + "\n")
                process.stdin.flush()

            send({"type": "start", "system": "You are Aster. Use the available tools to complete the task.",
                  "task": "Read the linked files and report their values", "history": [], "tools": TOOLS,
                  "allowedTools": ["read_file", "finish"], "sessionId": "offline-loop", "stage": str(folder),
                  "workspace": str(folder), "sessionFile": str(folder / "session.jsonl"), "model": label,
                  "thinkingLevel": "off", "freeMode": True, "maxSteps": 1, "maxTokens": 2048, "contextWindow": 128000})
            result = None
            try:
                while time.monotonic() < deadline:
                    try:
                        line = lines.get(timeout=0.2)
                    except queue.Empty:
                        self.assertIsNone(process.poll(), "Pi exited before completing the tool loop")
                        continue
                    self.assertTrue(line, "Pi closed its transport")
                    packet = json.loads(line)
                    if packet["type"] == "error":
                        self.fail(packet["error"])
                    if packet["type"] == "result":
                        result = packet
                        break
                    if packet["type"] != "request":
                        continue
                    payload = packet["payload"]
                    if packet["method"] == "model":
                        value = model.query(payload["messages"], tools=payload["tools"], purpose=payload.get("purpose"))
                    else:
                        self.assertEqual(packet["method"], "tool")
                        if payload["name"] == "finish":
                            value = {"ok": True, "finished": True, "summary": payload["arguments"]["summary"]}
                        else:
                            self.assertEqual(payload["arguments"]["path"], f"file-{len(observed) + 1}.txt")
                            observed.append(payload["arguments"]["path"])
                            value = {"ok": True, "content": "tool-observation-" + str(len(observed))}
                    send({"type": "response", "id": packet["id"], "result": value})
                self.assertIsNotNone(result, "Pi did not complete within the test deadline")
            finally:
                process.stdin.close()
                if process.poll() is None:
                    process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                process.stdout.close()
                process.stderr.close()
        return result, observed, model_requests

    @unittest.skipUnless(shutil.which("node"), "Node is required for the real Pi loop")
    def test_both_tiers_continue_after_each_tool_result(self):
        for go in (False, True):
            with self.subTest(go=go):
                result, calls, requests = self.run_loop(go=go)
                self.assertEqual(result["status"], "Submitted")
                self.assertEqual(len(calls), 3)
                self.assertEqual(len(requests), 4)

    @unittest.skipUnless(shutil.which("node"), "Node is required for the real Pi loop")
    def test_tool_prose_after_the_first_read_is_recovered(self):
        for go in (False, True):
            with self.subTest(go=go):
                result, calls, requests = self.run_loop(go=go, malformed=True)
                self.assertEqual(result["status"], "Submitted")
                self.assertEqual(len(calls), 3)
                self.assertEqual(result["toolCalls"], 4)
                self.assertEqual(len(requests), 5)
                self.assertEqual(len({request["request_id"] for request in requests}), 5)

    @unittest.skipUnless(shutil.which("node"), "Node is required for the real Pi loop")
    def test_finish_can_be_the_64th_tool_call(self):
        result, calls, requests = self.run_loop(reads=63)
        self.assertEqual(result["status"], "Submitted")
        self.assertEqual(len(calls), 63)
        self.assertEqual(len(requests), 64)

    @unittest.skipUnless(shutil.which("node"), "Node is required for the real Pi loop")
    def test_the_65th_tool_is_not_executed(self):
        result, calls, requests = self.run_loop(reads=64, finish=False)
        self.assertEqual(result["status"], "LimitsExceeded")
        self.assertEqual(len(calls), 64)
        self.assertEqual(len(requests), 64)

    @unittest.skipUnless(shutil.which("node"), "Node is required for the real Pi loop")
    def test_batches_share_the_same_64_tool_budget(self):
        for batch, expected_requests in ((16, 4), (15, 5)):
            with self.subTest(batch=batch):
                result, calls, requests = self.run_loop(reads=80, batch=batch, finish=False)
                self.assertEqual(result["status"], "LimitsExceeded")
                self.assertEqual(len(calls), 64)
                self.assertEqual(len(requests), expected_requests)


if __name__ == "__main__":
    unittest.main()
