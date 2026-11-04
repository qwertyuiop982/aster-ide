"""Terminal authorization independent from UI labels and engine error messages."""
import shlex

DEFAULT_READ_ONLY = ('pwd', 'ls', 'rg --files', 'git status', 'git diff', 'git log')


def native(session):
    return getattr(session, 'permission_mode', 'review') == 'native'


def unchecked(session):
    return native(session) and getattr(session, 'native_commands_unchecked', False)


def _tokens(value):
    # Rules authorize a single literal argv prefix. Shell syntax still prompts.
    if not isinstance(value, str) or any(char in value for char in '\n\r;&|<>`$(){}*?\\'):
        return None
    try:
        result = shlex.split(value, posix=True)
    except ValueError:
        return None
    if not result or '=' in result[0]:
        return None
    return result


def allowed(session, command):
    args = _tokens(command)
    if not args:
        return False
    rules = (session.request.get('configuration') or {}).get('terminalAllowedCommands', '\n'.join(DEFAULT_READ_ONLY))
    rules = rules.splitlines() if isinstance(rules, str) else rules
    if not isinstance(rules, list):
        return False
    for rule in rules[:100]:
        prefix = _tokens(rule)
        if prefix and args[:len(prefix)] == prefix:
            return True
    return False


def read_only(command):
    args = _tokens(command)
    if not args:
        return False
    return any(args[:len(prefix)] == prefix for prefix in (shlex.split(value) for value in DEFAULT_READ_ONLY))


def report(session, reason, *, command=''):
    session.emit('error', text='执行审批：' + reason + ('\n' + command[:2000] if command else ''), recoverable=True)


def permission_prompt(session, text):
    if not native(session):
        return text
    replacements = {
        'Edits are staged for user review: never claim they are already applied.': 'Edits apply immediately to the original project; describe their actual state.',
        "Commands run in the staged project only after the user's approval; a rejection is final for that command.": 'Commands run in the original project under the configured terminal allow rules and command approval switch; an explicit user rejection is final.',
        'Mode: EXECUTE. Inspect, plan, stage edits, request command approval for validation, and finish with proposed changes ready for review.': 'Mode: EXECUTE. Inspect, plan, implement and validate in the original project, then report completed changes.',
        'All edits are in the staged cwd and remain proposals until the user applies the preview.': 'All edits apply immediately in the original project cwd; no preview or automatic project backup is created.',
        'Review get_changes and validate when appropriate.': 'Inspect the resulting project files and validate when appropriate.',
        'Work in the staged project': 'Work in the original project',
    }
    for before, after in replacements.items():
        text = text.replace(before, after)
    return text
