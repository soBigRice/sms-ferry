# 相关工程记录

本文引用的 `deliverables/verification.md` 为本地设备验收记录，不随源码仓库发布；公开的验证入口与适用范围见 [README](README.md) 和 [PROJECT_MAP](PROJECT_MAP.md)。

## SMTP 结果不明 / 重复发送（2026-10-13）

适用：SMTP 直发短信邮件。连接失败与 DATA 提交后断线不是同一故障，后者可能已经投递。不能把任何异常统一设为可重试，也不能把固定 Message-ID 当作 exactly-once 保证。采用持久 sending 标记；进程恢复和不明确发送异常转 uncertain，让用户先核对邮箱。单元和本地 TLS 故障测试覆盖接受、认证拒绝、DATA 后断线。

## 邮件 TLS 错误分类（2026-10-13）

证书校验始终保持开启。针对 Angus 2.0.5，本地受信证书但主机名不匹配的回归用例最初得到网络超时。首次猜测分类不足、按旧 JavaMail 错误文本补判断无效，已经移除。读取实际依赖实现和真实异常链后确认：自定义测试 socket factory 在首次 TLS 失败后默认回退到系统 factory，而单连接测试服务不接第二条连接，最终暴露第二次连接的超时。测试设置 `mail.smtp.socketFactory.fallback=false`，验证原始 TLS 拒绝；生产使用系统 factory，无该注入。下次先区分业务故障与夹具故障，取得真实异常链再改分类，不通过放宽证书或主机名校验制造通过。

## 短信来源与身份（2026-10-13）

目标 S21 / Android 15。系统收件箱为单一来源，SMS_RECEIVED 只触发扫描；广播可能先于默认短信应用写入收件箱。收件箱观察、该事件的有界复查及定期补查共同补偿。按 provider `_id` 去重，相同内容的独立短信不得合并。暂不把模拟器 provider 验证推广为真实三星、所有验证码或 RCS 的兼容结论。

## 广播退避挡住收件箱观察（2026-10-14）

S21 / Android 15，用户反馈锁屏延迟、解锁补发。应用前台服务存在、电池白名单和后台权限正常；不能仅凭锁屏现象判断为 Doze。一次真机测试在锁屏、USB 充电期间确认邮件到达，但短信时间 10:13:32.375 到 SMTP 接受 10:14:34.374 延迟约 62 秒。WorkManager 日志显示广播扫描首次无新行返回 RETRY，工作库 Dependency 证实随后观察扫描依赖该重试，直到 10:14:32 才执行。

根因是所有扫描共用 APPEND_OR_REPLACE 工作链，前置退避使后续扫描 BLOCKED。修复为广播有界复查与观察扫描使用不同 unique work 名称；继续复用数据库唯一 source_id、事务游标和串行发信，暂停取消两条链。采用平台原生 WorkManager 2.11.2，未增加常驻轮询或唤醒锁。参考 [官方工作链状态说明](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/chain-work)，核查于 2026-10-14。

回归 `QueueSchedulerTest.providerChangeRunsWhileEarlyBroadcastIsWaitingForRetry` 在旧实现确认为 BLOCKED 而失败；修复后结果和真机复测记录见 `deliverables/verification.md`。下次先比较短信入库、Worker 首次运行、重试与依赖状态，区分应用排队、系统调度、SMTP 和 iPhone 通知。短时锁屏通过不代表断电深度休眠或过夜通过。

## 邮箱配置防误触（2026-10-14）

用户指出运行页配置容易误改，并选择“防误触即可”。原 MainActivity 将配置表单与运行操作混放，且测试/开始转发共用 withValidatedAccount，未点击保存也会写入表单并清除认证故障。修改为独立设置页默认只读、显式编辑和保存、取消/返回丢弃内存草稿；测试和运行入口只读取已保存配置。新增 MailSettingsUiTest 覆盖这几个边界，不通过额外密码或后台验证增加运行依赖。下次检查配置可修改性时同时追踪全部写入入口，不能只禁用输入框而保留隐式保存。验证状态见 deliverables/verification.md。

相关测试经验：enabled 标记由后台任务写入，早于主线程启动并提升 RelayService。仅等待 enabled 后立即清理，可能在服务提升前将其停止；本轮夹具出现该系统异常。测试等待实际 RelayService.running，隔离 Activity task，结束页面后再停止服务。最终 19 项原生测试通过，不能将测试直接操纵配置造成的生命周期竞争等同于真机已发生同类故障。

## 权限检测与操作引导（2026-10-14）

用户要求简洁 UI、检测权限并减少操作。之前只以短信权限决定是否申请，短信已允许但通知未允许时会跳过通知提示；电池和三星设置一直是手动入口，没有实时读系统状态。现在以 Readiness 区分短信双权限、通知权限/应用总开关/频道、优化豁免与后台限制，缺失项才显示操作；已保存配置维持只读，首次无配置直接填表。

通知是建议项，短信是接收条件；通知拒绝仍允许前台服务及转发，不能把所有建议都升级为硬门槛。系统电池优化豁免不等于三星从不休眠，也不能据此承诺过夜无延迟。短信权限重新允许时，恢复观察与扫描但不重设已有游标。下次权限故障优先分辨未请求、可再次请求、永久拒绝以及厂商独立设置；从系统设置返回时必须重新检测，不用本地“已配置”勾选代替系统结果。平台规则与版本核查：Android 15 目标 / compileSdk 36，2026-10-14。

来源：[通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)、[电池优化](https://developer.android.com/training/monitoring-device-state/doze-standby)、[后台限制 API](https://developer.android.com/reference/android/app/ActivityManager#isBackgroundRestricted())、[三星后台管理](https://developer.samsung.com/mobile/app-management.html)。验证结果见 deliverables/verification.md。

原生 UI 验证经验：权限第二次拒绝按钮可能变成 permission_deny_and_dont_ask_again_button；系统设置与 App 可在同一 Activity task 中，am start 复用任务不一定把设置页弹出。先核对当前界面与实际按钮 ID，再模拟真正返回或 CLEAR_TOP，不能把探针拿不到目标节点当成产品崩溃。0.1.3 以最终 APK 通过 20 项原生测试及真实系统权限流程，视觉由用户继续验收。

## 开机广播排队与实际转发分层核对（2026-10-14）

S21 / Android 15 / 0.1.3 真机重启首次解锁后，初查 RelayService 未运行，但 BOOT_COMPLETED 的 BootReceiver 仍为 PENDING OFFLOAD，尚未送达；系统最终记录排队 81.770 秒后 DELIVERED，前台服务自行恢复。期间短信广播与 WorkManager 已在 3.981 秒内完成 QQ SMTP 接受，用户确认 iPhone 收到，全程未打开 App。没有故障修补或权限调整。下次检查开机恢复，分别核对开机计数、解锁状态、系统广播投递状态、服务、队列及用户实际邮件，避免只凭服务暂未出现就误判失败或增加启动补丁。此次只证实 USB 连接、首次解锁后的恢复；尚未解锁和拔线过夜不在结论范围。证据见 deliverables/verification.md 的 S21 真机重启恢复条目。
