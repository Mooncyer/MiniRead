# 维护指南

## 日常修改流程

1. 先确认修改属于页面、文件读取、输入事件、存储权限还是构建配置。
2. 阅读 [`ARCHITECTURE.md`](ARCHITECTURE.md) 对应章节，保持现有状态机、分页组件和线程边界。
3. 修改后先运行 `./gradlew testDebugUnitTest assembleDebug`，再运行 release 构建检查 R8 和签名配置。
4. 在低内容页面测试空白区域横滑；在按钮/滑块/文本选择区域测试控件优先级。
5. 在编辑模式测试文本输入、换行、右向左进入操作、左向右保存回目录；验证读取失败不会进入空编辑器，保存失败会保留草稿。
6. 从目录长按文档进入操作页后右滑必须回目录；从阅读页进入操作页后右滑回原进度；编辑操作页右滑回草稿编辑器。
7. 重命名后确认 reader 和阅读进度切换到新文件；复制/导入中断不能留下半文件或提示伪成功。
8. 需要触碰输入事件时，使用 OWW211 实体表冠验证，不把 `adb input roll` 当作最终证据。

## 代码约定

- 使用现有原生 View 和 `MainActivity` 状态流，避免为单个控件引入重量级依赖。
- 任何磁盘读写放到 `executor`，通过 `mainHandler` 更新 UI。
- 不在 UI 线程读取大文档；不要把整本小说放入一个 `TextView` 或 `EditText`。
- 新增用户可见文本时，同时更新 `res/values/strings.xml` 和 `res/values-en/strings.xml`。
- 新增设置时使用 `SharedPreferences`，并给出合理默认值和范围。
- 高速表冠事件不要打印逐事件日志；临时调试完成后删除日志。
- 代码注释只解释输入兼容、分页边界等不明显原因。

## 设备回归

推荐顺序：

1. 启动目录，确认 `MiniRead` 文件夹和权限。
2. 切换深色/浅色，检查页面空白背景。
3. 从目录空白、设置空白、操作页空白测试左右滑动。
4. 打开小文本和 Markdown，验证文本选择和基础格式。
5. 打开大文本，验证分页、断点续读和内存。
6. 进入编辑，验证输入法、换行、编辑态左右滑动和保存。
7. 调整字号、表冠灵敏度，旋转实体表冠滚动阅读和编辑文本。
8. 检查：

   ```powershell
   adb shell dumpsys activity activities | Select-String "ResumedActivity"
   adb shell dumpsys meminfo mini.read
   adb logcat -b crash -t 200
   ```

## 提交前检查

- `git status` 中没有 `build/`、`.gradle/`、APK、截图、keystore 或 `keystore.properties`。
- README 中的命令和路径仍然有效。
- 不提交设备 serial、真实签名口令或个人路径。
- Release APK 使用预期证书：

  ```powershell
  apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
  ```
