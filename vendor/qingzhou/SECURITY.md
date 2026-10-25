# 安全政策

轻舟浏览器（Qingzhou Browser）是一个基于 Android WebView 的浏览器应用。用户的安全和隐私是我们最优先的考量。本文件说明了如何报告安全漏洞、我们的处理流程以及相关安全政策。

## 报告安全漏洞

如果你发现了安全漏洞，请不要在公开的 Issue 或 Discussions 中披露。请通过以下方式私下报告：

- 发送加密邮件至 [jssntsbczmfc2622@foxmail.com](mailto:jssntsbczmfc2622@foxmail.com)
- 邮件标题：`[安全漏洞] 简短描述`
- 邮件内容（加密前）：请包含以下信息
  - 漏洞的详细描述
  - 复现步骤
  - 受影响的版本号
  - 可能的攻击场景
  - 你建议的修复方案（如有）
  - 你的联系方式（以便我们跟进）

## PGP 公钥

所有漏洞报告必须使用 PGP 加密，以确保通信安全。

- 指纹：60267A39BF3551CE3CEB267041ABAF675659819E
- 公钥文件：[public-key.asc](public-key.asc)

指纹是公钥的唯一身份标识。导入公钥后，务必比对指纹与上述完全一致，以确保公钥未被篡改或冒充。

### 如何使用 PGP 加密报告

#### 第一步：安装 GnuPG

- Windows：下载并安装 [Gpg4win](https://www.gpg4win.org/)
- macOS：`brew install gnupg`
- Linux：使用包管理器安装，如 `sudo apt install gnupg`

#### 第二步：导入我们的公钥

下载仓库中的 [public-key.asc](public-key.asc) 文件，然后在终端执行：

`gpg --import public-key.asc`

#### 第三步：验证公钥指纹

导入后，务必验证指纹与上面公布的一致，防止公钥被篡改：

`gpg --fingerprint jssntsbczmfc2622@foxmail.com`

将输出的 40 位指纹与上方公布的指纹逐位比对。如果不一致，说明公钥文件可能已被替换，请立即停止发送并联系我们确认。

#### 第四步：编写漏洞报告

将报告内容写成一个纯文本文件，例如 `report.txt`。确保包含上述所需信息。

#### 第五步：加密报告

在终端执行以下命令，生成加密后的文件 `report.txt.asc`：

`gpg --encrypt --armor --recipient jssntsbczmfc2622@foxmail.com report.txt`

- `--encrypt`：加密
- `--armor`：输出 ASCII 文本，便于粘贴到邮件正文
- `--recipient`：指定收件人的邮箱

#### 第六步：发送邮件

打开邮件客户端，新建邮件：

- 收件人：`jssntsbczmfc2622@foxmail.com`
- 标题：`[安全漏洞] 简短描述`
- 正文：用文本编辑器打开 `report.txt.asc`，将文件中的全部内容（从 `-----BEGIN PGP MESSAGE-----` 到 `-----END PGP MESSAGE-----`）复制粘贴到邮件正文中，然后发送。

请勿将加密后的报告以附件形式发送，直接粘贴到邮件正文即可。

## 响应流程

1. 确认收到（48 小时内）：我们会确认收到你的报告，并提供预计的处理时间。
2. 初步评估（7 天内）：我们会评估漏洞的严重程度，确定优先级。
3. 开发修复：修复开发过程中，我们会与你保持沟通，必要时请协助验证。
4. 发布更新：修复完成后，我们会发布新版本，并在 Release Notes 中公开致谢，除非你要求匿名。
