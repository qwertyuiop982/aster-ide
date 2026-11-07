# 模型设置、原生调用与上下文压缩验证

本次修改工作区：`/root/work/aster-ide-account`。

## 修改

- 模型配置索引采用圆角卡片。分组顺序：连接模型、上下文与输出、思考、协作与审阅、账号与工具、任务运行、界面配置。清除 Key 入口归入连接模型。
- 移除 Agent 与 IDE 设置页的步骤上限输入，以及 `agentMaxSteps` 的存储和读取。旧配置字段被忽略。
- 移除 Gemini 原生工具前置检查、Codex 原生事件计数、Pi 模型轮次中止中的普通模式次数限制。Gemini 的 `maxSessionTurns` 使用上游无上限值 `-1`；OpenClaude 非免费模式不传 `maxTurns`，本地工具计数使用 Infinity。子代理不再声明或下发 `max_steps`。
- 免费任务仍按主代理与子代理合计 64 次实际工具调用计费预算检查。取消、权限检查及已有任务时限仍生效。
- Pi 使用上游 `estimateProjectedContextTokens` 计算压缩后的上下文，避免保留消息中的旧 usage 导致用量不下降和重复触发。
- Pi 将任务输出预留、摘要预算和保留近期消息预算分开计算，摘要预算按可用输入空间计算。
- 摘要生成的 `maxTokens` 经 Pi RPC、Python 调度器传到模型 API；摘要不受普通回复长度偏好限制。摘要内容不再作为普通助手消息显示。
- 已重建 Pi/Gemini 运行包，并刷新 Pi 源码来源记录。

## 验证结果

- 现有 49 项回归测试通过：Codex 原生路由、Pi 连接恢复、真实打包 Pi 工具循环、免费额度、账号协议、子代理运行。
- 新增 `scripts/test_pi_context.py` 的 3 项回归测试通过：132 次非免费工具调用、旧 maxSteps=2 不生效；压缩后的旧 usage 失效及会话恢复；摘要预算到达 API 且不混入回复。
- Gemini 原生工具前置检查在旧 maxSteps=2、已有 1000 次调用的条件下接受第 1001 次调用。
- 免费家族计数第 64 次被接受，第 65 次被拒绝。
- Python 修改文件解析成功；Pi/Gemini/OpenClaude JavaScript 语法检查通过。
- `:agent:app:compileDebugKotlin :ide:app:compileDebugKotlin` 编译通过，包括依赖的 core 模块。日志：`logs/native-unlimited-kotlin.log`。Agent Web UI 未修改，编译时跳过其 bundle 任务。
- 未进行 Android 真机界面验收、完整 APK 打包或发布。

## 用户提供接口的实际测试

接口基础地址：`https://api.uselunora.com/v1`；模型：`gpt-6.1-sol`。密钥不写入本记录或源码；临时凭据文件已删除。

- 带鉴权 `GET /models`：HTTP 200，列表包含目标模型。
- `POST /chat/completions`，最小文本请求：HTTP 200，返回 `OK`，约 3.3 秒。
- 使用重建后的 Pi 包和项目实际 Python 模型适配层：流式响应、一次 read_file 工具调用、工具结果回传、最终测试标记均成功。思考强度使用 low。
- 使用重建后的 Pi 包和实际模型做手动压缩：普通回复上限 64 tokens、窗口 8192，摘要请求预算分别为 1228/768 tokens（历史摘要与分段摘要），成功保存 1 个检查点、摘要共 1669 字符，保留指定路径；内部摘要作为普通回复显示的消息数为 0。
- 服务端拒绝 `reasoning_effort=none`，HTTP 400，明确列出 low/medium/high/xhigh/max。自动模式省略该参数可用于摘要；普通工具测试使用 low 成功。
- 上述在线结果针对 OpenAI Chat Completions 和 Pi，未验证该服务的 Codex Responses 协议。

改动前源码备份：`.backup/native-unlimited-context/`。工作区无 `.git`；无法生成 Git 状态或提交。