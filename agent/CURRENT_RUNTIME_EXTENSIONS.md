# 本轮运行与扩展改动

当前源码尚未导出新的签名 APK；fix2 不包含以下改动。

Gemini 模型设置增加 Gemini API / OpenAI API 选择，分别保存到引擎配置。OpenAI 使用 Chat Completions，保留 Gemini CLI 的会话、压缩、工具调度；目前仅文本及工具消息，整段返回，token 计数为估算。不支持固定思考预算，切换协议会将预算模式恢复自动；思考强度作为 reasoning_effort 发送，服务端仍可拒绝。

自动模型审阅默认关闭，可分别指定审阅模型（同一地址和密钥）。只在成功的执行任务产生修改后启动独立只读会话。命令批准和应用修改流程保留。审阅失败保留已完成任务与修改，并明确报告未完成。

项目 Skill 在 `.aster/skills/<name>/SKILL.md` 或 `.agents/skills/<name>/SKILL.md` 放置；模型设置以逗号分隔选择名称。仅显式启用项进入上下文；会报告发现与启用名称。最多12项、每项16KB、总计32K字符，不执行安装脚本。并非完整的 Skill 安装市场或资源管理器。

MCP 当前支持协议2025-03-26的 Streamable HTTP。项目 `.aster/mcp.json` 示例：

```json
{"mcpServers":{"example":{"type":"http","url":"https://example.com/mcp","headerEnv":{"Authorization":"MCP_AUTH_HEADER"}}}}
```

请求头值从共享运行环境变量读取，不在项目文件填写密钥。模型设置以逗号分隔启用服务器名称。模型通过共享 `mcp` 工具传 server、可选 tool 和 arguments。只在执行模式可用，连接/调用要求批准；自动审阅不提供此工具。外部服务器具有自身权限，操作不属于本地暂存修改。暂不支持stdio、OAuth登录、分页工具发现、持续SSE订阅和完整服务器管理页面。

工具面板显示原始工具名、JSON参数及结果，已加入结构化递归脱敏与显示长度限制。仍待补充跨事件callId、耗时、状态卡片。

输入框上角22px、对话框20px、工具与消息卡片16px。四引擎输入区按钮顺序不同；Gemini发送按钮位于左侧。手机360x644、横屏844x390、桌面1280x800均通过四引擎fixture，currentAssets=true且无人工干扰。

Pi分别探测TLS1.2/1.3（无密钥HEAD），都可达优先1.3，缓存5分钟。保持CA与主机名校验；HEAD成功只证明HTTP/TLS路径可达，不能证明模型POST被供应商接受。

OpenClaude 404改为保留API诊断，研究SDK已实际重建并验证。真实远端404原因尚未验证。研究SDK与研究APK仅供本地研究，禁止上传传播发布，保留原许可证及用途告知。

Codex Android文件锁改用libc flock并保持锁语义，宿主锁测试通过。原生重编译仍需最终成功收据与设备验证，不能把宿主测试视为Android启动成功。
