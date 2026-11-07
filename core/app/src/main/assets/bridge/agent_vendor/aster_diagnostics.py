"""Discard only the Android linker configuration warning known to be harmless."""

import re

_WARNINGS = tuple(
    ('WARNING: linker: Warning: failed to find generated linker configuration from '
     + quote + '/linkerconfig/ld.config.txt' + quote).encode()
    for quote in ('"', "'")
)


_WARNING_ENDINGS = tuple(value + ending for value in _WARNINGS for ending in (bytes((10,)), bytes((13,10))))

class LinkerWarningFilter:
    """Filter fragmented byte streams without buffering ordinary prompts or output."""
    def __init__(self):
        self.pending = b''
        self.line_start = True

    def feed(self, data=b'', final=False):
        data = self.pending + data
        self.pending = b''
        output = []
        while data:
            if self.line_start:
                candidates = _WARNING_ENDINGS
                matched = next((value for value in candidates if data.startswith(value)), None)
                if matched is not None:
                    data = data[len(matched):]
                    continue
                if any(value.startswith(data) for value in candidates):
                    if final:
                        if data not in _WARNINGS and data not in tuple(value + b'\r' for value in _WARNINGS):
                            output.append(data)
                    else:
                        self.pending = data
                    break
                self.line_start = False
            index = data.find(b'\n')
            if index < 0:
                output.append(data)
                break
            output.append(data[:index + 1])
            data = data[index + 1:]
            self.line_start = True
        return b''.join(output)


def strip_linker_warnings(text):
    return LinkerWarningFilter().feed(text.encode('utf-8'), final=True).decode('utf-8')



def console_text(text):
    """Readable console history; raw terminal bytes remain in the private transcript."""
    import re
    text = re.sub(r'\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)', '', text)
    text = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', text)
    return text.replace('\r\n', '\n').replace('\r', '\n')


def strip_runtime_diagnostics(text):
    """Remove known routine PRoot lines while retaining errors and exit status."""
    import re
    prefix = r"(?:proot|(?:[^\s]+/)?libaster_proot[.]so)"
    routine = re.compile(r"^" + prefix + r" (?:warning|info):", re.IGNORECASE)
    expired = re.compile(r"^" + prefix + r" error: can(?:'t|t) remove '[^']*/proot-[^']+': No such file or directory$", re.IGNORECASE)
    text = strip_linker_warnings(text)
    lines = []
    for line in text.splitlines(keepends=True):
        visible = console_text(line).strip()
        if visible.encode() in _WARNINGS or routine.match(visible) or expired.fullmatch(visible):
            continue
        lines.append(line)
    return ''.join(lines)


def clean_runtime_output(value, depth=0):
    """Handle both structured native results and JSON embedded in tool events."""
    if depth > 20:
        return value
    if isinstance(value, dict):
        return {key: clean_runtime_output(item, depth + 1) for key, item in value.items()}
    if isinstance(value, list):
        return [clean_runtime_output(item, depth + 1) for item in value]
    if not isinstance(value, str):
        return value
    if value.lstrip().startswith(('{', '[')) and ('proot' in value or 'WARNING: linker:' in value):
        import json
        try:
            decoded = json.loads(value)
            cleaned = clean_runtime_output(decoded, depth + 1)
            if cleaned != decoded:
                return json.dumps(cleaned, ensure_ascii=False)
        except (ValueError, TypeError):
            pass
    return strip_runtime_diagnostics(value)
