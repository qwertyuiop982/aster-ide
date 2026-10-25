from __future__ import annotations
from aster_free import PUBLIC_ENDPOINT, PUBLIC_MODEL, GO_ENDPOINT, GO_MODEL, FreeProtocolError, build_request as build_free_request, decode_response as decode_free_response, repair_request as repair_free_request
"""Bounded OpenAI-compatible HTTP adapter for Aster's Pi coding agent."""
import json
import queue
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from agent_thinking import resolve_thinking

MAX_TEXT = 1024 * 1024
HTTP_LIMIT = threading.BoundedSemaphore(2)


def compact_messages(messages, budget=100000):
    """Drop complete old exchanges, preserving matching tool-call observations."""
    normalized = []
    for original in messages:
        if original.get("role") not in ("system", "user", "assistant", "tool"):
            continue
        message = {key: value for key, value in original.items() if key in ("role", "content", "tool_calls", "tool_call_id")}
        message["content"] = str(message.get("content") or "")[:24000 if message["role"] != "tool" else 8000]
        if message.get("tool_calls"):
            calls = []
            for call in message["tool_calls"]:
                function = dict(call["function"])
                if len(str(function.get("arguments", ""))) > 12000:
                    function["arguments"] = json.dumps({"previous_arguments_omitted": "Read the staged file to inspect the current content."})
                calls.append({**call, "function": function})
            message["tool_calls"] = calls
        normalized.append(message)
    if not normalized:
        return []
    prefix = [normalized[0]] if normalized[0]["role"] == "system" else []
    last_user = max((i for i, item in enumerate(normalized) if item["role"] == "user"), default=-1)
    groups = []
    for index, message in enumerate(normalized[len(prefix):], start=len(prefix)):
        if message["role"] == "tool" and groups:
            groups[-1][1].append(message)
        else:
            groups.append((index, [message]))
    chosen, total = [], len(json.dumps(prefix, ensure_ascii=False))
    for index, group in reversed(groups):
        cost = len(json.dumps(group, ensure_ascii=False))
        latest = index == groups[-1][0]
        if latest and total + cost > budget:
            # A wide batch can exceed the context budget. Keep every call ID and
            # its observation instead of silently dropping the latest exchange.
            for item in group:
                if len(item.get("content", "")) > 1000:
                    item["content"] = item["content"][:1000] + "\n[output shortened; read the relevant file for more]"
                for call in item.get("tool_calls", []):
                    if len(call["function"].get("arguments", "")) > 1000:
                        call["function"]["arguments"] = json.dumps({"previous_arguments_omitted": True})
            cost = len(json.dumps(group, ensure_ascii=False))
        if index == last_user or latest or total + cost <= budget:
            chosen.append(group)
            total += cost
    return prefix + [item for group in reversed(chosen) for item in group]


class OpenAIModel:
    def __init__(self, configuration, session):
        self.session = session
        endpoint = str(configuration.get("endpoint", "")).strip().rstrip("/")
        parsed = urllib.parse.urlsplit(endpoint)
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.query or parsed.fragment or any(c.isspace() for c in endpoint):
            raise ValueError("请配置有效的模型 API 地址")
        self.endpoint = endpoint if parsed.path.endswith("/chat/completions") else endpoint + "/chat/completions"
        self.model = str(configuration.get("model", "")).strip()
        if not self.model:
            raise ValueError("请配置模型名称")
        self.key = str(configuration.get("apiKey", ""))
        self.max_tokens = configuration.get("maxTokens", 4096)
        self.streaming = bool(configuration.get("stream", True))
        self.thinking = resolve_thinking('pi', configuration)
        self.free_mode = bool(configuration.get("freeMode", False))
        if self.free_mode:
            go_tier = self.model == GO_MODEL and endpoint == GO_ENDPOINT.rsplit("/chat", 1)[0]
            self.endpoint, self.model = (GO_ENDPOINT, GO_MODEL) if go_tier else (PUBLIC_ENDPOINT, PUBLIC_MODEL)
            self.streaming = False
            self.thinking = {}
        self._tls_choice = None
        self._tls_checked_at = 0.0

    def select_tls_context(self):
        """Probe both versions on the configured proxy route without a model POST."""
        if urllib.parse.urlsplit(self.endpoint).scheme != 'https':
            return None
        if self._tls_choice is not None and time.monotonic() - self._tls_checked_at < 300:
            return self._tls_choice
        reachable, failures = [], []
        for version in (ssl.TLSVersion.TLSv1_2, ssl.TLSVersion.TLSv1_3):
            self.session.check_cancelled()
            context = ssl.create_default_context()
            context.minimum_version = context.maximum_version = version
            probe = urllib.request.Request(self.endpoint, method='HEAD', headers={'User-Agent': 'Aster/TLS-probe'})
            try:
                with urllib.request.urlopen(probe, context=context, timeout=5):
                    pass
                reachable.append(context)
            except urllib.error.HTTPError as error:
                error.close()
                reachable.append(context)
            except (urllib.error.URLError, ssl.SSLError, OSError) as error:
                failures.append(version.name + ': ' + str(getattr(error, 'reason', error))[:240])
        self.session.check_cancelled()
        if not reachable:
            detail = '; '.join(failures)
            if self.key:
                detail = detail.replace(self.key, '[hidden]')
            raise RuntimeError('Pi TLS 1.2/1.3 探测均失败：' + detail)
        self._tls_choice = reachable[-1]
        self._tls_checked_at = time.monotonic()
        return self._tls_choice

    def request(self, payload):
        headers = {"Content-Type": "application/json", "Accept": "text/event-stream, application/json", "User-Agent": "Aster/0.1.7"}
        if self.key:
            headers["Authorization"] = "Bearer " + self.key
        context = self.select_tls_context()
        tls12 = context is not None and context.maximum_version == ssl.TLSVersion.TLSv1_2
        for attempt in range(3):
            self.session.check_cancelled()
            request = urllib.request.Request(self.endpoint, data=json.dumps(payload, ensure_ascii=False).encode(), headers=headers, method="POST")
            response_opened = False
            try:
                options = {"timeout": 195 if self.free_mode else 45}
                if context is not None:
                    options["context"] = context
                if tls12:
                    context = ssl.create_default_context()
                    context.minimum_version = ssl.TLSVersion.TLSv1_2
                    context.maximum_version = ssl.TLSVersion.TLSv1_2
                    options["context"] = context
                with urllib.request.urlopen(request, **options) as response:
                    response_opened = True
                    if "text/event-stream" in response.headers.get("Content-Type", "").lower():
                        return self.read_stream(response)
                    body = response.read(4 * MAX_TEXT + 1)
                if len(body) > 4 * MAX_TEXT:
                    raise ValueError("模型响应过大")
                value = json.loads(body)
                if not isinstance(value, dict):
                    raise ValueError("模型响应格式无效")
                return value
            except urllib.error.HTTPError as error:
                detail = error.read(8192).decode(errors="replace")
                if attempt < 2 and error.code == 400 and "max_completion_tokens" in detail and "max_tokens" in payload:
                    payload["max_completion_tokens"] = payload.pop("max_tokens")
                    continue
                if attempt < 2 and error.code in (400, 422) and payload.get("stream") and "stream" in detail.lower():
                    payload["stream"] = False
                    payload.pop("stream_options", None)
                    continue
                if self.key:
                    detail = detail.replace(self.key, "[hidden]")
                raise RuntimeError(f"模型请求失败（HTTP {error.code}）：{detail[:1200]}") from None
            except (urllib.error.URLError, ssl.SSLError) as error:
                reason = getattr(error, "reason", error)
                # A handshake alert occurs before HTTP is sent; retry once with verified TLS 1.2.
                alert = isinstance(reason, ssl.SSLError) and "TLSV1_ALERT_INTERNAL_ERROR" in str(reason)
                if alert and not response_opened and not tls12 and attempt < 2:
                    self._tls_choice = None
                    self._tls_checked_at = 0.0
                    tls12 = True
                    continue
                host = urllib.parse.urlsplit(self.endpoint).hostname
                if isinstance(reason, ssl.SSLError):
                    raise RuntimeError(f"Pi TLS 握手失败（{host}）：{reason}。请检查服务端 TLS、代理与证书链；证书校验保持开启。") from None
                detail = str(reason)
                if self.key:
                    detail = detail.replace(self.key, "[hidden]")
                raise RuntimeError(f"Pi 网络连接失败（{host}）：{detail[:1000]}") from None
        raise ValueError("模型请求失败")

    def read_stream(self, response):
        content, calls, usage = [], {}, {}
        total, buffered, last_flush = 0, [], 0.0
        identifier = uuid.uuid4().hex
        started = completed = False
        finish_reason = None

        def flush():
            nonlocal last_flush
            if buffered:
                self.session.check_cancelled()
                self.session.emit("message_delta", messageId=identifier, text="".join(buffered))
                buffered.clear()
                last_flush = time.monotonic()

        while True:
            self.session.check_cancelled()
            line = response.readline(MAX_TEXT + 1)
            if not line:
                break
            total += len(line)
            if total > 4 * MAX_TEXT or len(line) > MAX_TEXT:
                raise ValueError("模型流式响应过大")
            if not line.startswith(b"data:"):
                continue
            data = line[5:].strip()
            if data == b"[DONE]":
                completed = True
                break
            if not data:
                continue
            chunk = json.loads(data)
            if chunk.get("error"):
                raise ValueError("模型流式响应错误：" + str(chunk["error"])[:800])
            if chunk.get("usage"):
                usage = chunk["usage"]
            choices = chunk.get("choices") or []
            if not choices:
                continue
            choice = next((item for item in choices if item.get("index", 0) == 0), {})
            if choice.get("finish_reason"):
                completed = True
                finish_reason = choice["finish_reason"]
            delta = choice.get("delta") or {}
            text = delta.get("content") or ""
            if isinstance(text, list):
                text = "".join(str(item.get("text", "")) for item in text if isinstance(item, dict))
            if text:
                if not started:
                    self.session.emit("message_start", messageId=identifier, role="assistant")
                    started = True
                content.append(str(text))
                buffered.append(str(text))
                if time.monotonic() - last_flush >= 0.06 or sum(map(len, buffered)) >= 512:
                    flush()
            for call in delta.get("tool_calls") or []:
                index = int(call.get("index", 0))
                if index < 0 or index >= 16:
                    raise ValueError("单轮工具调用超过 16 个")
                value = calls.setdefault(index, {"id": "", "type": "function", "function": {"name": "", "arguments": ""}})
                value["id"] += str(call.get("id") or "")
                for key in ("name", "arguments"):
                    value["function"][key] += str((call.get("function") or {}).get(key) or "")
        if not completed:
            raise ValueError("模型流式响应中断，请重试")
        flush()
        text = "".join(content)
        if started:
            self.session.emit("message_end", messageId=identifier, text=text[:24000])
        return {"choices": [{"message": {"role": "assistant", "content": text, "tool_calls": [calls[index] for index in sorted(calls)]}, "finish_reason": finish_reason}], "usage": usage, "_streamed": started}

    def query(self, messages, **kwargs):
        self.session.check_cancelled()
        # Pi owns context compaction. Preserve whole exchanges and exact tool arguments.
        if len(json.dumps(messages, ensure_ascii=False).encode()) > 4 * MAX_TEXT:
            raise ValueError("模型上下文超过 4 MiB，请先压缩会话")
        purpose = kwargs.get("purpose", "task")
        names = kwargs.get("tools")
        selected = [item for item in self.session.tools if names is None or item["function"]["name"] in names]
        payload = {"model": self.model, "messages": messages, "max_tokens": self.max_tokens,
                   "stream": self.streaming and purpose != "compaction"}
        if 'effort' in self.thinking and purpose != 'compaction':
            payload['reasoning_effort'] = self.thinking['effort']
        if selected and purpose != "compaction":
            payload.update(tools=selected, tool_choice="auto")
        if payload["stream"]:
            payload["stream_options"] = {"include_usage": True}
        if self.free_mode:
            payload = build_free_request(messages, selected, purpose)
        while not HTTP_LIMIT.acquire(timeout=0.1):
            self.session.check_cancelled()
        incoming = queue.Queue(maxsize=1)

        def fetch():
            try:
                active_payload = payload
                for attempt in range(3):
                    raw = self.request(active_payload)
                    if not self.free_mode:
                        incoming.put((True, raw))
                        break
                    try:
                        result = decode_free_response(raw, {item["function"]["name"] for item in selected}, purpose,
                                                      require_finish=any(item.get("role") == "tool" for item in messages))
                    except FreeProtocolError:
                        # Each malformed completion still incurred real gateway usage.
                        # Keep the task's token display aligned with its wallet ledger.
                        self.session.tokens += max(0, int((raw.get("usage") or {}).get("total_tokens", 0)))
                        if attempt == 2:
                            raise FreeProtocolError("Aster 的工具回复格式连续三次无效；未执行其中描述的工具，请重试当前任务") from None
                        self.session.emit("status", text="正在恢复工具调用", step=getattr(self.session, "steps", 0))
                        active_payload = repair_free_request(active_payload, raw)
                        continue
                    incoming.put((True, result))
                    break
            except Exception as error:
                incoming.put((False, error))
            finally:
                HTTP_LIMIT.release()

        threading.Thread(target=fetch, name="Aster-model-request", daemon=True).start()
        while True:
            self.session.check_cancelled()
            try:
                succeeded, result = incoming.get(timeout=0.1)
                if not succeeded:
                    raise result
                break
            except queue.Empty:
                continue
        self.session.check_cancelled()
        choices = result.get("choices") or []
        if not choices:
            raise ValueError("模型没有返回消息")
        message = choices[0].get("message") or {}
        content = message.get("content") or ""
        if isinstance(content, list):
            content = "\n".join(str(item.get("text", "")) for item in content if isinstance(item, dict))
        actions, normalized_calls = [], []
        calls = message.get("tool_calls") or []
        if len(calls) > 16:
            raise ValueError("单轮工具调用超过 16 个")
        for call in calls:
            function = call.get("function") or {}
            arguments = function.get("arguments", "{}")
            try:
                arguments = json.loads(arguments) if isinstance(arguments, str) else arguments
                if not isinstance(arguments, dict):
                    raise ValueError()
            except (ValueError, TypeError):
                # A malformed call is returned to the graph as an observation, so
                # the model can fix its arguments without losing staged work.
                arguments = {"_invalid_arguments": True}
            identifier = str(call.get("id") or uuid.uuid4().hex)
            name = str(function.get("name", ""))
            actions.append({"name": name, "arguments": arguments, "id": identifier})
            normalized_calls.append({"id": identifier, "type": "function", "function": {"name": name, "arguments": json.dumps(arguments, ensure_ascii=False)}})
        if not actions:
            if not str(content).strip():
                raise ValueError("模型返回了空消息")
            actions = [{"name": "finish", "arguments": {"summary": str(content)}, "id": "final"}]
        output = {"role": "assistant", "content": str(content), "usage": result.get("usage") or {}, "extra": {"actions": actions}}
        if normalized_calls:
            output["tool_calls"] = normalized_calls
        self.session.tokens += max(0, int((result.get("usage") or {}).get("total_tokens", 0)))
        if content and not result.get("_streamed"):
            self.session.emit("message", role="assistant", text=str(content)[:24000])
        if choices[0].get("finish_reason") == "length":
            raise ValueError("Aster-free 单次回复达到上限，请分步处理当前任务" if self.free_mode else "模型回复达到 token 上限，请调高回复上限或缩小当前任务后继续")
        return output
