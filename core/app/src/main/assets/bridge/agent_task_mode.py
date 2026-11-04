"""Composer interaction policy, independent of permissions and execution budgets."""
from copy import deepcopy

QUESTION_TOOLS = frozenset({'user_input', 'request_user_input', 'request_user_input_async', 'ask_user'})


def long_task_enabled(session):
    return (session.request.get('configuration') or {}).get('longTaskMode') is True


def task_mode_prompt(session):
    if long_task_enabled(session):
        return (
            '当前交互策略：长任务模式 / 无人守候。\n'
            'Long autonomous task mode; the user may be away. Prioritize sustained execution and completion. '
            'Within the authorized scope, inspect, plan, implement and validate without repeated check-ins. '
            'For reversible implementation details and ordinary preferences, choose reasonable defaults, record material assumptions, and continue. '
            'Give question tools lower priority: use user_input, request_user_input, request_user_input_async or ask_user only for a concrete blocker '
            'that cannot be resolved from available evidence or an authorized default, or for a decision requiring actual user authorization. '
            'Do not invent an answer, treat silence as approval, bypass command approvals, expand permissions or exceed existing task budgets. '
            'If blocked, continue independent authorized work and report the exact blocker honestly. '
            'Keep progress updates concise and finish with results and validation. This policy also applies to every subagent; include it when delegating. '
            'This explicit interaction policy takes precedence over generic instructions about asking questions or continuing autonomously.'
        )
    return (
        '当前交互策略：非长自主任务（有人值守）。\n'
        'This is NOT a long autonomous task. Give question tools HIGH priority when missing information, a meaningful preference '
        'or an ambiguous scope would materially change the deliverable. Prefer the available user_input, request_user_input, '
        'request_user_input_async or ask_user tool to guessing the user decision. Ask a small, focused batch with concrete options '
        'before committing to that branch; continue independent work while waiting only when supported by the engine. '
        'Do not treat an unanswered or dismissed question as consent. Clearly specified tasks should still be completed directly; '
        'do not ask about routine implementation details, repeat answered questions or request permission already granted. '
        'This policy also applies to every subagent; include it when delegating. Existing permissions, approvals and task budgets remain unchanged. '
        'This explicit interaction policy takes precedence over generic instructions to avoid questions or continue autonomously.'
    )


def prioritize_question_tools(session, tools):
    # Tool APIs have no portable numeric weight; use descriptions and ordering.
    enabled = long_task_enabled(session)
    result = []
    for original in tools:
        item = deepcopy(original)
        function = item.get('function', {})
        if function.get('name') in QUESTION_TOOLS:
            prefix = ('BLOCKER ONLY in long autonomous mode: minimize interruptions. ' if enabled else
                      'HIGH PRIORITY in non-long autonomous mode: clarify material ambiguity and user preferences before guessing. ')
            function['description'] = prefix + str(function.get('description', ''))
        result.append(item)
    if not enabled:
        result.sort(key=lambda item: item.get('function', {}).get('name') not in QUESTION_TOOLS)
    return result
