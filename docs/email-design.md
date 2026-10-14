# QQ 邮箱短信转发第一版

2026-10-13。用户已选择邮箱路线，设备为三星 S21 / One UI 7，只有一张 SIM。

## 范围与验收

个人自用安卓 APK，直接连接 QQ SMTP，iPhone 在邮箱查看。发送账号、授权码、接收地址在手机填写；不预填真实账号，不建设中转服务器，不访问用户邮箱。不实现短信回复、彩信、RCS、通知监听或无障碍。

接收以系统短信收件箱为事实来源：短信广播与前台服务中的 ContentObserver 触发扫描，WorkManager 定期补查。仅转发开启功能后新增的收件短信，不自动群发历史短信。暂停后不捕获新短信，已排队记录保留，恢复后继续发送。

每条短信以系统 `_id` 去重，不按正文或验证码去重，内容相同的两条短信仍独立发送。收件地址及发件账号在入队时固定；配置改变不偷偷改变旧记录目的地。邮件包含来源号码、接收时间与完整正文。

## 可靠性边界

`pending → sending → accepted / pending / blocked / uncertain`。只有 SMTP 接受邮件才标记 accepted，不代表邮箱最终投递、iPhone 通知或用户已读。网络连接阶段失败可以重试，认证及明确拒绝需处理后重试。发送途中结果不明或进程在 sending 阶段结束，保守标记 uncertain，核对邮箱后由用户决定是否重试。SMTP 无法提供跨进程 exactly-once 保证，固定 Message-ID 也不保证邮箱去重。

WorkManager 一次处理最多 5 条，网络失败指数退避；记录不因累计运行时长被丢弃。15 分钟补查不是精确定时保证，Doze 会延后。前台服务采用 remoteMessaging，常驻通知可暂停。三星仍需配置从不休眠、允许后台；强行停止后必须手动打开。重启后首次解锁再恢复，不承诺解锁前收发。

## 数据与安全

邮件账号及授权码、排队短信正文通过 Android Keystore AES-GCM 加密；应用不备份这些数据，不记录 SMTP 原始错误或短信正文到日志。SMTP 固定 smtp.qq.com:465，使用系统 CA、主机名校验，禁止明文网络和信任所有证书。邮件内容会进入用户选择的邮箱，这不是端到端加密。

已发送记录保留 30 天后清理；未成功记录保留，扫描游标防止清理历史记录后重发。授权码不填写在聊天或源文件。SMTP 测试仅由手机按钮明确触发；本地自动测试只访问本地测试服务。

## 实现选型

Kotlin + 原生 Android View；原生 SQLite 管理简单队列，Android Keystore 保管密钥；AndroidX WorkManager 2.11.2 调度；Eclipse Angus Mail 2.0.5 + Activation 2.0.3，使用官方 Android 支持而非自行实现 SMTP。Angus EPL-2.0，随 APK 保留许可证。AGP 9.1.0 / Gradle 9.3.1 / JDK 17 / compileSdk 36 / targetSdk 35 / minSdk 26。目标是用户 Android 15，不据此承诺 Android 16.1 验证码权限兼容。

核查来源（2026-10-13）：
- [QQ 授权码与新版设置入口](https://help.mail.qq.com/detail/106/985)
- [腾讯 SMTP 参数](https://main.qcloudimg.com/raw/document/product/pdf/1270_46586_cn.pdf)
- [Angus 官方 Android 支持](https://eclipse-ee4j.github.io/angus-mail/Android)
- [WorkManager 2.11.2](https://developer.android.com/jetpack/androidx/releases/work)
- [remoteMessaging](https://developer.android.com/develop/background-work/services/fgs/service-types#remote-messaging)
- [三星后台管理](https://developer.samsung.com/mobile/app-management.html)

## 尚需真机验收

实际安装并授予 READ_SMS / RECEIVE_SMS（安装器必须允许受限短信权限）；发测试邮件；普通、长短信及实际验证码；锁屏过夜；断网补发；重启首次解锁恢复；三星从不休眠；邮箱通知与真实投递。无账号、授权码和 S21 连接时不宣称这些已通过。
