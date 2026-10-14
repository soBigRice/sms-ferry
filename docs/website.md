# 官网与 APK 下载

2026-10-14，用户授权制作小官网、GitHub Pages 部署以及 APK 下载。沿用已选 A「信舟」品牌：深绿 #176B4C、暖白 #FAFAF5、浅绿面板、中文系统字体，首页 / 功能 / 三步配置 / 下载与 FAQ。原生 SVG 插图描述短信到邮箱的路径，图标从 Android 矢量导出；没有假界面、统计或用户资料。

## 入口与发布

- `site/index.html` / `styles.css`：语义化单页、移动布局、键盘焦点、原生 details；不使用构建框架或外部字体。
- `site/main.js`：同源读取 `release.json`、增强校验值复制。下载链接与版本已内置 HTML，JS / JSON 加载失败时仍能下载；只允许本仓库 Releases URL。
- `site/release.json`：当前 APK 版本、字节数、SHA-256、不可变版本下载链接。预览包不用 `releases/latest/download`，避免 GitHub 排除 prerelease 后产生 404。
- `scripts/check-site.py`：标准库检查 HTML 与 release.json 的版本 / 下载 / 校验一致性、相对资源、锚点和第三方许可；更新版本时同时更新 HTML 无 JS 的回退内容。
- `.github/workflows/pages.yml`：main 的官网修改触发，官方 Pages Actions 按完整 SHA 固定；仅上传 `site/`，不发布源码、测试记录、手机核对副本或本地 deliverables。
- APK 发布到 GitHub Releases `v0.2.1`（prerelease）。源提交 `f9f3afc81ca6cf40e79a51d92ed861c30444cd6e`，不是把 APK 提交进 Git 历史。相同包名与签名覆盖升级才能保留原数据；当前 Debug 签名包如实标为预览版。

本地预览：`python3 -m http.server 8765 --bind 127.0.0.1 --directory site`。发布前运行 `python3 scripts/check-site.py`，查看桌面 / 手机并核对在线 APK 下载的 SHA-256。结束时关闭临时服务器，删除下载核对副本。

GitHub Pages 设置为 Actions 构建，网址 `https://sobigrice.github.io/sms-ferry/`。部署完成需检查对应 Actions run、HTTPS 页面和实际 APK 响应，工作流成功不代替用户视觉验收。

本轮发布已完成：官网源码 `3c11ebfd99ceb5779a902dd113e319947bfb11fb`，对应 [Pages 部署运行](https://github.com/soBigRice/sms-ferry/actions/runs/38545346678) 成功。内置浏览器已查看线上 HTTPS 页面，360 / 390 / 768 / 1280 宽度无横向溢出；FAQ 与校验信息可展开，资源加载正常，无页面运行错误。浏览器实际下载 APK 得到 12,693,019 字节，SHA-256 与 `release.json`、原包一致，签名验证通过。临时下载与本地预览服务在收尾清理；用户视觉验收待反馈。

本机 CLI 直接访问 GitHub Git 端点曾超时，但 GitHub API 正常。核对系统已有代理后，仅在本次 Git 命令用 `-c http.proxy=...` 即可推送；未改全局 Git 或网络设置。后续出现相同症状时，先区分 Git / API 端点和系统代理是否被 CLI 使用，不直接改仓库地址或把网络失败当认证失败。

## 视觉核对

设计概念与浏览器渲染已按标题和 CTA、左右构图、暖白 / 绿配色、插图比例、横向功能条、编号步骤和下载面板逐项对照。功能描述按实际支持的 SMTP 认证方式收敛，银行示例改为无文字短信示意；增加 APK 大小 / 校验值和 FAQ，补足公开分发所需的安装与隐私信息。没有把邮件发送说成已读、零延迟或端到端加密。

图标使用 Lucide 1.57.0 的 settings / list / shield-check / download 四枚 SVG，描边改为品牌绿；完整上游 ISC / MIT 声明保留在 `site/assets/icons/LICENSE`。参考 [Lucide](https://github.com/lucide-icons/lucide/tree/1.57.0)、[官方静态站 Pages 工作流](https://github.com/actions/starter-workflows/blob/main/pages/static.yml)；2026-10-14 核查，不新增 npm 依赖。
