# Aster 1.0.4-agent-tools

本版合并交互式终端、远端 Terminal MCP、客户端子代理和图片读取，并保留前一版的连接修复与原生环境安装设置。应用 ID 为 `dev.aster.account`，versionCode 为 15。

## 客户端子代理

模型候选配置、密钥读取、参数校验、独立子会话、协程调度、取消和界面事件由 Android 客户端负责。Python 桥接层复用已有模型引擎与项目工具，检查父任务归属，继承权限，管理共享工作副本、文件锁和免费工具预算。

在 Agent 模型设置或 IDE 的 Agent 设置中填写“子代理候选模型”，使用逗号或换行分隔；候选模型沿用该引擎已配置的供应商地址和 API Key。当前主模型可以通过 `current` 选择，其他候选 ID 由 `subagent` 的 `models` 操作返回。每个引擎最多提供 8 个候选。未配置可用 Key 或未就绪的引擎不会进入额外候选池。

内置工具 `subagent` 在工具列表和系统提示中优先推荐。主模型负责选择模型、思考强度或预算、system 提示词和 Prompt。每批最多 4 个任务，同时最多运行 2 个；每个 Prompt 和 system 提示词最多 12000 字符，单任务 timeout 为 15–900 秒，max_steps 为 1–64。所有任务先校验，再开始执行。

```json
{"action":"models"}
```

```json
{"action":"parallel","tasks":[{"model_id":"current","thinking_level":"high","system_prompt":"检查实现中可复现的错误，给出文件依据。","prompt":"检查终端取消和恢复行为。先读相关文件，完成后报告证据。","timeout":300,"max_steps":16},{"model_id":"current","thinking_level":"medium","system_prompt":"专注验证现有实现。","prompt":"检查图片读取的供应商请求格式和文件大小边界。","timeout":300,"max_steps":16}]}
```

子代理不继承主对话历史，需要主模型在 Prompt 中提供必要上下文。它们共享主任务的工作副本、环境、PTY 和 MCP 对话范围，并保持相同的模式权限；修改仍作为同一份待审阅改动保存。子代理的工具定义剔除 `subagent`，运行时也拒绝递归调用。命令及外部工具审批统一路由到主任务，停止主任务会取消子任务。免费模式下主代理和子代理共享 64 次工具调用预算。

## 交互式终端

`terminal` 使用真实 PTY，保留 cwd、变量、正在等待的输入和前几次命令输出。可打开多个独立终端，每个对话最多 4 个、运行时总计最多 8 个。原有 `run_command` 的 timeout 按秒接受 1–3600，不再截断为 120 秒。

```json
{"action":"open","name":"build"}
```

后续调用保留返回的 `terminal_id`：

```json
{"action":"exec","terminal_id":"返回的终端ID","command":"./gradlew assembleDebug","wait_ms":1000}
```

```json
{"action":"write","terminal_id":"返回的终端ID","text":"yes","key":"enter"}
```

```json
{"action":"write","terminal_id":"返回的终端ID","key":"x","modifiers":["ctrl","alt"]}
```

`wait_ms` 只控制单次等待输出的时间，不会结束命令。使用 `read` 继续观察，使用 `list` 找回同一对话的终端；不要因单次等待结束而重复执行命令。支持 `resize`、`interrupt` 和 `close`。每次返回包含近期控制台历史；历史按容量保留。运行时停止后进程不会自动恢复，已有历史仍可读取，输入不会自动重放。

用户指定的 `/linkerconfig/ld.config.txt` 配置缺失提示已从 Kotlin 启动输出和 Python 桥接输出中精确移除。真实 linker 加载错误仍会显示。

## 远端 Terminal MCP

公网地址为 **https://asteride.cn/terminal-mcp**，提供 Streamable HTTP 和 Bearer 鉴权。服务使用 `aster-terminal` 非 root 账户，监听 `127.0.0.1:8893`，由 nginx 转发。部署目录为 `/opt/aster-terminal-mcp/current`，状态目录为 `/var/lib/aster-terminal-mcp`。

提供 7 个工具：`terminal_open`、`terminal_exec`、`terminal_write`、`terminal_read`、`terminal_list`、`terminal_resize`、`terminal_close`。中断命令可使用 `terminal_write` 发送 Ctrl+C。远端执行的命令运行在服务器环境。

客户端配置保存为 `/sdcard/Download/Aster-terminal-MCP-config.json`，其中包含访问凭据，未放进源码包和验证报告。将该 JSON 作为项目 `.aster/mcp.json` 的配置，并在模型设置启用 `terminal` 后，可通过内置 `mcp` 工具调用。`conversationScoped: true` 使 Aster 自动注入稳定的对话标识，子代理沿用主对话范围。

远端部署使用与 APK 相同的终端模块，并核对文件 SHA-256。版本更新会重启终端服务；旧终端历史保留，旧进程不重放。

## 图片读取

`read_file` 自动按签名和扩展名识别 PNG、JPEG、WEBP 和 GIF。可用 `intent` 指定需要检查的图像内容；不填时会分析场景、对象位置和可读文字。原有 UTF-8 文本读取与行号参数保持可用，`read_files` 也支持混合读取。

```json
{"path":"screenshots/home.png","intent":"检查顶部按钮的位置，并读出可见文字。"}
```

图片字节由当前引擎配置的供应商图片接口处理：Pi 和 OpenAI 兼容配置使用 Chat Completions，Codex 使用 Responses，OpenClaude 使用 Anthropic Messages，Gemini 使用 generateContent。返回包含 `source: model_vision`、模型、图片尺寸和视觉分析文本，不把本地 OCR 当作完整视觉结果。

限制为每张 5 MiB、每边最多 20000 像素且总计不超过 40 MP；检查文件签名及真实尺寸。免费接口暂不支持图片输入，会明确返回原因；需要选择已配置且支持图片的模型。

## 验证与交付边界

机器可读的最终验证结果、构建状态、APK 与源码文件哈希保存在同名验证报告中。验证覆盖 Python 回归、本地供应商 HTTP 格式、客户端调度器与真实 Python/Pi 工具循环，以及远端 MCP 公网调用和超过两分钟的命令。

客户端完整调用链使用生产 Kotlin 调度器、连接编解码、Python 运行时及已打包 Pi 引擎。Android 连接入口由本地适配器替代，模型由本地 HTTP 测试服务替代；这不代表真机界面或真实模型供应商已验证。

APK 使用本地 ARM 工具链构建并复用已有 Codex 原生资源。此前自动安装受到设备权限限制，真机安装、原生设置界面交互及新功能在真实供应商上的调用仍需设备侧确认。

最终归档验证：`:app:assembleDebug` 与 `:app:lintDebug` 均通过，lint 为 0 错误、13 个警告。Python 回归运行 70 项，69 项通过、1 项长耗时用例在本批跳过；该用例另已实测 125.176 秒通过。客户端完整调用链实际观察到 2 个并行子任务；最新远端长命令耗时 126.754 秒并正常完成。APK 的 46 个源资源逐字节一致，签名校验通过且与 1.0.2 修复版相同。
