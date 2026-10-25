# Agent fix3 release 本地交付

用户要求增加 openssl-tool，并交付普通与 OpenClaude 本地研究两种 release APK。

自动环境脚本、桥接 Agent 安装和 IDE 基础安装均显式安装 openssl、openssl-tool、ca-certificates。
实际 openssl version 检查保留；新增库存在但工具不可用、安装后工具仍失败和两入口包列表测试。

两种构建关闭调试、启用 R8 和资源裁剪，研究版继承原有版权告知、用途标记和 SDK 隔离。
签名脚本已迁移至 core/build/application 输出目录，核验同一证书与 R8 方法保留。

结果：113 项 release JVM、112 项本轮宿主及安装回归、9 项 JS、4 项包内 SDK 检查通过。
同哈希网页资产复用 fix2 浏览器结果，保留一次菜单偶发失败；未宣称设备问题已解决。

两份本地安装文件：

- /sdcard/Download/Operit/Aster/release/aster-agent-fix3-release.apk
  SHA256: 89f818c399f945086b0977fc50add400253aee4e11764246f1d37d8e537e4c68

- /sdcard/Download/Operit/Aster/local-research-DO-NOT-DISTRIBUTE/aster-openclaude-fix3-release-LOCAL-ONLY-禁止传播发布.apk
  SHA256: 3bd67be32ab7cfded6a567a1c16e8b55f102fac9acd8c0c0a3471f003bfb91cc

详细证据见 logs/release-apks-build.json 与 logs/release-apks-handoff.json；Android 复测待完成，未安装或上传。
