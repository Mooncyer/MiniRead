# 构建与发布

## 环境要求

- JDK 17
- Android SDK Platform 35
- Android Build Tools 34 或兼容版本
- Gradle 使用仓库内 Wrapper（Gradle 8.13）

本项目使用 Android Gradle Plugin 8.1.0、Kotlin Gradle Plugin 1.9.20。项目依赖在 `build.gradle` 中声明；如果使用离线环境，请先在联网环境完成依赖预取。

## 常用任务

Debug 构建不依赖私有签名文件，适合 GitHub 克隆后的快速检查：

```powershell
.\gradlew.bat tasks
.\gradlew.bat assembleDebug
```

Release 构建只有在本地存在并正确配置 `keystore.properties` 时才会继续：

```powershell
.\gradlew.bat assembleRelease
```

APK 输出位置：

- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/app-release.apk`

这些构建产物均由 `.gitignore` 排除，不要提交到源码仓库。

## Release 签名

1. 将发布 keystore 放在安全的本地位置，不要放入 Git 仓库。
2. 从模板创建本地配置：

   ```powershell
   Copy-Item keystore.properties.example keystore.properties
   ```

3. 填写 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`。
4. 构建并校验：

   ```powershell
   .\gradlew.bat assembleRelease
   $apk = ".\app\build\outputs\apk\release\app-release.apk"
   apksigner verify --verbose --print-certs $apk
   ```

`keystore.properties` 和 keystore 已列入忽略规则。不要在 README、Issue、日志或提交中粘贴真实口令、私钥或签名文件。

## 安装到手表

```powershell
adb devices -l
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n mini.read/.MainActivity
```

Release 与 debug 的签名通常不同，不能直接覆盖安装。若设备已有不同证书签名的包，先确认阅读进度/文档备份，再卸载旧包或使用相同签名证书构建。

## Release 检查清单

- `assembleRelease` 成功且 lint vital 检查通过。
- `apksigner verify --verbose --print-certs` 显示签名有效，证书指纹符合预期。
- 安装后检查启动、文件权限、文档打开、表冠和滑动返回。
- 检查 APK 大小及 OWW211 上的 PSS：

  ```powershell
  adb shell dumpsys meminfo mini.read
  adb logcat -b crash -t 200
  ```
