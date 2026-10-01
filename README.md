# MiniRead

MiniRead（中文名：笔记）是一款面向 Android 手表的小屏原生阅读器，使用 Kotlin 编写。应用以沉浸式阅读为核心，支持 `.txt` 和 `.md` 文档、长文分页、断点续读、编辑、OPPO Watch 表冠滚动以及中英文界面。

当前版本为 `0.0.2`，已完成 OWW211 验收。版本包含目录页点左上标题退出，并集中修正了长文连续窗口分页、页面异步加载、操作页返回语义、横滑手势和编辑保存可靠性。

## 功能

- 管理公共存储中的 `/storage/emulated/0/MiniRead/` 文档目录。
- 从 Android 系统文件选择器导入 `.txt`、`.md` 文档，也可作为文本文件的打开方式。
- 阅读页采用连续窗口：同时保留相邻文本页，向下追加下一页，向上插入上一页并补偿滚动偏移；接缝不会通过整页替换处理。
- 保存文档分页偏移和页内滚动比例，支持断点续读；页面变更使用异步 token 隔离旧结果，旧 fling 会停止，并在新布局后恢复定位。
- 支持全屏阅读、文本选择、编辑、换行、重命名和删除确认。
- 文档重命名会切换到新的 reader，并迁移对应阅读进度。
- 编辑读取失败时不会进入空编辑器；保存采用原子写入，失败时保留草稿，保存完成后重建 reader。
- 在 OPPO Watch 上消费 Generic Motion `ACTION_SCROLL` 表冠事件，并提供可调灵敏度。
- 深色/浅色主题；中文、英文界面。
- 左向右滑动返回，右向左滑动进入页面操作；目录文档行也支持横滑，横滑导航会取消子控件点击但保留按钮和滑块触摸；编辑页左滑保存并返回目录，右滑显示编辑操作。
- 操作页记录来源 `DIRECTORY`、`READER`、`EDITOR`：目录长按返回目录，阅读操作页返回原进度，编辑操作页返回草稿编辑。
- 目录页点左上标题退出；页面切换保留淡入和轻微横向转场，同时在页面挂载时重置旧视图状态。

## 技术信息

| 项目 | 配置 |
| --- | --- |
| Application ID | `mini.read` |
| 版本 | `0.0.2` |
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

公开样例输入位于 [`test/fixtures/`](test/fixtures/)，包含小型 Markdown 与普通文本。大体积或私人测试文档放在被 Git 忽略的 `test/local/`；不要在未确认再分发许可前上传整本文档。0.0.2 已完成 OWW211 验收，建议后续修改至少覆盖：

- 目录、设置、深浅主题、文件导入，以及点左上标题退出。
- `.md` 语法渲染与 `.txt` 长文分页；多字节 UTF-8、任意断点反向翻页、上一页底部/下一页顶部定位和断点续读。
- 快速连续翻页、页面变更期间旧异步结果隔离、旧 fling 停止以及新布局后的滚动定位。
- 目录文档行横滑、阅读/编辑/操作页的来源返回语义，以及横滑取消子控件点击但不破坏按钮和滑块触摸。
- 编辑读取失败、原子写保存失败保留草稿、保存完成重建 reader，以及重命名后 reader 和阅读进度迁移。
- 编辑保存、编辑态横滑、文本选择、输入法交互和页面转场无残留。
- 表冠滚动、灵敏度范围以及连续快速旋转。
- `adb logcat -b crash` 中无应用崩溃。

具体回归顺序见[维护指南](docs/MAINTAINING.md)，版本变化见[变更记录](docs/CHANGELOG.md)。OPPO Watch 表冠输入细节及验证方法见[表冠适配说明](docs/OPPO_WATCH_CROWN.md)。`adb shell input roll` 不能替代实体表冠的 `REL_WHEEL` 验收。

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

维护者入口见[架构说明](docs/ARCHITECTURE.md)和[维护指南](docs/MAINTAINING.md)。当前版本需求见[需求基线](docs/REQUIREMENTS.md)。

## 安全与隐私

- `keystore.properties`、keystore、APK、Gradle/IDE 构建目录和本地测试截图均不应提交。
- `keystore.properties.example` 仅说明字段，不含密钥或口令。
- 默认文档保存在设备公共存储的 MiniRead 文件夹；应用不会把文档同步到网络。

## License

本仓库目前未声明开源许可证。公开发布前请由项目所有者选择并添加 LICENSE 文件；在此之前，代码默认不授予通用再分发权限。
