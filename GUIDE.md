# 编译、安装与排障指南

## 适用范围

本文对应 1.0.19 开发验证版本。默认链路为 `SplashActivity → EmbeddedBrowserActivity → GeckoView`，最低和目标系统均为 Android 11 / API 30。旧 `MainActivity` 系统 WebView 路径保留在仓库中，不是默认启动入口。

当前已复现卸载重装后的深色空白页，尚未完成根因定位；本文的可编译、可安装不等同于首页和播放验收通过。

## 工具链

首次安装工具、配置跨平台环境和排查编译错误，先阅读[完整编译说明](编译说明.md)；本指南侧重安装、运行和电视排障。

| 工具 | 版本或要求 |
| --- | --- |
| JDK | CI 17；现场 JDK 21，Java / Kotlin 字节码目标 17 |
| Gradle Wrapper | 9.3.1 |
| Android Gradle Plugin | 9.1.1 |
| Kotlin | 2.4.20，AGP 内置 Kotlin |
| SDK Platform | `platforms;android-37.0` |
| Build Tools | `build-tools;36.0.0` |
| ADB | `platform-tools` |
| Node.js | 现场使用 24，运行内置 `node:test` 测试 |

配置 `JAVA_HOME`、`ANDROID_HOME`，或在不入库的 `local.properties` 中设置 `sdk.dir`。首次依赖解析需要访问 Google、Maven Central、Mozilla Maven；离线构建只适用于依赖与 SDK 已缓存的环境。

```bash
sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"
java -version
./gradlew --version
./gradlew assembleDebug testDebugUnitTest lintDebug
node --test tests/*.test.cjs
./gradlew assembleRelease
```

Windows 将 `./gradlew` 改为 `.\gradlew.bat`。APK 输出目录为 `app/build/outputs/apk/debug/` 和 `app/build/outputs/apk/release/`。

`ExpiredTargetSdkVersion` 在本项目 lint 中为警告，因为目标设备基线明确为 API 30 侧载；这不表示满足应用商店的目标 API 要求。其他 lint 检查保持默认。

## 签名与 ARM64 现场包

默认 debug 签名与项目 release 签名不同，只有同签名包才能覆盖原安装。现场测试曾复用项目 release 签名，并限制为 `arm64-v8a`；默认 Wrapper 命令不包含这个临时覆盖，包体与现场包可能不同。

需要复现该构建时，将以下内容保存为仓库外的 `tv-debug.init.gradle`，例如 macOS / Linux 的 `/tmp/tv-debug.init.gradle`：

```groovy
gradle.beforeProject { project ->
    project.plugins.withId('com.android.application') {
        project.androidComponents.finalizeDsl { android ->
            android.buildTypes.debug.signingConfig = android.signingConfigs.release
            android.defaultConfig.ndk.abiFilters.clear()
            android.defaultConfig.ndk.abiFilters.add('arm64-v8a')
        }
    }
}
```

```bash
./gradlew -I /tmp/tv-debug.init.gradle assembleDebug testDebugUnitTest lintDebug
```

这仍是可调试 APK，不能因使用 release 签名就称为正式发行构建。现场 ARM64 包约 208 MB，默认多 ABI 包可能更大。不要使用不相同的签名包测试“保留登录升级”。

## ADB 安装和启动

按电视提供的方式开启 ADB。Android 11 无线调试若提供配对端口，先配对，再连接调试端口；若电视使用传统 ADB，则按其实际地址和端口连接。

```bash
adb pair TV_IP:PAIR_PORT
adb connect TV_IP:DEBUG_PORT
adb devices -l
adb -s SERIAL install -r app/build/outputs/apk/release/app-release.apk
adb -s SERIAL shell am start -n com.douyin.tv/.SplashActivity
```

`PAIR_PORT` 与 `DEBUG_PORT` 可能不同，不能固定都用 5555。使用 U 盘安装时通过电视文件管理器选择 APK，并按系统提示允许安装来源。

全新安装使用 `adb -s SERIAL uninstall com.douyin.tv`，然后安装并启动；卸载会清除资料。覆盖升级、清数据、卸载重装是不同测试场景，记录时必须区分。

## 默认内核和浏览器身份

- APK 内置 GeckoView `158.0.20261007201254`，不更新或切换系统 WebView provider。
- 浏览器使用独立持久化 profile；不继承旧系统 WebView Cookie。
- 默认 UA 为 Android 11 平板兼容身份，Chrome 83 是兼容标记，实际执行内核仍为 GeckoView 158。
- Firefox 平板 UA 在历史测试中被分流到下载页；较新 Chrome 兼容标记没有减少主要脚本体积，所以没有据此修改默认 UA。
- 手机端展示的设备类型还需重新登录后验证，不能仅凭 UA 字符串承诺服务端会如何分类。

调试构建允许通过启动参数对照 UA；正式构建不使用该调试覆盖。正常使用从电视图标或 `SplashActivity` 进入即可。

## 遥控和播放控制

完整键位见 [QUICKSTART.md](QUICKSTART.md)。方向移动与普通确认点击走原生鼠标事件；播放控制通过内置扩展适配网页。网页繁忙时原生光标与菜单仍能独立绘制，但实际点击或播放命令仍可能等待网页处理。

自动模式切换保存进入播放前的状态，不会因为重复的播放通知覆盖快照。返回先关闭登录弹窗、退出播放器，再处理网页历史；主页面第一次提示，2 秒内再按才退出。找不到播放器关闭按钮时保留页面并提示。

进度条在快进退时显示四秒，普通时间更新不延长显示时间。未知时长或尚未就绪的视频可能无法快进；站点未提供相邻视频时不能保证切换成功。

## 空白和加载异常排查

先记录版本、覆盖升级还是全新安装、电视型号、系统版本、当前画面和已执行的操作，不先清数据或更换系统内核。

```bash
adb -s SERIAL shell dumpsys package com.douyin.tv
adb -s SERIAL shell pidof com.douyin.tv
adb -s SERIAL shell dumpsys activity activities
adb -s SERIAL logcat -d --pid=APP_PID -s DouyinGecko:V GeckoConsole:E AndroidRuntime:E
adb -s SERIAL exec-out screencap -p > tv-screen.png
```

用 `pidof` 得到的新 PID 替换 `APP_PID`。退出并重新进入、重装或进程崩溃后需要重新核对，不能把旧进程日志当成当前页面状态。

- 仅有光标、深色背景和顶部进度不算进入首页。
- `onPageStop(success=true)` 表示导航完成，扩展 `ready` 表示控制通道已建立；都不能代替实际页面内容检查。
- 需要核对实际 URL、页面标题、可见站点提示、视频卡片和播放器状态。空白原因尚未明确时不要直接归因于网络、账号或无障碍。
- 检查期间暂停遥控器操作可避免页面被退出；排查结束应明确恢复正常操作。

## 性能诊断

调试构建在扩展就绪后进行一次八秒采样，也可从原生菜单选择“诊断：采样当前页面”。结果写入应用日志和私有 `cache/browser-performance.json`；旧文件可能来自前一次采样，应核对 `sampleStartedAt`、UA、视口和可见性。

```bash
adb -s SERIAL shell run-as com.douyin.tv cat cache/browser-performance.json
```

`run-as` 需要可调试包。指标包括帧回调、帧间隔、100 ms 事件循环计时的调度延迟、视频缓冲和解码帧。计时器仅在 probe 内运行，结束会清理。`longTaskSupported=false` 时，零计数不代表没有长任务；调度延迟也不等同于 LongTask 时长。

需要浏览器调试时，调试包提供本机 socket：

```bash
adb -s SERIAL forward tcp:9224 localabstract:com.douyin.tv/firefox-debugger-socket
```

这是 Mozilla RDP，不是 Chromium CDP。页面已关闭时可能没有 tab；`evaluationResult` 中的异常位置 `frame` 不能误当作网页目标。使用采样器时还要区分 profiler 自身开销、标记墙钟跨度和被采样函数的实际 CPU 开销。

详细数据和测试边界见[诊断报告](电视流畅性诊断与内置内核验证.md)。

## 发布与验收

仓库现有 workflow 在 `main` / `master` 推送或手动运行时，会自动改版本、构建、推送标签并创建正式 GitHub Release。源码提交不等同于实机验收；本次已知空白问题尚未解决，提交标记 `[skip ci]` 避免自动发布，本地验证不省略。

发布前至少验证全新安装可进首页、登录完成、光标点击、列表滚动、进退全屏、上下切视频、快进进度条、连续返回退出和同签名升级后的资料保留。当前这些条目尚未全部通过。
