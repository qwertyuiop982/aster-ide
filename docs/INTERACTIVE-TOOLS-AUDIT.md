# 交互工具源码检查记录

本记录覆盖 Codex / Gemini 当前接入的交互工具和共用 Android 回传链路。检查依据是工程内固定版本的上游协议、工具实现和 Aster 适配代码。

本轮仅做源码检查、Python 语法解析、Gemini 工具包构建与 APK assemble；未运行单元测试、集成测试、lint 或真机验证。以下结果描述代码改动，不代表设备验证通过。

| 工具或链路 | 发现的问题与处理 |
| --- | --- |
| Codex `request_user_input` | 统一问题格式并分批显示；保留题目 ID；提交结果按原生 `answers` 协议回传；取消不生成答案。 |
| Codex `request_user_input_async` | 原先丢弃 `AgentMessage.questions`。现在解析 `title` 和字符串选项，显示可回答窗口；运行中的回合使用 `turn/steer`，已正常结束的回合在原线程启动后续回合。 |
| Codex `send_message_to_user_async` | 这是消息通知，不自动产生问答窗口。保留主代理消息和子代理通知；只有结构化 `questions` 才触发问答入口。 |
| Gemini `ask_user` | 接入单选、多选、自由输入和 yes/no；原生 `onConfirm` 收到实际的提交或取消结果；取消不再作为执行异常代替原生取消回调。 |
| Gemini `exit_plan_mode` | 展示计划内容，提供“批准计划 / 退回修改”和可选修改意见，调用上游原生确认回调；保留原生计划校验和状态管理。 |
| 命令、stdin、文件和额外权限审批 | 普通状态或命令输出不再清掉正在等待的审批；网络专用请求也有确认入口；展示网络和额外权限详情。问答答案不作为命令授权。 |
| Gemini 原生执行/MCP 确认 | 复用已完成的同一次命令/MCP审批，避免重复窗口；其他新增确认仍使用原生回调并展示相关内容。 |
| MCP form elicitation | Codex 与 Gemini 共用 Android 输入；常见字符串、数值、布尔、枚举字段逐项填写；复杂结构允许输入 JSON。校验失败重新提示，不把无效输入当成功。 |
| MCP URL elicitation | 提供可打开的 HTTP(S) 链接和明确的完成/取消选项。打开链接不等于完成验证。 |
| MCP 专用设备或平台验证 | 当前 Android 客户端不能处理的特殊模式明确返回 decline，并显示状态；没有假装完成。 |
| 并行与子代理 | 子代理在问答/规划模式可转发提问；问题排队；读取原生事件与等待用户回答分离，避免阻塞取消或原生自动解决通知。 |
| 超时与过期 | 原生 `serverRequest/resolved`、客户端请求结束、任务停止或超时会关闭待回答窗口；重复和过期提交不会被当成新回答。 |
| Android 问题解析 | 支持对象或字符串选项、`question` 或 `title`、可选问题和密码输入；不再静默截掉第九个以后的选项。 |
| 工具卡片 | 将原先笼统的 `multi_agents` 细分为等待消息、启动子代理、发送消息等实际动作。等待结束不代表发生过审批。 |
| Pi / OpenClaude | 当前固定桥接注册表没有暴露上游原生问答工具。本次没有把普通文本推断成工具问题，也没有新增这些引擎的工具注册。它们共用的命令审批与子代理回传修复已包含。 |

主要实现文件：

- `core/app/src/main/assets/bridge/agent_interactions.py`
- `core/app/src/main/assets/bridge/agent_codex.py`
- `core/app/src/main/assets/bridge/agent_codex_runtime.py`
- `core/app/src/main/assets/bridge/agent_native.py`
- `core/app/src/main/assets/bridge/agent_runner.py`
- `core/app/src/main/java/dev/aster/ide/agent/AgentNativeQuestions.kt`
- `core/app/src/main/java/dev/aster/ide/agent/AgentQuestionDialog.kt`
- `core/app/src/main/java/dev/aster/ide/agent/AgentController.kt`
- `core/app/src/main/java/dev/aster/ide/agent/AgentSubagents.kt`
- `research/gemini-agent/runner.ts`
- `research/gemini-agent/build.mjs`

APK 构建复用现有 arm64 Codex 二进制，新 Rust 源码改动尚未编入。产物、摘要和构建状态见 `logs/interactive-tools-apk.json`。