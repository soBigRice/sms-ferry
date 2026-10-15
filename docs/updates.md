# GitHub 应用更新

核对日期：2026-10-15。0.2.2 开始支持应用内检查 / 下载 GitHub Releases APK；公开下载仍以实际已发布的 Release 为准。首次从 0.2.1 升级需手动安装包含更新入口的 APK，之后可在首页「检查更新」完成后续升级。0.2.3 邮件模板版使用 versionCode 8，沿用 0.2.2 的 Debug 签名与预览版渠道，旧版更新器无需修改即可发现该资产。

## 行为与边界

```text
首页「检查更新」 → UpdateActivity / UpdateModel
→ GitHubUpdater.check → 公共 Releases 列表（包含 prerelease）
→ 比较语义版本、选择对应签名渠道的 APK
→ 用户下载 → HTTPS / 长度 / SHA-256 / APK 身份校验
→ FileProvider → 安装来源授权（需要时）→ Android 系统安装器确认
→ MY_PACKAGE_REPLACED → 已有 BootReceiver 恢复原先开启的转发
```

- 只在打开更新页或点击重新检查时访问 GitHub；不新增后台轮询、强制升级或静默安装。公共接口不需要账号 / Token，不读取或发送邮箱配置、授权码或短信。
- 固定仓库 `soBigRice/sms-ferry`。使用 `releases?per_page=100`，检查最近 100 条发布；预览版本不走排除 prerelease 的 `releases/latest`。使用三段 SemVer（可带预发布后缀），Tag 建议加 `v` 前缀，版本按数字及 SemVer 优先级比较。
- 目前安装的是 Debug 预览包，对应资产名 `sms-ferry-{version}-debug.apk`。非 Debug 包对应 `sms-ferry-{version}.apk`；不同签名渠道不能互相覆盖。这里只接受当前签名，不引入签名轮换或自动卸载。
- GitHub asset 的 `digest` 必须提供 SHA-256。下载后同时验证字节数、包名 `com.sobigrice.smsrelay`、资产版本与 APK versionName 一致、versionCode 高于已安装包、签名与当前包一致；任一不符都不进入安装器。
- 更新状态与转发状态独立，不写邮箱配置、开启标记、扫描游标或队列。网络失败、取消或安装拒绝均可重试；不表示已经更新成功。旋转屏幕保留更新任务，关闭更新页取消本页任务。
- APK 仅缓存在应用私有 `cache/updates/`，下载失败 / 取消删除临时文件；只保留一份可安装包，下一次下载清理旧包。交给安装器后暂时保留文件供其读取，避免立即删除导致安装失败。系统也可清理缓存，届时需重新下载。
- 手机需要能访问 GitHub API 与 Release 下载域；失败显示原因并提供 GitHub 版本页入口。三星受限短信权限及覆盖升级后的转发仍需要真机验收。

## 后续发版

1. 同时递增 `app/build.gradle.kts` 中的 versionName 和 versionCode；包名保持不变。
2. 用与已安装 APK **相同的 keystore** 构建。当前预览版使用本机已有 Debug 签名；不能在新机器 / CI 上重新生成默认 Debug key 后直接发布，否则覆盖升级会被拒绝。不要把 keystore 或口令提交到仓库。
3. 运行相关单元测试、Lint、构建及升级验收；使用 `apksigner verify --print-certs` 与上一版对比签名证书 SHA-256。
4. 将 APK 命名为上述资产名，上传到同版本 Tag 的 GitHub Release。预览版勾选 prerelease 仍可被应用发现；不需要额外 OTA 服务或 `latest.json`。
5. 核对 GitHub API 的资产字节数 / SHA-256 与本地包一致，再同步官网 `site/release.json` 与 HTML 回退下载信息，并运行 `scripts/check-site.py`。官网与应用分别使用自己的发布读取方式。

发布是独立授权动作；配置应用更新支持不会自动推送源码、创建 Release 或部署官网。

## 验证入口

`UpdatePolicyTest` 覆盖版本排序、升级方向、渠道、URL 和摘要契约；`ReleaseParserTest` 在隔离 Android 模拟器覆盖真实 JSON 解析与 APK 签名元数据；`UpdateFileProviderTest` 验证安装器仅能读取更新缓存目录；`UpdateUiTest` 覆盖下载前不可安装、下载期间不可重复提交、失败可重试而不误报最新。实际结果与仍待验收范围见 PROJECT_MAP 的当前版本验证记录。

实现采用项目现有协程、Android HTTPS、PackageManager、AndroidX FileProvider 和系统安装器，不新增第三方 updater SDK。已有通用更新库会增加依赖且仍需补齐本项目的预览版、签名与摘要契约，当前范围用平台能力即可完成。

来源：[GitHub Releases API](https://docs.github.com/en/rest/releases/releases)、[Release asset digest](https://docs.github.com/en/rest/releases/assets)、[安装来源授权](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())、[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)。核查于 2026-10-14，minSdk 26 / targetSdk 35 / compileSdk 36。
