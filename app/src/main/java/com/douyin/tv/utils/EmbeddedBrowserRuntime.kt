package com.douyin.tv.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.MainThread
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

/** 持有进程唯一的内置浏览器运行时，Activity 重建时复用同一配置和持久化浏览器资料。 */
object EmbeddedBrowserRuntime {
    /** 仅在主线程创建和访问；随应用进程释放，不主动清除磁盘缓存或登录资料。 */
    private var geckoRuntime: GeckoRuntime? = null

    /** 仅主线程访问；成功安装的扩展随运行时复用，应用进程终止后失效。 */
    private var remoteExtension: WebExtension? = null

    /**
     * 返回当前进程已安装的遥控器扩展，避免每次进入 Activity 都重新安装。
     * 冷启动调试包仍重装 APK 内扩展，确保同版本脚本修改生效；正式包按扩展版本检查更新。
     * 仅缓存成功结果，安装失败可在下次进入时重试；调用方仍须为新会话绑定消息委托。
     *
     * @param context 用于取得唯一运行时和判断调试构建的上下文。
     * @return 扩展安装结果；失败由调用方记录并提示，不缓存失败结果。
     * @see <a href="https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebExtensionController.html">内置扩展安装与版本检查契约</a>
     */
    @MainThread
    fun getRemoteExtension(context: Context): GeckoResult<WebExtension> {
        remoteExtension?.let { return GeckoResult.fromValue(it) }
        val runtime = getGeckoRuntime(context)
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val installation = if (debug) {
            runtime.webExtensionController.installBuiltIn("resource://android/assets/browser-extension/")
        } else {
            runtime.webExtensionController.ensureBuiltIn("resource://android/assets/browser-extension/", "tv-remote@douyin-tv.local")
        }
        return installation.map { extension ->
            remoteExtension = extension
            extension
        }
    }

    /**
     * 创建或返回进程唯一运行时；调试能力只在可调试 APK 中开放。
     *
     * @param context 用于获取应用上下文和构建调试标记的 Android 上下文。
     * @return 已初始化且可供多个会话复用的运行时。
     * @see <a href="https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/geckoview-quick-start.html">GeckoView 运行时生命周期约定</a>
     */
    @MainThread
    fun getGeckoRuntime(context: Context): GeckoRuntime {
        geckoRuntime?.let { return it }
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val settings = GeckoRuntimeSettings.Builder()
            .remoteDebuggingEnabled(debug)
            .consoleOutput(true)
            // 电视试用 1.35 倍网页密度，并同步 DPI；1920 宽屏预期约为 1422 CSS 像素。
            // 由浏览器统一处理布局和输入坐标，避免 CSS 缩放导致点击区域与光标错位。
            .displayDensityOverride(1.35f)
            .displayDpiOverride(216)
            .build()
        return GeckoRuntime.create(context.applicationContext, settings).also { geckoRuntime = it }
    }
}
