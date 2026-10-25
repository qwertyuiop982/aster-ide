# Aster

把开发现场，带在身边。Android 上的代码编辑器、终端与多引擎 Agent 工作台。

[官网](https://asteride.cn) · [下载与对应源码](https://github.com/qwertyuiop982/aster-ide/releases/tag/v1.0.1-free64) · [使用指南](https://asteride.cn/guide/) · [源码与许可](https://asteride.cn/licenses/)

Aster 包含完整 IDE、内置 Termux 环境与 Pi / Codex / Gemini 引擎。可以从首页选择 IDE 或独立 Agent 模式，按项目保存会话，查看工具调用与修改预览。

## 版本

| 版本 | 应用包 | 模型来源 | 发布状态 |
| --- | --- | --- | --- |
| 账户版 free64 | `dev.aster.account` | Pi 账户模型或自定义 API；Codex / Gemini 自定义 API | 测试构建，预览版 |
| 原版 1.0.1 | `dev.aster.ide` | 自定义 API | 原版 Release 构建 |

两个版本使用独立应用包，可以并存，数据不会自动迁移。每个 APK 均提供相应客户端源码与 SHA-256。账户版沿用客户端及组件许可，并非闭源分发。

账户版使用 Pi 时可以选择 Aster-free（1 积分 / 1000 输入与输出 token）或 Aster-free go（50 积分 / 1000 token）。Go 固定最低推理强度，只显示最终内容，输出计费包含推理 token。真实模型、上游 URL 和密钥只存放于服务端。

free64 为每轮 Pi 账户任务固定设置最多 64 次工具调用，完成时提前结束；批量调用共享同一上限。工具结果回传后继续原任务，遇到普通文字形式的工具指令会进行有次数限制的格式恢复，避免误判为完成。正式恢复请求的实际 token 计入用量。

## 构建与验证

面向 Android 7.0+、arm64-v8a。开发环境、原生引擎构建和依赖准备见 [DEVELOPMENT.md](DEVELOPMENT.md)，账户版构建范围见 [ACCOUNT-BUILD.md](ACCOUNT-BUILD.md)。使用自己的 Android 签名密钥。

```sh
./gradlew :app:assembleDebug
python3 -B -m unittest scripts.test_free_tool_loop scripts.test_account_free scripts.test_agent_runtime_install -v
```

已通过 APK 构建、签名与资源校验、包内凭据检查、16 项离线测试，以及公开 HTTPS 网关与实际 Pi 循环的三文件连续读取测试。64 次与批量边界使用离线测试验证。新版 APK 的 Android 安装和 IDE 实机操作尚待验收；全新机器从源码归档开始的完整构建尚未验证。具体范围见 Release 中的 validation JSON。

## 官网

新版官网源码位于 [website/v3](website/v3)，无 npm 构建依赖。使用公开发布元数据在本地生成静态文件：

```sh
python3 website/v3/build.py --release-input release-input.json --output /tmp/aster-site
```

源码包含响应式首页、IDE / Agent 预览、引擎切换、积分估算、双版本下载、使用指南、许可及数据说明。远端仅接收生成的静态站点。

## 许可

保留根 [LICENSE](LICENSE)、[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 以及所有组件原版权和许可。客户端包含 GPL / AGPL 等组件；再分发需履行对应源码义务。Release 分别提供两个 APK 各自的源码。独立 Shizuku 应用不随 APK 分发。
