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


def collaboration_prompt(session):
    request = getattr(session, 'request', None) or {}
    config = request.get('configuration') or {}
    mode = config.get('collaborationMode', 'assist')
    child = request.get('isSubagent') is True
    if mode == 'off':
        return ''
    if mode == 'extreme' and config.get('subagentsUnlimited') is True:
        if child:
            return (
                '【极端协作：子代理强制职责 / EXTREME CHILD CONTRACT】\n'
                '你必须落实父代理的全部任务要求。接到可拆分任务后，必须先拆成边界明确的工作包，'
                '立即派出多个子代理，并明确要求它们继续分派可独立完成的工作。不得只写计划而不派发。'
                '每份委派必须包含目标、已知上下文、负责范围、交付物、验收标准和回报要求。'
                '可直接完成不可再拆分的叶子工作；不得用无意义的递归代替执行，不得把原任务原样重复下发。'
                '你必须收集下级结果、解决冲突，并向父代理返回实际产物、验证证据、进度与阻塞。'
                '不设置代理数量配额。所有层级继承用户授权范围、现有工具权限、取消信号与执行预算。'
                '未取得执行证据时，禁止声称已经完成。'
            )
        return (
            '【极端协作：主代理强制职责 / EXTREME ROOT CONTRACT】\n'
            '角色判定：直接接收用户任务的是主代理；通过原生 spawn_agent、invoke_agent 或 subagent 收到委派的是子代理，'
            '即使继承本提示词也不得把自己当主代理。子代理必须拆分独立工作并派出下级代理；'
            '不可再拆分的叶子代理必须实际执行并回报证据。以下主代理禁令仅适用于根主代理。\n'
            '你的唯一身份是首批代理派发者与工作进度汇报者。必须遵守以下职责边界：\n'
            '1. 收到任务后，必须立即组织并实际派出覆盖任务各独立方向的首批子代理；不得停在计划。\n'
            '2. 首批每个子代理必须收到完整工作要求：用户目标、约束、相关上下文、负责范围、'
            '交付物、验收标准，以及“必须继续拆分可独立工作并派出子子代理、逐层落实要求”的明确指令。\n'
            '3. 主代理禁止亲自执行探查、检索资料、读写项目文件、编写代码、运行命令或测试。'
            '所有实际执行必须交给子代理体系。任务简单、紧急、工具方便均不构成例外。\n'
            '4. 首批派发后，主代理只接收工作状态、汇报真实进度、呈现结果和阻塞；'
            '后续拆解与执行由首批子代理协调。只有首批任务明确遗漏或失败时，才补派替代代理。\n'
            '5. 不设置代理数量配额；按实际独立工作展开，不得制造空任务或无限重复委派。\n'
            '6. 未收到执行代理的证据，禁止声称成功。不得自行补做子代理没有完成的工作。'
            '继承用户授权范围、工具权限、取消信号与执行预算；提示词不授予额外权限。'
        )
    if mode == 'team':
        if child:
            return (
                '【团队协作：执行代理强制职责 / TEAM WORKER CONTRACT】\n'
                '你是执行代理，必须亲自完成主代理分配的探查、实现、资料整理或验证工作。'
                '严格遵守分配范围与权限，实际调用所需工具，不得只返回计划、建议或未经验证的完成声明。'
                '回报必须包含实际结果、改动文件、验证方式与结果、未决问题和下一步需求。'
                '主代理的“不得执行”限制只约束主代理，不约束你。'
            )
        return (
            '【团队协作：主代理强制职责 / TEAM ROOT CONTRACT】\n'
            '角色判定：直接接收用户任务的是主代理；通过原生 spawn_agent、invoke_agent 或 subagent 收到委派的是执行子代理，'
            '即使继承本提示词也必须执行分配的工作并回报证据。以下主代理禁令不得套用到执行子代理。\n'
            '你只能担任编排者与结果整合者，禁止作为执行者。以下规则必须全部遵守：\n'
            '1. 全部探查、资料检索、代码编写、文件读写、命令运行和测试必须委派给子代理。'
            '主代理禁止亲自执行上述工作，即使任务很小、子代理失败或直接执行更快也不得例外。\n'
            '2. 必须实际发出代理任务，不得仅描述将如何委派。每次委派都要明确任务范围、'
            '所有权、输入上下文、交付物和验收标准。\n'
            '3. 主代理只负责理解用户要求、拆分任务、调用协作工具派发和等待代理、'
            '整理代理返回的信息、消解结论冲突，以及向用户汇报。\n'
            '4. 结果不足、互相矛盾、验证缺失或出现新问题时，必须继续发出新的子代理任务；'
            '不得自行接手执行，也不得在证据不足时结束。\n'
            '5. 最终交付必须整合子代理的实际产物与验证证据，并明确未完成事项与阻塞。'
            '继承用户授权与工具权限；不得虚构代理结果。'
        )
    prefix = '极端协作尚未启用：必须配合 Ultra；当前使用辅助策略。' if mode == 'extreme' else ''
    return prefix + '辅助协作：根据任务大小与独立工作量适当派出子代理；小任务由主代理直接完成。'
