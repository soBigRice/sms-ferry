# QQ 邮箱短信转发 Implementation Plan

> 历史实现计划（2026-10-13）：本轮任务已完成。最初约定不创建子代理、不初始化 Git、不在用户验收前提交；2026-10-14 用户确认名称“信渡 · SMS Ferry”并授权创建 GitHub 开源仓库，随后选择 MIT 许可证；仓库初始化与源码提交属于该后续授权。

**Goal:** 构建可安装的 S21 短信到 QQ 邮箱转发 APK。
**Architecture:** 系统短信收件箱 → 加密本地队列 → QQ SMTP。广播、ContentObserver、定期任务共同触发队列，单条原子领取避免并发发信。
**Tech Stack:** Kotlin, Android Views, SQLite, Keystore, WorkManager, Angus Mail。
**Spec:** [email-design.md](email-design.md)

## Global Constraints

- minSdk 26 / targetSdk 35 / compileSdk 36，目标 S21 / One UI 7。
- 不自动发送历史短信，不改默认短信应用，不删除系统短信。
- SMTP fixed smtp.qq.com:465 + TLS；配置和短信加密；无用户凭证写入源码。
- 暂停、重启恢复、认证失败、结果不明必须可观察。
- 已发送记录保留 30 天，未成功记录保留。

## Review Focus

- 同正文的两个 provider ID 必须独立转发。
- 并发 Worker 原子领取同一记录，仅一个能发送。
- SMTP DATA 后掉线不自动重发；恢复 sending 状态需保守核对。
- 改收件地址不能改变旧排队记录。
- 授权码错误、密钥读取失败不能清空配置或丢失队列。

## Tasks

- [x] 1. QueueRunner / Mail models / MailSender：先写 JVM 回归用例，再实现失败分类、目的地固定及 SMTP MIME/TLS。
- [x] 2. CryptoStore / ConfigStore / QueueDatabase / SmsInbox：加密、唯一 provider ID、扫描游标和领取事务；添加设备测试。
- [x] 3. SyncWorker / RelayService / Receivers：只开启后收件箱增量，网络约束、退避、前台通知与重启恢复。
- [x] 4. MainActivity / layouts：QQ 配置、测试邮件、开始/暂停、权限和三星设置、状态及问题记录操作。
- [x] 5. JVM tests + lint + assembleDebug；临时模拟器进行安装、界面、短信队列和恢复检查。
- [x] 6. 更新 README / PROJECT_MAP 和实际验证状态，导出 APK，关闭测试进程、移除本次临时文件与模拟器。
