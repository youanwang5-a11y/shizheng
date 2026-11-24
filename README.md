# 拾证

拾证是一款面向大学生的 Android 应用。它把比赛、活动、志愿服务等经历与对应证明放在一起，方便学期末查找。记录、学期和附件保存在手机中，可以离线使用。AI 默认关闭。

## 安装和使用

在 Android 9（API 28）及以上的设备上打开 [版本索引](docs/RELEASES.md)中的签名 APK。通过聊天工具收到 APK 后，可先保存到手机，再从系统文件管理器打开。Android 若提示安装来源未授权，可为当前打开 APK 的应用临时授权。使用 ADB 时执行 `adb install -r <APK路径>`。

- 在「记录」点「新增」，填写名称、类别和日期，再点「保存记录」。在详情里可随时补充照片、PDF 和 Word。
- 在「我的」可管理学期、备份和恢复。换手机前把完整备份保存到能带走的位置。
- 如需使用 AI，在「我的 → AI 设置」配置支持 OpenAI Chat Completions 的 HTTPS 服务，再从「我的 → AI 记录」进入文字整理。

[一页使用说明](docs/使用说明.md) · [同学试用版软件及功能介绍](docs/拾证软件及功能介绍.docx)

## 构建

需要 JDK 17、Android SDK Platform 36 和 Build-Tools 35.0.0。测试设备另需 Platform-Tools，模拟器使用 Android 15 / API 35 x86_64 镜像。本项目固定使用 Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21、Room 2.8.4 和 Compose BOM 2025.10.01。Wrapper 会验证 Gradle 分发包的 SHA-256。 [AGP 兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes) · [SDK 命令行工具](https://developer.android.com/tools) · [Compose BOM](https://developer.android.com/develop/ui/compose/bom) · [Room](https://developer.android.com/jetpack/androidx/releases/room)

将 `JAVA_HOME` 指向 JDK 17，`ANDROID_HOME` 指向 SDK，或由 Android Studio 生成本机 `local.properties`。使用 `local.properties` 时，Windows 盘符写作 `E\:/Android/Sdk`。首次构建需要访问 Google Maven、Maven Central 和 Gradle Plugin Portal。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
.\gradlew.bat :app:connectedDebugAndroidTest
```

macOS 和 Linux 使用 `./gradlew`。调试 APK 包名为 `app.shizheng.debug`，与分发版 `app.shizheng` 可以共存。请在专用模拟器运行设备测试。分发版本的 Gradle 输出是未签名 APK，Windows 上使用 [签名脚本](scripts/Sign-Release.ps1)生成可安装文件。构建路径、签名备份和证书验证见 [版本索引](docs/RELEASES.md)。

## 源码和资料

- `app/src/main/java/app/shizheng/data` 保存记录数据、文件与备份逻辑。
- `app/src/main/java/app/shizheng/ai` 管理 AI 协议和本机凭据。
- `app/src/main/java/app/shizheng/ui` 实现 Compose 页面和流程。
- `app/schemas` 保存数据库版本结构。单元测试在 `app/src/test`，设备测试在 `app/src/androidTest`。
- `design` 保存 v1、v2 概念图和生图提示词。`docs` 保存规范、测试、版本和竞赛过程资料。

[产品规范](docs/PRODUCT_SPEC.md) · [架构](docs/ARCHITECTURE.md) · [测试报告](docs/TEST_REPORT.md) · [版本管理及回档](docs/VERSIONING.md)
