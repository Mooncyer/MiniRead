# OPPO Watch 表冠适配

本项目保留了原始设备实测记录，完整底层分析见仓库根目录历史文档的整理版本。当前验证设备：OPPO Watch 3 Pro，型号 `OWW211`。

## 设备特征

- 输入设备：`pixart_pat9125`
- 设备节点：`/dev/input/event2`
- Linux 事件：`EV_REL / REL_WHEEL`
- Android 层：通过 Generic Motion `ACTION_SCROLL` 进入 Activity

应用不强制要求 `SOURCE_ROTARY_ENCODER`，并按以下顺序读取：

1. `AXIS_VSCROLL`
2. `AXIS_SCROLL`
3. `AXIS_HSCROLL`

事件会被消费，避免进入 OPPO 固件中可能依赖缺失 `com.google.android.wearable` 的不兼容 RSB 路径。Manifest 保留可选 shared library 声明：

```xml
<uses-library
    android:name="com.google.android.wearable"
    android:required="false" />
```

## 调试命令

```powershell
adb shell getevent -lp /dev/input/event2
adb shell getevent -lt /dev/input/event2
adb shell logcat -b crash -t 200
adb shell dumpsys activity activities | Select-String "ResumedActivity"
```

`adb shell input roll 0 8` 只能验证应用能否处理另一类输入，不能模拟真实 `REL_WHEEL`。最终验证必须旋转实体表冠。

## 灵敏度

设置页的“表冠灵敏度”范围为 1–8，默认值为 4。代码通过小数累积将输入增量转换为像素滚动，避免小增量因取整丢失。阅读模式使用阅读 `ScrollView`，编辑模式使用编辑专用 `ScrollView`。

## 验收

- 目录、设置、阅读、文档操作、编辑页面均不崩溃。
- 连续快速旋转不触发 RSB/共享库崩溃。
- 阅读长文本和编辑长文本均能滚动。
- 返回页面后再次旋转表冠仍能操作当前滚动容器。
