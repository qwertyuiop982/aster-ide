'''Validated thinking controls for the pinned native engine transports.'''
import re


def available_levels(engine, model, max_tokens=4096):
    model = str(model).lower()
    if engine in ('pi', 'codex'):
        return ('auto', 'off', 'minimal', 'low', 'medium', 'high', 'xhigh')
    if engine == 'openclaude':
        return ('auto', 'off') + (('low', 'medium', 'high') if max_tokens >= 2048 and 'claude-3-' not in model else ())
    if engine == 'gemini':
        if re.search(r'gemini-3(?:[.][0-9]+)?-pro', model):
            return ('auto', 'low', 'high')
        if re.search(r'gemini-3(?:[.][0-9]+)?-flash', model) and 'flash-lite' not in model:
            return ('auto', 'minimal', 'low', 'medium', 'high')
        if re.search(r'(?:^|/)gemini-2[.]5-(?:pro|flash)(?:-|$)', model):
            minimum = 512 if 'flash-lite' in model else 128
            return ('auto',) + (() if 'pro' in model else ('off',)) + (('low', 'medium', 'high') if max_tokens >= minimum + 256 else ())
        return ('auto',)
    raise ValueError('Unknown thinking engine')


def _legacy_thinking(engine, configuration):
    level = configuration.get('thinkingLevel', 'auto')
    model = str(configuration.get('model', '')).lower()
    maximum = configuration.get('maxTokens', 4096)
    if type(maximum) is not int or maximum < 64 or maximum > 65536:
        raise ValueError('Invalid output budget for thinking')
    if not isinstance(level, str) or level not in available_levels(engine, model, maximum):
        raise ValueError('Thinking level is not supported by this engine/model/output budget')
    result = {'level': level}
    if level == 'auto':
        return result
    if engine in ('pi', 'codex'):
        return {**result, 'effort': 'none' if level == 'off' else level}
    if engine == 'gemini' and 'gemini-2.5' not in model:
        return {**result, 'gemini': {'thinkingLevel': level.upper()}}
    minimum = 1024 if engine == 'openclaude' else 512 if 'flash-lite' in model else 128
    limit = 32000 if engine == 'openclaude' else 32768 if 'pro' in model else 24576
    cap = min(maximum - 256, limit)
    budget = 0 if level == 'off' else minimum if level == 'low' else (minimum + cap) // 2 if level == 'medium' else cap
    if engine == 'gemini':
        return {**result, 'budgetTokens': budget, 'gemini': {'thinkingBudget': budget}}
    return {**result, 'budgetTokens': budget, **({'effort': level} if level != 'off' else {})}

def resolve_thinking(engine, configuration):
    mode = configuration.get("thinkingMode", "legacy")
    if engine == 'gemini' and configuration.get('protocol') == 'openai':
        level = configuration.get('thinkingLevel', 'auto')
        if mode == 'budget':
            raise ValueError('OpenAI protocol does not support a fixed Gemini thinking budget')
        if mode == 'auto' or mode == 'legacy' and level == 'auto':
            return {'mode': 'auto', 'level': 'auto'}
        if mode == 'off' or mode == 'legacy' and level == 'off':
            return {'mode': 'off', 'level': 'off'}
        if mode not in ('legacy', 'strength') or level not in ('minimal', 'low', 'medium', 'high', 'xhigh'):
            raise ValueError('Invalid OpenAI thinking strength')
        return {'mode': 'strength', 'level': level, 'effort': level}
    if mode == "legacy":
        if engine == 'openclaude':
            level = configuration.get('thinkingLevel', 'auto')
            model = str(configuration.get('model', '')).lower()
            if not isinstance(level, str) or level not in ('auto', 'off', 'minimal', 'low', 'medium', 'high', 'xhigh'):
                raise ValueError('Invalid OpenClaude thinking selection')
            if level == 'auto': return {'level': 'auto'}
            if level == 'off': return {'level': 'off', 'budgetTokens': 0}
            if re.search(r'sonnet-4-6|opus-4-[678]', model):
                return {'mode': 'strength', 'level': level, 'effort': level}
            if level in ('minimal', 'low', 'medium', 'high', 'xhigh'):
                maximum = configuration.get('maxTokens', 4096)
                cap = min(maximum - 256, 32000)
                budget = 1 if level == 'minimal' else 1024 if level == 'low' else (1024 + cap) // 2 if level == 'medium' else cap
                return {'mode': 'budget', 'level': 'auto', 'budgetTokens': budget}
            raise ValueError('Invalid OpenClaude thinking selection')
        return _legacy_thinking(engine, configuration)
    model = str(configuration.get("model", "")).lower()
    maximum = configuration.get("maxTokens", 4096)
    if type(maximum) is not int or not 64 <= maximum <= 65536:
        raise ValueError("Invalid output budget")
    if mode == "auto":
        return {"mode": mode, "level": "auto"}
    if mode == "off":
        return {**_legacy_thinking(engine, {**configuration, "thinkingLevel": "off"}), "mode": mode}
    if mode == "strength":
        level = configuration.get("thinkingLevel")
        supported = engine in ("pi", "codex") or engine == "gemini" and "gemini-3" in model or engine == "openclaude"
        valid_levels = ("minimal", "low", "medium", "high", "xhigh") if engine == "openclaude" else available_levels(engine, model, maximum)
        if not supported or not isinstance(level, str) or level in ("auto", "off") or level not in valid_levels:
            raise ValueError("Model does not support selected thinking strength")
        if engine == "gemini":
            return {"mode": mode, "level": level, "gemini": {"thinkingLevel": level.upper()}}
        return {"mode": mode, "level": level, "effort": level}
    if mode == "budget":
        supported = engine == "openclaude" or engine == "gemini" and re.search(r"(?:^|/)gemini-2[.]5-(?:pro|flash)(?:-|$)", model)
        if not supported:
            raise ValueError("Model does not support fixed thinking budget")
        minimum = 1 if engine == "openclaude" else 512 if "flash-lite" in model else 128
        limit = 32000 if engine == "openclaude" else 32768 if "pro" in model else 24576
        budget = configuration.get("thinkingBudget")
        cap = min(maximum - 1, limit)
        if type(budget) is not int or not minimum <= budget <= cap:
            raise ValueError("Thinking budget exceeds model or output limits")
        result = {"mode": mode, "level": "auto", "budgetTokens": budget}
        if engine == "gemini":
            result["gemini"] = {"thinkingBudget": budget}
        return result
    raise ValueError("Unknown thinking mode")
