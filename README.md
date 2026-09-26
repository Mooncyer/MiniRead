# MiniRead

MiniRead | 一款专为 Android 手表设计的轻量化阅读器

## 功能

- 管理公共存储中的 `/storage/emulated/0/MiniRead/` 文档目录。
- 从 Android 系统文件选择器导入 `.txt`、`.md` 文档，也可作为文本文件的打开方式。
- 按文件窗口读取大文档，避免一次性载入整本小说。
- 保存文档分页偏移和页内滚动比例，支持断点续读。
- 支持全屏阅读、文本选择、编辑、换行、重命名和删除确认。
- 在 OPPO Watch 上消费 Generic Motion `ACTION_SCROLL` 表冠事件，并提供可调灵敏度。
- 深色/浅色主题；中文、英文界面。
- 左向右滑动返回，右向左滑动进入页面操作；编辑页左滑保存并返回目录，右滑显示编辑操作。
- 页面转场、翻页淡入及原生触摸反馈。

## 技术信息

| 项目 | 配置 |
| --- | --- |
| Application ID | `mini.read` |
| 版本 | `0.0.1` |
| 语言 | Kotlin |
| 最低 Android | API 23（Android 6.0） |
| Target SDK | API 30（Android 11） |
| ABI | `armeabi-v7a`、`arm64-v8a` |
| UI | Android 原生 View，Material 风格 |

## 构建

需要 JDK 17、Android SDK Platform 35 和 Android Build Tools。仓库包含 Gradle Wrapper：

```bash
./gradlew assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

Release 构建需要本地签名配置；公开克隆无需 keystore 即可构建 debug 包。签名模板和发布流程见[构建与发布](docs/BUILDING.md)。不要把 keystore 或真实口令提交到 GitHub。

## 安装与运行

安装 debug 包：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n mini.read/.MainActivity
```

首次使用需允许应用管理外部存储。文档目录为 `/storage/emulated/0/MiniRead/`。Android 11 上应用会引导用户授予“管理所有文件”访问权限；系统文件选择器打开文档使用 SAF 授权。

## 测试

公开样例输入位于 [`test/fixtures/`](test/fixtures/)，包含小型 Markdown 与普通文本。大体积或私人测试文档放在被 Git 忽略的 `test/local/`；不要在未确认再分发许可前上传整本文档。建议至少覆盖：

- 目录、设置、深浅主题和文件导入。
- `.md` 语法渲染与 `.txt` 长文分页。
- 编辑保存、编辑态横滑、操作页返回和断点续读。
- 小屏空白区域横滑、文本长按选择和输入法交互。
- 表冠滚动、灵敏度范围以及连续快速旋转。
- `adb logcat -b crash` 中无应用崩溃。

OPPO Watch 表冠输入细节及验证方法见[表冠适配说明](docs/OPPO_WATCH_CROWN.md)。`adb shell input roll` 不能替代实体表冠的 `REL_WHEEL` 验收。

## 项目结构

```text
app/src/main/       Android 应用源码、Manifest 和资源
assets/             项目静态设计素材
build.gradle        根 Gradle 插件配置
settings.gradle     Gradle 工程设置
docs/               需求、架构、构建和设备适配文档
gradle/wrapper/     Gradle Wrapper
test/fixtures/      可公开的小型回归样例
test/local/         本地大文档样例（Git 忽略）
CONTRIBUTING.md     贡献与提交指南
```

维护者入口见[架构说明](docs/ARCHITECTURE.md)和[维护指南](docs/MAINTAINING.md)。初始产品需求记录于[需求基线](docs/REQUIREMENTS.md)。

## 安全与隐私

- `keystore.properties`、keystore、APK、Gradle/IDE 构建目录和本地测试截图均不应提交。
- `keystore.properties.example` 仅说明字段，不含密钥或口令。
- 默认文档保存在设备公共存储的 MiniRead 文件夹；应用不会把文档同步到网络。
