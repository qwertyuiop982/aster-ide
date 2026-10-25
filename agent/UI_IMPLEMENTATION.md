# Aster 独立 Agent 界面

## 模块边界

- `core/launcher`：原生首次工作模式选择、IDE/Agent 路由；只创建当前模式的 ViewModel。
- `core/app`：工作区、设置、Agent 控制器、会话、工具审批、Python 桥接和 Termux 运行时。
- `ide/app`：原生 ACS / VS Code、Sora 编辑器、语言服务和原辅助 Agent。
- `agent/app`：独立 Agent 原生宿主与本地 WebView 界面。

Activity 重建时由 `ModeViewModels` 保留当前模式的 ViewModelStore；显式切换模式时，先保存，再释放旧模式的编辑器和终端连接。共享运行时不随页面销毁。

## 四种呈现

| 引擎 | 横屏 | 竖屏 | 配色 |
|---|---|---|---|
| Codex | 中央任务起点、底部输入台；信息面板按需打开 | 单列会话，修改单独审阅 | 白 / 青 |
| Gemini | Codex 构图家族，强调已选择上下文文件 | 单列会话、上下文摘要入口 | 白 / 蓝 |
| OpenClaude | 左导航、中会话、右工作信息 | 历史抽屉及工作信息面板 | 白 / 橙 |
| Pi | 历史 + 标准聊天 | 标准移动聊天，不是终端界面 | 纯黑 / 灰白 |

常驻侧栏仅在横屏宽度至少 1100 CSS px 且高度至少 580 CSS px 时使用；窄屏、竖屏和短横屏使用单主区域。键盘压缩可用高度时输入区采用紧凑布局。

`assets/agent/engines/` 保留四个独立布局配置；`components.js` 共享功能组件；`base.css` 管理尺寸、响应式和各引擎视觉；`dom.js` 仅以 DOM/textContent 渲染模型文本与代码，绝不执行模型返回的 HTML。

## 实际接入的功能

- 引擎选择及真实可用性结果；缺失资源不会回退为 Pi。
- 按工作区 / 引擎 / 会话分开的草稿，切换时持久化。
- 任务发送、流式消息、工具事件、停止、任务清单。
- 历史搜索、恢复、新建、原生删除确认。
- 提示词编辑、上下文用量、手动压缩入口。
- 原生工作区选择、创建、Android 目录导入。
- 原生模型配置和加密 Key；WebView 不持有 API Key。
- 原生上下文文件选择，最多 8 个 UTF-8 文本文件；单文件 64 KiB，全部显式上下文遵守宿主 12,000 字符预算，截断有标记。
- 原生命令审批、修改前后阅读、确认应用及丢弃确认。
- 原生后退键先关闭网页面板。

输入清空依据本地 Agent 桥接的启动确认，不等同于远程模型已处理成功；后续失败通过真实任务状态显示。

## 边界和未完成项

- 生成设计图仅作为视觉参考，不是实机功能证明。
- Codex 原生 app-server 与 OpenClaude SDK 的端到端循环、恢复、压缩、Android 行为仍需要独立验证。
- OpenClaude 的 SDK 尚不默认随 APK 分发；手动压缩及未验证的输出预算选项不假装可用。
- 右侧“上下文”显示用户明确选择的文件，不声称 Agent 已经阅读了整个项目。
- 修改面板目前提供文件列表和修改前/后切换，不是逐行合并冲突编辑器。
- 修改应用保留逐文件冲突检查；不是跨多个文件的单一文件系统事务。
- 旧 `scripts/package_delivery.py` 是历史 release 流程；当前预览不要通过它发布正式版本。

## 启动修复与透明性（agent-startup-fix1）

进入 Agent 后立即准备工作区 → 内置 Termux/Python → 检查 Node 22.19+ → 缺失/过旧时安装 Node LTS → 检查引擎资源；不要求先配置 API。已有环境复用，不引入第二套 Linux。

原生启动面板不依赖 JavaScript。它分别显示环境阶段与网页 ready/首帧确认，记录停止步骤，并提供取消、环境重试、网页重载、模型设置、模式切换及本地脱敏诊断。脚本资源失败、启动超时、WebView 渲染进程退出都有可见错误入口。

页面使用本地 IIFE bundle，编译目标 Chrome 61；生产 CSP 不允许 eval、外网、iframe 或模型 HTML。Gradle preBuild 会重建 UI bundle。转译及浏览器 fallback 测试不等于旧版 Android WebView 已验收。

### 自定义上下文

- 四个引擎分别保存自定义窗口；Pi/Gemini/Codex 范围 8192–1048576 tokens，OpenClaude 按上游公开设置最低 33000，默认 65536。
- Pi 使用真实模型元信息及原生压缩；Gemini 原生可用窗口取用户预算与模型上限的较小值；Codex 写入 `model_context_window`，保留其原生安全余量和报告；OpenClaude 仅在本地研究 SDK 导出公开会话 override，不启用内部账号标志。
- 最大输出与上下文是两个概念。Codex 未验证的输出参数不提供假开关；其余引擎输出设置保留至少 1024 输入 tokens，仍受模型自身上限约束。
- 界面区分已保存窗口、最近一次引擎有效窗口、估算/混合/引擎报告来源和更新时间。未知值显示“尚无有效用量报告”，不伪装成精确零用量或实时账单。
- 显式文件注入 12000 字符上限、界面历史缓存上限与引擎 token 窗口不同，运行详情中明确说明。
- “运行详情与诊断”只在本机显示；复制仅到剪贴板。已知密钥与常见认证字段脱敏，日志可能仍包含项目路径/任务文本，分享前应检查。

### Codex 外观

只修改 Codex：全圆角输入台、任务卡、胶囊控件和弹层；增加页面/卡片/消息/面板入场、工具展开和按压反馈。流式消息复用已有节点，避免每个 token 重播消息入场动画；尊重 `prefers-reduced-motion`。不做二进制体积优化。

## 调试预览

```sh
python3 -B scripts/build_agent_context_hosts.py
python3 -B scripts/build_ui_preview.py --export --name aster-agent-startup-fix1-debug.apk
```

此命令构建 APK、运行 core/ide/agent 模块 JVM 测试、确认资源与本轮输入一致、检查 APK 签名。只有全部通过才写入：

`/sdcard/Download/Operit/Aster/preview/aster-agent-startup-fix1-debug.apk`

`--export` / 兼容的 `--publish` 仅复制到本机 Download 目录，不做服务器上传。导出会阻止 restricted 路径、SDK 文件和已知 OpenClaude SDK 内容进入 APK。OpenClaude 的完整 SDK 构建与验证记录只留在本地研究目录。

构建记录：`logs/ui-preview-build.json`。它明确区分 JVM / 主机回归和设备验收。不能把 debug 预览当作已完成四引擎验证的正式 release。

图标：Lucide 0.468.0 本地子集；来源记录在 `agent/design/LUCIDE-SOURCE.json`，许可证在 `assets/agent/LUCIDE-LICENSE.txt`。
