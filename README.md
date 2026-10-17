# 抖音电视版（DouyinTV）

面向 Android 11 及以上电视的网页客户端。默认入口使用 APK 内置的 GeckoView 浏览器，独立于电视系统 WebView；通过原生光标、菜单和播放进度条适配遥控器。

**当前为开发验证版本。** 用户已在 Android 11 VIDAA 电视上确认 1.0.21 日常使用基本顺畅；常规退出重开保留后台页面。首次冷启动及系统回收后的重新加载仍可能卡顿。1.0.19 曾在 VIDAA TV 卸载重装后停留在深色背景，根因尚未定位；1.0.20 已在独立空白资料包复现并修复首次分流到下载页的问题；历史空白、全新登录及完整播放仍待复验。历史覆盖安装时的正常页面和流畅性数据不能替代全新安装验收。

## 当前能力与边界

- 内置 GeckoView 158，启动不再切回系统 WebView。
- 原生圆点光标：方向键短按微调 4 个物理像素，长按逐级加速，确认键点击。
- 鼠标与播放两种模式；进入全屏或视频弹层自动切到播放模式，退出恢复原模式及光标位置。
- 播放模式支持上下切视频、左右快进退 10 秒、确认播放或暂停；快进退显示四秒原生进度条。
- 返回依次处理登录弹窗、播放器和网页历史；主页面第一次提示，2 秒内再次返回退出。
- 使用 Gecko 持久化资料保存浏览器状态；是否保持登录还受站点会话有效期影响。
- 屏外视频卡片跳过渲染，减少长列表布局和绘制负担；实际收益依页面状态而变。
- 首页双返回退到电视桌面并保留后台页面，重开复用已有文档，避免重复加载整站；系统回收后仍需重新加载。
- 视频弹层内限定主视频选择，并暂停被遮住的背景视频，避免误控制首页直播预览。

自动连播、可播放内容和登录校验由抖音网页及账号状态决定，当前不作为应用已完成的独立功能承诺。

## 版本与构建基线

| 项目 | 当前配置 |
| --- | --- |
| 应用版本 | 1.0.21 / versionCode 22 |
| 包名 | `com.douyin.tv` |
| 最低系统 / 目标系统 | Android 11 / API 30 |
| 编译 SDK | API 37，`platforms;android-37.0` |
| 构建工具 | Build Tools 36.0.0、Gradle 9.3.1、AGP 9.1.1 |
| Kotlin | 2.4.20，使用 AGP 内置 Kotlin |
| JDK | CI 使用 17；本地 JDK 21 已验证，字节码目标为 17 |
| 内置内核 | GeckoView `158.0.20261007201254` |

版本真源为 [app/build.gradle](app/build.gradle) 和 [扩展 manifest](app/src/main/assets/browser-extension/manifest.json)。实测设备为 VIDAA TV / MT9653 / Android 11 / ARM64，其他电视尚未完成同等验证。

## 编译与检查

首次配置 SDK、macOS / Linux / Windows 环境、签名、ABI、检查报告和常见错误见[完整编译说明](编译说明.md)。

安装上述 SDK，并配置 `ANDROID_HOME` 或本地 `local.properties`。首次构建需要访问 Google、Maven Central、Mozilla Maven 和 Gradle 分发服务。

macOS / Linux：

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
node --test tests/*.test.cjs
./gradlew assembleRelease
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
node --test tests/*.test.cjs
.\gradlew.bat assembleRelease
```

Node 测试使用 Node.js 内置测试运行器，现场验证使用 Node.js 24。APK 位于 `app/build/outputs/apk/debug/app-debug.apk` 或 `app/build/outputs/apk/release/app-release.apk`。默认包可能包含多个 ABI；ARM64 现场包的签名和构建方法见[详细指南](GUIDE.md)。

## 安装与操作

安装、同签名覆盖升级、全新卸载重装和遥控键位见[快速开始](QUICKSTART.md)。卸载会清除应用数据；同签名覆盖升级才保留既有资料。新内核资料与旧系统 WebView 不共享，需要重新登录。

| 按键 | 鼠标模式 | 播放模式 |
| --- | --- | --- |
| 上 / 下 / 左 / 右 | 移动光标，短按微调、长按加速 | 上下切视频，左右快退 / 快进 10 秒 |
| 确认 | 点击光标位置 | 播放 / 暂停 |
| 返回 | 关闭弹窗、退出播放或回上一页；主页面连续两次退出 | 同左 |
| 菜单 | 切换模式、上下滚动、快进退、重载 | 同左 |

## 验证状态

遥控移动和点击手感已获用户确认；登录成功、重载后资料保留，以及视频切换、快进和返回恢复已有实机记录。静态列表曾达到约 60 fps，但启动采样仍约 4–5 fps；这些属于不同页面阶段。**卸载重装后的空白问题及全流程流畅性验收仍未通过。**

当前有 39 项 Node 测试和 12 项 Android 单元测试；各版本的构建、lint 及实机结果见诊断报告，它们不证明所有电视、登录或全新安装场景可用。

## 文档与发布

- [快速开始](QUICKSTART.md)：安装、键位和首次使用。
- [架构说明](架构说明.md)：系统边界、模块、启动和生命周期。
- [技术设计](技术设计.md)：输入、消息协议、状态和诊断实现。
- [编译说明](编译说明.md)：从环境准备到产物校验的完整步骤。
- [编译、安装与排障指南](GUIDE.md)：签名、ADB、浏览器与诊断。
- [改造与验收状态](PROJECT_SUMMARY.md)：模块职责、已验证项和剩余工作。
- [电视流畅性诊断与内置内核验证](电视流畅性诊断与内置内核验证.md)：测试条件、数据与结论边界。

现有 GitHub Actions 在推送 `main` / `master` 或手动执行时会自动递增版本并发布 GitHub Release。开发快照若尚未具备发布条件，应明确区分源码推送和正式发布；本次提交使用 `[skip ci]`，本地检查照常执行。

## 第三方组件与项目声明

GeckoView 的许可证和对应源码入口见 [第三方组件说明](app/src/main/assets/第三方组件说明.txt)。UA 中的 Chrome 83 是网站兼容标记，实际内核为 GeckoView 158；Android 平板身份与内核版本分别记录。

本项目原有声明：仅供学习交流使用，不得用于商业用途。抖音及相关商标归北京字节跳动科技有限公司所有。本应用与抖音、Mozilla、Firefox 没有官方关联。
