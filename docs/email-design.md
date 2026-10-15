# SMTP 转发与 Material 3 界面

核对日期：2026-10-14。当前目标版本 0.2.0。用户已确认采用 Compose + Material 3、通用 SMTP 配置，兼容原 QQ 配置和已有队列。原有 SMTP → 邮箱路线保持，目标设备仍为三星 S21 / One UI 7。

## 发信配置与边界

`MailAccount.create` 接受发件邮箱、接收邮箱、SMTP 主机、端口、加密方式、登录用户名和密码 / 授权码。登录用户名独立于发件地址；不限制邮箱域名或凭据长度为 QQ 的 16 位规则。密码中的空格原样保存，禁止换行与 NUL。服务器支持域名、IPv4 / IPv6，拒绝 URL、路径及混入的端口；端口范围 1–65535。

`MailSecurity.TLS` 为连接即加密，`MailSecurity.STARTTLS` 为强制升级后再认证；缺少 STARTTLS 会失败，不降级为明文。使用系统 CA 和主机名校验。`SmtpMailSender` 复用 Eclipse Angus Mail 2.0.5，按已保存账号配置建立连接，不实现自研 SMTP。QQ / 163 预设只是可修改的填值工具；真正的运行参数始终来自用户保存的配置。

只需要 SMTP 发信；POP3 / IMAP 是收信协议，不在当前功能范围。支持服务商允许的 SMTP 用户名 + 密码 / 授权码 / 应用专用密码认证；OAuth 登录与令牌刷新未实现，不承诺所有邮箱服务都可用密码登录。

## 旧数据与队列身份

- `ConfigStore.account` 读取旧版加密 JSON（`sender/code/recipient`，没有 version）时调用 `MailAccount.legacyQq`，映射原 `smtp.qq.com:465` / TLS / 发件地址作为用户名，并保留旧 QQ 凭据去空白行为；读取不重写密文。显式保存才写 version 2 及完整 SMTP 配置。
- 未来未知的配置版本报错且保留原数据，不清空配置。应用包名、Keystore 别名、SharedPreferences 名称和 SQLite 数据库版本保持原值。
- 每条 `MailJob.smtpIdentity` 绑定 SMTP 主机、端口、加密方式及用户名，不复制密码。`SmsInbox.readPage` 和测试邮件入队时写入身份；`QueueDatabase` 将其存入原有加密 payload。
- 原 payload 缺少 `smtp` 时只按原 QQ 身份解释；不能当作任意服务器都可发的通配记录。原收件地址、短信 `_id`、扫描游标和状态不改。
- `QueueRunner.run`、手动重试及设置页的已知失败恢复都用 `MailJob.belongsTo` 校验发件地址及服务器身份。配置变化后不悄悄改变旧记录的目的地或发送通道。
- 设置页只在完整服务器身份与发件地址相同的情况下允许凭据留空沿用；换服务器、端口、加密方式或登录用户名，必须重新输入凭据。

`pending → sending → accepted / pending / blocked / uncertain` 的语义保持：DATA 提交后掉线或进程中断可能已经投递，结果不明不自动重发，先让用户核对邮箱。固定 Message-ID 不保证 exactly-once。已发送记录保留 30 天，未成功记录保留；暂停不采集新短信，原待发记录保留。没有累计运行时长截止。

## Compose 界面与交互

`MainActivity` / `MailSettingsActivity` 使用 `ComponentActivity.setContent`；`ui/RelayScreens.kt` 提供首页、设置、Material 3 对话框与有类型的状态 / 事件，Activity 保留权限、队列和设置操作编排。`ui/SmsFerryTheme.kt` 跟随系统深浅色，Android 12+ 使用系统动态配色。页面使用可滚动布局、系统栏与输入法内边距，并约束大屏内容宽度。

首页仅展示脱敏收件地址、运行状态、运行检查和最近 5 条记录；更多入口保留最近 20 条。设置页默认只读，首次无配置直接进入编辑；保存是唯一配置写入入口，取消 / 返回丢弃草稿，编辑中测试按钮禁用且 handler 也拒绝发信。已保存凭据不回填，不进入可保存的 Compose 状态或 Android View 状态。

短信权限为接收条件，通知与后台优化为建议；重新允许短信权限只恢复原游标扫描，不建立新基线。系统电池优化豁免不等于三星从不休眠名单，强行停止仍需手动打开。重启首次解锁后恢复；不承诺首次解锁前、拔线过夜或所有 Android 版本的兼容行为。

## 邮件模板（2026-10-15）

沿用纯文本 MIME 与当前设置草稿流程，提供标题 / 正文编辑、固定示例预览、恢复默认。`MailTemplate` 只支持 `{{sender}}`、`{{time}}`、`{{body}}` 单次替换，正文须保留完整短信变量。现有需求没有条件分支、HTML 或执行脚本需求，因此不引入通用模板引擎和新依赖，避免额外语法及转义规则。标题模板限制 300 字符、正文模板 10,000 字符；长度限制仅针对配置，不截实际短信正文。

模板作为原 version 2 配置及原队列加密 payload 的可选 `template: {subject, body}` 字段；旧数据缺失时保持原格式，读取不重写，无数据库 schema 迁移。入队时复制模板，发送和重试读取队列快照，避免修改模板后旧邮件悄悄变样。原 SMTP 身份、目的地、去重、暂停、认证阻塞和不明结果语义保持。默认测试邮件沿用原格式，自定义模板的测试邮件使用测试来源与正文渲染。预览始终使用虚构样例，无短信读取或网络副作用。

标题变量的控制字符和换行在 MIME 边界替换为空格，短信正文保持原样；[Jakarta Mail 2.1 `MimeMessage.setSubject` 文档](https://jakarta.ee/specifications/mail/2.1/apidocs/jakarta.mail/jakarta/mail/internet/mimemessage)明确由应用保证标题不含换行（核查 2026-10-15，项目 Angus Mail 2.0.5）。回退可在编辑页恢复默认并保存；已有队列保留原快照。0.2.3 / code 8 的发布与升级结果见 PROJECT_MAP；真实设备的邮箱投递仍须单独验收。

## 选型与来源

- Compose Material 3：Google 的 Material Views 库已进入维护模式；当前 App 两页、交互范围明确，用户确认迁移，保留后台 / 数据层。采用 Compose BOM `2025.10.01`、activity-compose `1.11.0`，编译插件 `2.2.10` 与现有 AGP 9.1.0 的内置 Kotlin 对齐。选择与 compileSdk 36 相容的固定版本，避免把最新要求 compileSdk 37 的依赖升级带入本次改动。
- 原生 SQLite、Android Keystore AES-GCM、WorkManager 2.11.2、Angus Mail 2.0.5 继续使用，没有中转服务器、POP 收信任务或额外网络权限。

核查来源（2026-10-14）：[Material Compose-first](https://github.com/material-components/material-components-android/blob/master/docs/getting-started.md)、[Compose 编译与依赖配置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)、[Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3)、[Angus SMTP / TLS 参数](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html)。

验证入口与当前限制见 [PROJECT_MAP](../PROJECT_MAP.md)、[README](../README.md)。真实邮箱投递与用户主观 UI 验收仍需独立验证。

预设参数核对：QQ 沿用已验证的原配置；163 的 `smtp.163.com:465` 参考 [Lexmark 官方 SMTP 设置文档](https://publications.lexmark.com/publications/lexmark_hardware/CX421_CX522_CX622_CX625_MC2325_MC2425_MC2535_MC2640_XC2235_XC4240/UG/html/ru/configuring-smtp-server-for-e-mail-topic.html)。未采纳该文档的关闭证书校验建议，本应用始终校验证书；163 真实投递仍待账号实测。
