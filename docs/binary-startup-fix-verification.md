# 二进制文件导致工作台启动失败：修复与验证

## 原因

设备截图中的实际错误为「这是二进制文件」，显示在「环境准备未完成」页面。该文案来自 `ide/app/src/main/java/dev/aster/ide/data/EditorDocument.kt` 的文本编辑器读取器：文件前 4096 字节含 NUL 时拒绝作为文本打开。

`IdeViewModel.prepareEditor()` 原先会自动打开工作区中第一个文件（优先 `src/main.py`），且没有隔离这一便利操作的失败。工作区文件列表为递归目录排序，新增的自编译 ELF 如果排到前面，会让文本读取异常向上传播为 `startupError`，从而阻断进入工作台。切换工作区的自动打开逻辑有相同问题。

截图对应的是编辑器初始化失败；此前检查的 PRoot、CPU 架构、ELF 解释器或执行位不足，未证实与此错误有关，本次没有修改它们。

## 修复

- 新增 `InitialDocument.open()`，保留 `src/main.py` 优先顺序，依次尝试非目录文件。
- 文件无法作为文本打开时记录路径和错误并继续；没有适合的文本文件时正常结束，允许工作台以无自动标签页状态启动。
- 首次初始化和切换工作区共用该处理。
- 取消异常继续向上传播，并在扫描文件之间检查协程取消。
- 手动打开文件仍保留二进制、UTF-8 和文件大小检查；不改动或删除用户的二进制与源码。

源码修改前备份：`.backup/binary-startup/ide/app/src/main/java/dev/aster/ide/IdeViewModel.kt`。

## 验证

执行 `python3 scripts/test_binary_startup.py`：12 项验证通过。测试直接编译生产 `InitialDocument.kt`、`EditorDocument.kt` 与 `ContentLoader.kt`，使用缓存中的真实 Compose/Sora 库和 Android SDK 接口；工作区对象和设置使用本地文件夹替身，未在 Android 设备中运行 UI。

覆盖：复现截图原文、二进制排在文本之前、全二进制目录、空目录/只有目录、优先源码、优先源码本身是二进制、不支持的 UTF-8、文件被删除、超大文件、取消异常、预先取消任务、原文件不变。

`/root/gradle/gradle-8.13/bin/gradle :ide:app:compileDebugKotlin :agent:app:compileDebugKotlin -x :agent:app:bundleAgentUi --no-configuration-cache --console=plain -Pandroid.aapt2FromMavenOverride=/root/Android/build-tools/35.0.0/aapt2` 编译成功，耗时 52 秒；85 个任务中 1 个执行、84 个复用。

日志：`logs/binary-startup-regression.log`、`logs/binary-startup-kotlin.log`。
