# 贡献指南

感谢帮助改进 MiniRead。提交变更前，请阅读 [`README.md`](README.md) 和 [`docs/MAINTAINING.md`](docs/MAINTAINING.md)。

## 提交变更

- 一个变更集中解决一个问题，并说明设备、Android 版本和复现步骤。
- 用户可见文本同步维护中文和英文资源。
- 影响文件读取、滚动、权限或返回手势时，说明大文档和手表小屏的回归结果。
- 不提交 `build/`、Gradle 缓存、APK、设备截图、个人测试文档、keystore 或真实签名配置。

## 本地验证

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

如已配置私有签名 keystore，可额外运行：

```powershell
.\gradlew.bat assembleRelease
```

Debug 构建不需要 keystore。Release 签名配置见 [`docs/BUILDING.md`](docs/BUILDING.md)。

## Issue 报告

请提供：

- 设备型号、Android 版本和应用版本。
- 页面状态及触发步骤。
- 预期结果与实际结果。
- 相关 `logcat` 输出；提交前请删除个人路径、文档内容、URI、令牌和其他隐私数据。

表冠问题请注明是否用实体表冠复现。`adb input roll` 不能替代 OWW211 的真实 `REL_WHEEL` 输入。
