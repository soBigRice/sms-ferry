# 第三方组件

本项目的许可证不改变第三方组件的原有许可。运行时依赖由 Gradle 下载，不在源码仓库中直接分发。

- **Gradle Wrapper 9.3.1**：仓库包含 `gradlew`、`gradlew.bat` 和 `gradle/wrapper/gradle-wrapper.jar`。Copyright 2015 the original authors，使用 [Apache License 2.0](LICENSES/Apache-2.0.txt)；脚本中的原始声明保持不变。来源：[Gradle](https://github.com/gradle/gradle)。
- **AndroidX Core / WorkManager**：依赖版本见 `app/build.gradle.kts`，使用 Apache License 2.0。来源：[AndroidX](https://android.googlesource.com/platform/frameworks/support/)。
- **Eclipse Angus Mail / Activation、Jakarta Activation API**：保留各上游组件随包提供的许可证；APK 打包配置合并 `META-INF/LICENSE.md` 和 `META-INF/NOTICE.md`。来源：[Angus Mail](https://github.com/eclipse-ee4j/angus-mail)、[Angus Activation](https://github.com/eclipse-ee4j/angus-activation)、[Jakarta Activation](https://github.com/jakartaee/jaf-api)。

具体版本与选型见 [邮件实现设计](docs/email-design.md)。
