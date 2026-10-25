"""Explicit engine dispatch. Missing runtimes never silently fall back to Pi."""
from pathlib import Path
import os
import platform
import shutil
import re
import subprocess


def node_runtime(environment):
    """Report an executable and its actual version; file presence is not readiness."""
    binary = shutil.which('node', path=environment.get('PATH'))
    result = {'path': binary or '', 'version': '', 'minimum': '22.19.0', 'ready': False, 'reason': ''}
    if not binary:
        result['reason'] = '未找到 Node.js；需要内置 Node.js 22.19+'
        return result
    probe_environment = dict(environment)
    probe_environment.pop('NODE_OPTIONS', None)
    try:
        probe = subprocess.run([binary, '--version'], env=probe_environment, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, timeout=3)
        version = probe.stdout.strip()[:80]
        result['version'] = version
        match = re.fullmatch(r'v?(\d+)\.(\d+)\.(\d+)(?:[-+][A-Za-z0-9.-]+)?', version)
        result['ready'] = probe.returncode == 0 and match is not None and tuple(map(int, match.groups())) >= (22, 19, 0)
        if not result['ready']:
            detail = version or probe.stderr.strip()[:240] or '无有效版本输出'
            result['reason'] = 'Node.js 检查未通过：' + detail + '；需要 22.19+'
    except (OSError, subprocess.TimeoutExpired) as error:
        result['reason'] = 'Node.js 无法启动或检查超时：' + str(error)[:240]
    return result


ENGINES = {
    'pi': {'name': 'Pi Coding Agent 1.3.0', 'version': '1.3.0', 'bundle': 'pi-agent.mjs', 'api': 'openai-chat'},
    'gemini': {'name': 'Gemini CLI 0.64.0', 'version': '0.64.0', 'bundle': 'gemini-agent.mjs', 'api': 'gemini'},
    'codex': {'name': 'Codex 0.163.0', 'version': '0.163.0', 'api': 'openai-responses'},
    'openclaude': {'name': 'OpenClaude 0.33.0', 'version': '0.33.0', 'bundle': 'openclaude-agent.mjs', 'api': 'anthropic'},
}

def engine_info(identifier):
    if identifier not in ENGINES:
        raise ValueError('Unknown Agent engine: ' + str(identifier))
    return ENGINES[identifier]

def codex_binary(base, environment):
    candidates = [Path(base) / 'agent-engines/codex/codex-app-server']
    native = environment.get('ASTER_NATIVE_LIB_DIR')
    if native:
        candidates.insert(0, Path(native) / 'libaster_codex.so')
    found = shutil.which('codex-app-server', path=environment.get('PATH'))
    if found:
        candidates.append(Path(found))
    return next((p for p in candidates if p.is_file() and os.access(p, os.X_OK)), None)

def availability(base, vendor, environment):
    result = []
    node = node_runtime(environment)
    for identifier, info in ENGINES.items():
        ready = False
        reason = ''
        if identifier == 'codex':
            ready = codex_binary(base, environment) is not None
            reason = '' if ready else '需要安装 Android 原生 codex-app-server'
        elif identifier == 'openclaude':
            sdk = Path(base) / 'agent-engines/openclaude/sdk.mjs'
            host = Path(vendor) / info['bundle']
            ready = sdk.is_file() and host.is_file()
            reason = '' if ready else ('缺少 OpenClaude SDK：普通版不包含此资源；请使用本地研究版并查看 SDK 校验日志' if not sdk.is_file() else '缺少 OpenClaude 宿主资源，请更新应用后重启运行环境')
        else:
            ready = (Path(vendor) / info['bundle']).is_file()
            reason = '' if ready else '引擎资源尚未构建'
        if identifier != 'codex' and not node['ready']:
            ready = False
            reason = node['reason']
        result.append({'id': identifier, 'name': info['name'], 'version': info['version'], 'available': ready, 'reason': reason, 'api': info['api']})
    return result

def require_available(identifier, base, vendor, environment):
    engine_info(identifier)
    entry = next(item for item in availability(base, vendor, environment) if item['id'] == identifier)
    if not entry['available']:
        raise ValueError(entry['name'] + ': ' + entry['reason'])
    return entry
