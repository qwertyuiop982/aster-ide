"""Transient interactive UI adapters shared by native engines; never grant permissions."""
import json
import math
import re
from urllib.parse import urlsplit


def normalize_questions(questions):
    if not isinstance(questions, list) or not 1 <= len(questions) <= 32:
        raise ValueError('交互提问需要 1–32 个问题')
    normalized, identifiers = [], set()
    for index, source in enumerate(questions):
        if not isinstance(source, dict):
            raise ValueError('提问必须是对象')
        question = dict(source)
        identifier = str(question.get('id') or index)
        title = question.get('question') or question.get('title')
        if not isinstance(title, str) or not title.strip() or len(title) > 24000:
            raise ValueError('提问内容为空或超过 24000 字符')
        if len(identifier) > 128 or identifier in identifiers:
            raise ValueError('提问编号重复或过长')
        identifiers.add(identifier)
        options = question.get('options') or []
        if question.get('type') == 'yesno':
            options = [{'label': 'Yes', 'description': '是'}, {'label': 'No', 'description': '否'}]
        elif question.get('type') == 'text':
            options = []
        if not isinstance(options, list) or len(options) > 64:
            raise ValueError('提问选项必须是数组且最多 64 项')
        converted = []
        for option in options:
            option = {'label': option, 'description': ''} if isinstance(option, str) else option
            if not isinstance(option, dict) or not isinstance(option.get('label'), str) or not option['label'].strip():
                raise ValueError('提问选项缺少有效文字')
            if len(option['label']) > 4000:
                raise ValueError('提问选项过长')
            converted.append({'label': option['label'], 'description': str(option.get('description') or '')[:4000]})
        normalized.append({**question, 'id': identifier, 'question': title, 'options': converted,
                           'isSecret': bool(question.get('isSecret', question.get('is_secret', False)))})
    return normalized


def request_questions(session, questions, **metadata):
    questions = normalize_questions(questions)
    answers = {}
    # Android presents six questions at a time without dropping the remainder.
    for offset in range(0, len(questions), 6):
        result = session.client_tool('user_input', {**metadata, 'questions': questions[offset:offset + 6], 'timeoutSeconds': 540})
        if result.get('ok') is not True:
            raise ValueError(str(result.get('error') or 'Android 无法显示交互窗口'))
        if result.get('dismissed'):
            return {'ok': True, 'dismissed': True, 'answers': {}, 'timedOut': bool(result.get('timedOut'))}
        incoming = result.get('answers')
        if not isinstance(incoming, dict):
            raise ValueError('Android 提问应答格式无效')
        for question in questions[offset:offset + 6]:
            value = incoming.get(question['id'], [])
            if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
                raise ValueError('Android 提问答案格式无效')
            if question.get('required', True) and not any(item.strip() for item in value):
                raise ValueError('Android 提交缺少必填答案')
            answers[question['id']] = value
    return {'ok': True, 'dismissed': False, 'answers': answers}


def validate_form(value, schema, path='表单'):
    """Validate MCP's primitive form fields, including numeric/enum/array constraints."""
    if not isinstance(schema, dict):
        raise ValueError('MCP 表单 schema 无效')
    if value is None and schema.get('type') == 'null':
        return
    for keyword in ('anyOf', 'oneOf'):
        if keyword in schema:
            valid = 0
            for branch in schema[keyword]:
                try:
                    validate_form(value, branch, path)
                    valid += 1
                except ValueError:
                    pass
            if not valid or (keyword == 'oneOf' and valid != 1):
                raise ValueError(path + ' 不符合可选字段类型')
    for branch in schema.get('allOf', []):
        validate_form(value, branch, path)
    kind = schema.get('type')
    checks = {'object': isinstance(value, dict), 'array': isinstance(value, list), 'string': isinstance(value, str),
              'boolean': isinstance(value, bool), 'integer': isinstance(value, int) and not isinstance(value, bool),
              'number': isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value), 'null': value is None}
    if kind and not any(checks.get(item, False) for item in (kind if isinstance(kind, list) else [kind])):
        raise ValueError(path + ' 字段类型无效')
    if 'enum' in schema and not any(type(value) is type(item) and value == item for item in schema['enum']):
        raise ValueError(path + ' 不在可选值中')
    if 'const' in schema and value != schema['const']:
        raise ValueError(path + ' 不符合固定值')
    if isinstance(value, dict):
        if any(key not in value for key in schema.get('required', [])):
            raise ValueError(path + ' 缺少必填字段')
        properties = schema.get('properties') or {}
        for key, item in value.items():
            if key in properties:
                validate_form(item, properties[key], path + '.' + key)
            elif schema.get('additionalProperties') is False:
                raise ValueError(path + ' 包含未知字段 ' + key)
            elif isinstance(schema.get('additionalProperties'), dict):
                validate_form(item, schema['additionalProperties'], path + '.' + key)
    if isinstance(value, str):
        if len(value) < schema.get('minLength', 0) or len(value) > schema.get('maxLength', 24000):
            raise ValueError(path + ' 文字长度无效')
        if schema.get('pattern'):
            try:
                if not re.search(schema['pattern'], value):
                    raise ValueError(path + ' 文字格式无效')
            except re.error:
                raise ValueError('MCP 表单正则表达式无效') from None
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if not math.isfinite(value):
            raise ValueError(path + ' 数值无效')
        if 'minimum' in schema and value < schema['minimum'] or 'maximum' in schema and value > schema['maximum']:
            raise ValueError(path + ' 数值超出范围')
        if 'exclusiveMinimum' in schema and value <= schema['exclusiveMinimum'] or 'exclusiveMaximum' in schema and value >= schema['exclusiveMaximum']:
            raise ValueError(path + ' 数值超出范围')
    if isinstance(value, list):
        if len(value) < schema.get('minItems', 0) or len(value) > schema.get('maxItems', 64):
            raise ValueError(path + ' 项目数量无效')
        if schema.get('uniqueItems') and len({json.dumps(item, sort_keys=True) for item in value}) != len(value):
            raise ValueError(path + ' 不允许重复项目')
        for item in value:
            validate_form(item, schema.get('items') or {}, path)


def mcp_elicitation(session, params):
    mode = params.get('mode', 'form')
    header = str(params.get('serverName') or 'MCP')[:100]
    message = str(params.get('message') or '')
    if mode == 'url':
        url = str(params.get('url') or '')
        parsed = urlsplit(url)
        if parsed.scheme not in ('https', 'http') or not parsed.hostname or parsed.username or parsed.password:
            return {'action': 'decline', 'content': None}
        result = request_questions(session, [{'id': 'mcp', 'header': header,
            'question': message + '\n请打开链接，完成网页操作后再选择“完成”。', 'url': url,
            'allowCustom': False, 'options': ['完成', '取消']}])
        accepted = not result['dismissed'] and result['answers'].get('mcp') == ['完成']
        return {'action': 'accept' if accepted else 'cancel', 'content': None}
    if mode not in ('form', 'openai/form', 'openaiForm'):
        session.emit('status', text='当前 Android 客户端不支持此 MCP 验证类型，已明确拒绝', step=session.steps)
        return {'action': 'decline', 'content': None}
    schema = params.get('requestedSchema') or {}
    properties = schema.get('properties') or {}
    required = set(schema.get('required') or [])
    simple = schema.get('type') == 'object' and 1 <= len(properties) <= 32 and all(
        isinstance(spec, dict) and spec.get('type') in ('string', 'integer', 'number', 'boolean')
        and not any(key in spec for key in ('anyOf', 'oneOf', '$ref')) for spec in properties.values())
    questions = []
    if simple:
        for key, spec in properties.items():
            options = spec.get('enum') or ([True, False] if spec['type'] == 'boolean' else [])
            labels = [str(value) if isinstance(value, str) else json.dumps(value, ensure_ascii=False) for value in options]
            questions.append({'id': key, 'header': header, 'question': (message + '\n' if not questions else '') + str(spec.get('title') or key) + '\n' + str(spec.get('description') or ''),
                'options': labels, 'allowCustom': not bool(options), 'required': key in required,
                'placeholder': spec['type'], 'isSecret': spec.get('format') == 'password' or bool(spec.get('writeOnly'))})
    else:
        questions = [{'id': 'mcp', 'header': header, 'question': message + '\n请按表单要求输入 JSON：\n' + json.dumps(schema, ensure_ascii=False), 'options': []}]
    while True:
        result = request_questions(session, questions)
        if result['dismissed']:
            return {'action': 'cancel', 'content': None}
        try:
            if simple:
                content = {}
                for key, spec in properties.items():
                    values = result['answers'].get(key) or []
                    if not values:
                        continue
                    content[key] = values[0] if spec['type'] == 'string' else json.loads(values[0])
            else:
                content = json.loads(result['answers']['mcp'][0])
            if not isinstance(content, dict):
                raise ValueError('MCP 表单需要 JSON 对象')
            validate_form(content, schema)
        except (ValueError, TypeError, KeyError) as error:
            session.emit('status', text='表单未提交：' + str(error)[:300] + '，请更正或取消。', step=session.steps)
            questions[0] = {**questions[0], 'question': '上次输入无效：' + str(error)[:300] + '\n' + questions[0]['question'][-20000:]}
            continue
        return {'action': 'accept', 'content': content}
