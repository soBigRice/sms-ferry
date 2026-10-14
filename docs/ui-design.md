# 图标与动效

核对日期：2026-10-14，0.2.1。用户已选 A「信舟」，同意完善动效及保留数据安装到三星；主观体验待真机验收。

## 图标

深绿背景、浅色信封船与波纹；原生矢量前景 `res/drawable/ic_launcher_foreground.xml` 是唯一图形源。`mipmap-anydpi/ic_launcher.xml` 组合背景、前景及同一轮廓的 monochrome 图层。108dp 图层内的主要图形保留在中央 66dp 区域；桌面形状由启动器裁切，不在矢量资源内烘焙圆角或阴影。应用包名和通知小图标保持原约定。

## 触发与边界

| 入口 | 动效 | 数据与交互约束 |
|---|---|---|
| `HomeScreen` 更新 | 标题切换、状态颜色、卡片尺寸 | 以 `HomeState` 为准，动画结束不触发业务操作 |
| `CountCell` | 按增减方向切换数字 | 只显示真实旧值和新值，不插值制造计数 |
| `HistoryEntry` / 列表稳定 key | 新记录淡入与展开、其他条目位置调整 | 使用记录 ID；点击仍调用原 `actions.record` |
| `MailSettingsScreen` 编辑 / 只读 | 淡入、短距离位移、容器高度变化 | `AnimatedContent` 仅以 editing 布尔值为 key，输入不触发模式转场；离场按钮禁用，取消后草稿不保留 |
| 保存 / 取消编辑 | 收起配置、返回列表顶部、关闭键盘 | 沿用原保存与取消逻辑，不自动保存，不保存密码到 UI 可恢复状态 |
| `MainActivity.openMailSettings` / `MailSettingsActivity.leaveSettings` | 进入与返回的水平短距离过渡 | Android 14+ 使用平台关闭转场 API；26–33 保留平台兼容路径 |

Compose 内容过渡约 220ms、容器 280ms，Activity 240ms；采用有限过渡，业务动作立即执行。Compose 动画遵循系统 animator duration scale，页面转场遵循系统窗口 / 转场动画缩放。只有真实 busy 状态显示 Material 进度条，没有后台常驻装饰动画。

## 选型与验证

沿用 Compose BOM 2025.10.01 / Material 3，不添加动效或图标库。依据 [Android 自适应图标](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive)、[Compose 动画](https://developer.android.com/develop/ui/compose/animation/quick-guide) 与 [Activity 转场 API](https://developer.android.com/reference/android/app/Activity#overrideActivityTransition(int,%20int,%20int))，2026-10-14 核查。

快速状态变化由 `FerryMotionUiTest.interruptedTransitionsSettleOnLatestStatusAndRealCount` 检查；配置保存 / 取消 / 返回约束沿用 `MailSettingsUiTest`。原生测试只在隔离模拟器运行，不能在有真实配置和记录的用户手机执行。

本轮实际结果：构建通过，30 项单元测试、28 项原生测试及关闭动画的 8 项界面测试通过；Lint 0 错误、16 项既有警告。已查看模拟器圆形图标、浅色 / 深色与编辑态；S21 保留数据安装成功，配置密文与 9 条记录未改变。实际主观效果等待用户测试。
