# 架构说明

## 模块边界

当前应用规模较小，核心实现集中在 `app/src/main/java/mini/read/MainActivity.kt`，使用原生 Android View 和 Activity 管理页面及交互，避免引入额外运行时依赖。

- **MainActivity**：管理目录、设置、阅读、文档操作等页面状态；处理系统文件打开、权限、主题、手势、Generic Motion 表冠事件及编辑保存。
- **FilePageReader**：以随机访问文件读取器按约 12 KiB UTF-8 窗口读取文本，优先在换行边界切页。
- **Markdown 渲染**：在当前窗口内将常见 Markdown 块/行内语法转换为 Android `Spannable`，不对整本文件构造 DOM 或一次性渲染。
- **SwipeFrameLayout / SwipeEditText**：分别提供非编辑页面和编辑器上的横滑识别；Activity 级分发负责空白区域的横滑路由。
- **SharedPreferences**：存储主题、阅读字号、表冠灵敏度、每个文档的字节偏移和页内滚动比例。

## 页面与导航

页面状态为 `DIRECTORY`、`SETTINGS`、`READER`、`ACTIONS`。

- 目录 → 点文档 → 阅读。
- 目录右向左 → 设置；设置左向右 → 目录。
- 阅读右向左 → 当前文档操作；操作页左向右 → 阅读。
- 编辑右向左 → 编辑操作（退出编辑、换行）。
- 编辑左向右 → 保存草稿并返回目录。
- 横滑识别优先处理明显水平位移；按钮和 SeekBar 起始触摸交给控件，空白区由 Activity 兜底。

## 大文档与断点续读

`FilePageReader` 使用文件字节偏移分页。UI 线程只接收当前窗口文本；磁盘读取在线程池中完成。阅读进度由文件路径对应的偏移和 ScrollView 页内比例组成。若修改分页窗口、编码或换行处理，需验证 UTF-8 多字节边界不会丢字或重复。

编辑模式当前有 4 MB 文件上限，因为 `EditText` 使用内存中的可编辑文本；大文档仍可阅读但不会被整体载入编辑器。

## 表冠事件

OWW211 的 `pixart_pat9125` 在 Linux 输入层报告 `EV_REL / REL_WHEEL`。Activity 对 `ACTION_SCROLL` 依次读取 `AXIS_VSCROLL`、`AXIS_SCROLL`、`AXIS_HSCROLL`，按设置灵敏度做小数累积，再将像素增量滚动到当前阅读 `ScrollView` 或编辑 `ScrollView`，并消费事件避免进入 OPPO 不兼容 RSB 路径。

`adb shell input roll` 不是设备实体表冠的等价模拟。硬件验收应旋转实体表冠，并同时观察 `adb shell getevent -lt /dev/input/event2` 与应用崩溃日志。

## 存储与权限

- 应用库目录：`/storage/emulated/0/MiniRead/`。
- Android 11 目标版本需要用户授予管理所有文件访问权限才能操作公共目录。
- 外部文件通过 `ACTION_VIEW` / SAF URI 读取；导入时复制到应用库，并在提供方支持的情况下尝试删除源 URI。
- 不要把文档内容、URI 权限或签名秘密写入日志。
