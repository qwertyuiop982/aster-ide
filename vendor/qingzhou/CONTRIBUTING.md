# 贡献指南：从入门到精通

感谢你对轻舟浏览器的关注！这份指南将帮助你从克隆仓库到提交 Pull Request，逐步完成你的第一次贡献。

## 目录

- [行为准则](#行为准则)
- [新手入门](#新手入门)
- [开发环境搭建](#开发环境搭建)
- [寻找任务](#寻找任务)
- [开发流程](#开发流程)
- [代码风格](#代码风格)
- [提交规范](#提交规范)
- [测试](#测试)
- [Pull Request 指南](#pull-request-指南)
- [沟通渠道](#沟通渠道)

---

## 行为准则

本项目采用 [贡献者公约](CODE_OF_CONDUCT.md)。参与即表示你同意遵守该准则。简而言之：保持友善，尊重他人，不接受任何形式的骚扰。

---

## 新手入门

如果你从未参与过开源项目，以下步骤将帮助你迈出第一步：

1. **注册 GitHub 账号**：如果你还没有 GitHub 账号，请先注册。
2. **Fork 本仓库**：点击页面右上角的 Fork 按钮，创建你自己的副本。
3. **了解 Git 基础**：如果你不熟悉 Git，建议先花 30 分钟学习。
4. **寻找简单任务**：在 [Issues](https://github.com/myos-Lu/qingzhou/issues) 中查找带有 `good first issue` 标签的任务，这些是专门为新贡献者准备的。
5. **从文档开始**：如果你还不熟悉代码，可以从改进文档、修复拼写错误开始，这也是非常有价值的贡献。

---

## 开发环境搭建

### 方式一：使用 AIDE（推荐，与原作者保持一致）

1. 将仓库克隆到电脑。
2. 将整个项目文件夹复制到手机存储的 `Android/data/com.chou.android.ide/files/Projects/` 目录下。
3. 在 AIDE 中打开项目，点击"运行"编译安装。

### 方式二：使用 Android Studio

1. 克隆仓库到电脑。
2. 用 Android Studio 打开项目文件夹。
3. 启动 Android 模拟器，运行。

> **注意**：项目最初使用 AIDE 创建，项目结构可能与标准 Android Studio 项目略有差异。如遇到编译问题，请参考 `build.gradle` 文件进行调整。

---

## 寻找任务

- 查看 [Issues](https://github.com/myos-Lu/qingzhou/issues) 列表。
- `good first issue` 标签：适合新手。
- `bug` 标签：Bug 修复。
- `enhancement` 标签：新功能或改进。
- `help wanted` 标签：急需帮助的任务。
- 如果没有找到合适的任务，欢迎在 [Discussions](https://github.com/myos-Lu/qingzhou/discussions) 中提出你的想法。

---

## 开发流程

### 1. 同步上游仓库

在开始开发前，确保你的 Fork 是最新的：

执行 `git remote add upstream https://github.com/myos-Lu/qingzhou.git` 添加上游仓库。

然后执行 `git fetch upstream` 获取最新代码。

接着执行 `git checkout main` 切换到主分支。

最后执行 `git merge upstream/main` 合并上游的更新。

### 2. 创建功能分支

执行 `git checkout -b feature/your-feature-name` 创建并切换到新分支。

分支命名建议：
- `feature/xxx`：新功能
- `fix/xxx`：Bug 修复
- `docs/xxx`：文档改进
- `refactor/xxx`：代码重构

### 3. 编写代码

- 保持每次提交的改动范围小而聚焦。
- 一个提交只做一件事。
- 写下清晰的提交说明。

### 4. 提交更改

执行 `git add .` 暂存所有更改。

然后执行 `git commit -m "类型：简短描述"` 提交。

提交信息格式：`类型：描述`，例如：
- `fix：修复 Cookie 删除后域名不消失的问题`
- `feat：添加广告拦截规则自动更新功能`
- `docs：更新 README 中的安装说明`

### 5. 推送到你的 Fork

执行 `git push origin feature/your-feature-name`

### 6. 创建 Pull Request

在 GitHub 上创建 PR，填写以下信息：
- **标题**：简短描述你的更改
- **描述**：详细说明你做了什么、为什么这样做、如何测试
- **关联 Issue**：如果有相关 Issue，请使用 `Closes #123` 语法关联

---

## 代码风格

### Kotlin 规范

- 遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)。
- 使用驼峰命名法（camelCase）。
- 类名首字母大写，方法名和变量名首字母小写。
- 保持代码简洁，避免不必要的注释，好的代码自己会说话。
- 优先使用 `val` 而非 `var`。
- 使用 `when` 替代冗长的 `if-else` 链。

---

## 提交规范

- **一个提交只做一件事**：不要把多个不相关的修改放在一个提交里。
- **提交信息要清晰**：别人看提交记录就能知道改了什么。
- **提交前自测**：确保在 AIDE 中能正常编译和运行。
- **不提交无关文件**：不要提交 `.idea/`、`*.iml`、`local.properties` 等本地配置文件。

---

## 测试

在提交 PR 前，请手动确认以下内容：

- [ ] 应用能正常启动，不闪退。
- [ ] 你的改动在深色模式和浅色模式下都正常显示。
- [ ] 你的改动不影响已有功能。

---

## Pull Request 指南

### PR 标题格式

`类型：简短描述`

例如：
- `feat：添加用户脚本导入功能`
- `fix：修复深色模式下地址栏不可见的问题`

### PR 描述模板

在 PR 描述中请包含以下内容：

**做了什么**：简要描述你的更改。

**为什么这样做**：说明更改的原因或背景。

**如何测试**：
1. 步骤一
2. 步骤二
3. 步骤三

**截图**：粘贴截图或录屏。

**关联 Issue**：使用 `Closes #123` 语法关联。

### PR 审核

- 所有 PR 至少需要一次审核才能合并。
- 审核者会检查代码风格、功能完整性、潜在 Bug。
- 如果有修改意见，请在原分支上继续提交，PR 会自动更新。

---

## 沟通渠道

- **Bug 报告和功能请求**：[Issues](https://github.com/myos-Lu/qingzhou/issues)
- **讨论和交流**：[Discussions](https://github.com/myos-Lu/qingzhou/discussions)
- **安全漏洞报告**：如果你发现了安全漏洞，请通过 [安全政策](SECURITY.md) 中的方式报告，勿在公开 Issue 中披露。
- **邮件联系作者**：jssntsbczmfc2622@foxmail.com

---

## 致贡献者

感谢你抽出时间阅读这份指南。开源项目因为有你的参与而变得更好。无论你的贡献是大是小——修复一个拼写错误、改进一行代码、或者添加一个全新的功能——我们都心怀感激。
