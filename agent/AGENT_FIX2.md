# Agent fix2 本地交付

用户实测 fix1 的原生启动面板有效，IDE 正常，Agent 网页未正常显示且 Node 未自动安装。安装包身份已经核对。

本轮修复了 Node 安装被 Python 桥接/RPC 前置条件阻断的流程缺陷，并让网页显示与环境准备独立。尚无设备日志证明这就是用户设备故障的唯一根因。

启动顺序为工作区与原生会话、内置 Termux 和直接工具检查、按需安装、Python 桥接、引擎资源检查。`agent-runtime.sh` 安装 Node.js LTS、Git、ripgrep、OpenSSL、CA，检查 Node >=22.19.0 及程序实际可执行性。证书仅检查文件非空，不代表网络 TLS 已验证。安装有超时、修复重试和取消检查；宿主只使用原有共享 Termux。

网页从原生认证邮箱拉取最新状态，检查 revision 与 token；首次确认要求有效会话、可见对话区、输入区及原生非零尺寸。保留严格 CSP 和本地资源白名单。环境未就绪时显示原生顶部状态，网页失败仍显示完整恢复面板。原生返回键关闭网页面板的路径仍使用原有求值调用，需随实机回归检查。

普通版为 `0.1.7-agent-fix2`，研究版为 `0.1.7-openclaude-local-only-fix2`。研究 SDK 将 Anthropic/MCP/Orama 必需 JS 依赖内联，并避免对 node_modules 内部相对路径套用源码 TUI 裁剪规则。研究版保留来源和版权告知，仅此变体含 SDK。两种 APK 同包覆盖安装，未上传。

113 项 JVM、9 项 JS、7 项隔离 shell 测试通过；95 项既有宿主测试按相同 bundle 哈希复用。APK 内 SDK 的 3 项导入/上下文测试与 1 项本机流式模拟查询、真实 Read 工具循环通过。浏览器最终 8 个场景、24 项布局检查通过，保留一次 Gemini 小窗口菜单偶发失败，随后两次通过但原因未确认。

证据：`logs/local-openclaude-apk-build.json`、`logs/agent-fix2-browser-summary.json`、`logs/agent-fix2-handoff.json`。Android 首启、Node 下载与安装、WebView 首屏、原生返回键、真实服务、Android 工具、恢复和压缩尚待实机验收，不能宣称用户白屏已解决。
