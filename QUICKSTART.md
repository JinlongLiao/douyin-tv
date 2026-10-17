# 快速开始

## 当前状态

当前源码版本为 1.0.21，最低支持 Android 11。APK 使用内置 GeckoView，不需要更换电视的系统 WebView。

VIDAA TV 曾出现卸载重装后停留深色背景的问题；1.0.20 在独立全新资料包复现并修复首次分流到下载页，但历史空白及完整流程尚未全部验收。应用进入 Activity、显示光标或日志出现 `ready` 均不代表已进入可用首页。具体状态见[诊断记录](电视流畅性诊断与内置内核验证.md)。

## 编译

准备 JDK 17 或已验证的 JDK 21、SDK Platform 37.0、Build Tools 36.0.0 和 Platform Tools，使用项目 Gradle Wrapper。

```bash
# macOS / Linux
./gradlew assembleRelease
```

```powershell
# Windows PowerShell
.\gradlew.bat assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`。调试包用 `assembleDebug`，位于 `app/build/outputs/apk/debug/app-debug.apk`。构建检查、ARM64 包及签名差异见 [GUIDE.md](GUIDE.md)。

## 安装到电视

可以通过 U 盘和电视文件管理器安装，按电视提示允许安装未知来源应用；也可以使用已授权连接的 ADB。

```bash
adb devices -l

# 同签名覆盖升级，保留应用数据
adb install -r app/build/outputs/apk/release/app-release.apk

# 启动
adb shell am start -n com.douyin.tv/.SplashActivity
```

同时连接多台设备时，在每条 ADB 命令中用 `adb -s SERIAL` 指定电视。

需要明确测试全新安装时：

```bash
# 卸载会清除登录、缓存和设置
adb uninstall com.douyin.tv
adb install app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.douyin.tv/.SplashActivity
```

覆盖升级要求包名和签名相同；默认 debug 包与 release 包的签名可能不同。签名不一致时先取得同签名 APK，不能把卸载作为保留资料的升级方式。

## 首次使用

1. 确认页面显示实际导航、视频卡片或明确的站点提示，而非只有光标和进度条。
2. 默认鼠标模式，用方向键移动，确认键点击登录入口；按网页提供的流程完成登录。
3. 新内核不继承旧系统 WebView 的登录资料。登录持久化还受站点会话是否有效影响。
4. 若停留空白，按菜单查看能否打开原生菜单，记录现象后按[排障指南](GUIDE.md)采集日志；不要反复清数据，以免破坏现场。

## 遥控器键位

| 按键 | 鼠标模式 | 播放模式 |
| --- | --- | --- |
| 方向键 | 短按微调 4 个物理像素，长按逐级加速 | 上下切视频，左右快进退 10 秒 |
| 确认 | 点击光标所在位置 | 播放 / 暂停 |
| 返回 | 先处理弹窗和播放，再回退网页；主页面提示后 2 秒内再次返回桌面，保留后台页面 | 同左 |
| 菜单 | 切换模式、上下滚动、快进退、重载页面 | 同左 |

进入全屏或视频弹层自动切到播放模式，退出后恢复进入前的模式和光标位置。快进退时原生进度条显示四秒；缺少相邻视频或视频尚不可操作时会提示。

自动播放、连播及可访问内容由站点状态决定。遇到按钮失效时请记录当前页面和操作模式，站点改版可能改变适配的控件结构。
