"""JSON MCP Streamable HTTP sessions, explicitly enabled per engine."""
import json
import urllib.parse
import urllib.request
import uuid

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        raise ValueError('MCP 重定向已拒绝；请配置服务器的最终 URL')

class McpClient:
    def __init__(self, endpoint, headers, session):
        parsed = urllib.parse.urlsplit(endpoint)
        if parsed.scheme not in ('https', 'http') or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
            raise ValueError('MCP URL 无效')
        self.endpoint, self.headers, self.session = endpoint, headers, session
        self.session_id = None

    def request(self, method, params=None, notification=False):
        self.session.check_cancelled()
        body = {'jsonrpc': '2.0', 'method': method, 'params': params or {}}
        identifier = uuid.uuid4().hex
        if not notification: body['id'] = identifier
        headers = {**self.headers, 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-03-26'}
        if self.session_id: headers['Mcp-Session-Id'] = self.session_id
        request = urllib.request.Request(self.endpoint, data=json.dumps(body).encode(), headers=headers, method='POST')
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=15) as response:
            self.session_id = response.headers.get('Mcp-Session-Id') or self.session_id
            if notification: return {}
            raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024: raise ValueError('MCP 响应超过 1 MiB')
            if 'text/event-stream' in response.headers.get('Content-Type', ''):
                values = []
                for block in raw.decode().replace('\r\n', '\n').split('\n\n'):
                    data = '\n'.join(line[5:].lstrip() for line in block.splitlines() if line.startswith('data:'))
                    if data: values.append(json.loads(data))
                value = next((item for item in values if item.get('id') == identifier), None)
            else: value = json.loads(raw)
        self.session.check_cancelled()
        if not isinstance(value, dict) or value.get('id') != identifier: raise ValueError('MCP 响应 ID 不匹配')
        if value.get('error'): raise ValueError('MCP 调用失败：' + str(value['error'])[:800])
        return value.get('result', {})

    def close(self):
        pass

    def initialize(self):
        result = self.request('initialize', {'protocolVersion': '2025-03-26', 'capabilities': {}, 'clientInfo': {'name': 'aster', 'version': '1.0.0'}})
        if result.get('protocolVersion') != '2025-03-26': raise ValueError('MCP 服务器未协商支持的协议版本')
        self.request('notifications/initialized', notification=True)


def configured_client(session, server):
    import os
    import re
    if not isinstance(server, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', server): raise ValueError('MCP 服务器名称无效')
    configured = str((session.request.get('configuration') or {}).get('mcpServers', '')).split(',')
    if server not in [item.strip() for item in configured]: raise ValueError('此 MCP 服务器未启用')
    file = session.workspace / '.aster/mcp.json'
    if not file.is_file() or file.is_symlink() or session.workspace not in file.resolve().parents or file.stat().st_size > 32000:
        raise ValueError('项目缺少有效的 .aster/mcp.json')
    config = json.loads(file.read_text()).get('mcpServers', {}).get(server)
    if not isinstance(config, dict): raise ValueError('MCP 服务器配置无效')
    transport = config.get('type', 'stdio' if 'command' in config else 'http')
    if transport == 'stdio':
        from agent_mcp_stdio import StdioMcpClient
        return StdioMcpClient(config, session)
    if transport != 'http': raise ValueError('不支持的 MCP 传输类型')
    headers = {}
    for name, variable in config.get('headerEnv', {}).items():
        value = session.environment.get(variable)
        if not value: raise ValueError('缺少 MCP 请求头环境变量：' + str(variable))
        headers[str(name)] = value
    return McpClient(str(config.get('url', '')), headers, session)


def execute_mcp(session, arguments):
    server = arguments.get('server')
    if not session.approve_external('MCP ' + str(server) + ' · ' + str(arguments.get('tool') or 'tools/list')):
        return {'ok': False, 'error': '用户未批准 MCP 连接或调用'}
    client = configured_client(session, server)
    try:
        client.initialize()
        tools, cursor, seen = [], None, set()
        for page in range(20):
            listing = client.request('tools/list', {'cursor': cursor} if cursor else {})
            items = listing.get('tools', [])
            if not isinstance(items, list) or any(not isinstance(item, dict) for item in items):
                raise ValueError('MCP tools/list 格式无效')
            tools.extend(items)
            if len(tools) > 1000: raise ValueError('MCP 工具数量超过1000')
            cursor = listing.get('nextCursor')
            if not cursor: break
            if not isinstance(cursor, str) or cursor in seen: raise ValueError('MCP 分页游标无效')
            seen.add(cursor)
        else: raise ValueError('MCP 工具分页超过20页')
        name = arguments.get('tool')
        if not name: return {'tools': tools}
        if not any(item.get('name') == name for item in tools):
            raise ValueError('MCP 服务器没有该工具')
        params = arguments.get('arguments', {})
        if not isinstance(params, dict): raise ValueError('MCP 参数必须为 JSON 对象')
        result = client.request('tools/call', {'name': name, 'arguments': params})
        if result.get('isError'): return {**result, 'ok': False}
        return result
    finally:
        client.close()
