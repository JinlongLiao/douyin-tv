package com.douyin.tv

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.douyin.tv.ui.BrowserControlModeState
import com.douyin.tv.ui.BrowserPlaybackOverlay
import com.douyin.tv.ui.BrowserPointerView
import com.douyin.tv.utils.BrowserIdentity
import com.douyin.tv.utils.EmbeddedBrowserRuntime
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/**
 * 使用 APK 内置 GeckoView 提供独立浏览器会话，不读取电视系统的 Chromium WebView。
 * 方向和点击使用原生鼠标事件；播放状态和返回由当前会话的抖音顶层页面扩展提供。
 *
 * @see <a href="https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/web-extensions.html">GeckoView 内置扩展和双向消息接口</a>
 */
class EmbeddedBrowserActivity : AppCompatActivity() {
    /** 显示浏览器内容及承接 Android 输入法的原生视图。 */
    private lateinit var geckoView: GeckoView
    /** 当前 Activity 独占的页面会话，销毁 Activity 时关闭，持久化资料由运行时保留。 */
    private lateinit var geckoSession: GeckoSession
    /** 独立于网页主线程绘制的遥控鼠标光标。 */
    private lateinit var browserPointerView: BrowserPointerView
    /** 快进快退时显示时间与比例的原生控制条。 */
    private lateinit var browserPlaybackOverlay: BrowserPlaybackOverlay
    /** 当前模式及进入播放器前的模式快照，仅在主线程更新。 */
    private val browserControlModeState = BrowserControlModeState()
    /** 是否已由扩展确认网站全屏播放弹层可见。 */
    private var isPageVideoModalActive = false
    /** 扩展确认的登录弹窗状态，包括仍在加载的登录模块占位。 */
    private var hasPageLoginDialog = false
    /** 当前文档是否至少收到一次弹层状态快照，未知时保持网页返回校验。 */
    private var isPageOverlayStateKnown = false
    /** 浏览器导航回调确认的首页位置，不包含仍带 modal_id 的播放器。 */
    private var isRootBrowserLocation = false
    /** 当前方向键是否操作视频；默认鼠标模式，进入播放器时自动切换。 */
    private val playbackControlMode: Boolean
        get() = browserControlModeState.playbackControlMode
    /** 浏览器最近确认的播放位置，单位秒，用于按键时立即展示控制条。 */
    private var mediaPositionSeconds = 0.0
    /** 浏览器最近确认的总时长，单位秒；0 表示未知或尚未加载。 */
    private var mediaDurationSeconds = 0.0
    /** 最近一次发给网页的悬停时间，单位毫秒；移动光标时至多每 80 ms 通知一次。 */
    private var lastPointerHoverTime = 0L
    /** 页面加载进度，不拦截遥控器焦点或覆盖已显示的网页。 */
    private lateinit var progressBar: ProgressBar
    /** 当前顶层文档的控制通道；页面跳转或断开时立即失效。 */
    private var remoteControlPort: WebExtension.Port? = null
    /** 浏览器历史回退能力，只在主线程通过导航回调更新。 */
    private var canNavigateBack = false
    /** Gecko 原生全屏状态；返回键先退出全屏再处理页面历史。 */
    private var isVideoFullscreen = false
    /** 最近消费的方向键，用于允许不同方向快速切换。 */
    private var lastDirectionCode = KeyEvent.KEYCODE_UNKNOWN
    /** 单调递增的播放/返回命令序号，帮助诊断按键排队，不复用页面脚本执行结果。 */
    private var commandSequence = 0L
    /** 当前文档尚未确认的返回命令序号，0 表示空闲；防止繁忙时连续返回先关弹窗再误退。 */
    private var pendingBackCommandSequence = 0L
    /** 主页面首次提示退出的单调时间，单位毫秒；0 表示尚未提示或已被其他操作取消。 */
    private var rootExitHintTime = 0L
    /** 当前返回请求发出时刻，单位毫秒；用于识别网页繁忙时两秒内连续按下的第二次返回。 */
    private var pendingBackRequestTime = 0L
    /** 返回结果未到达时是否又按了一次；仅首个结果确认为主页面时才允许退出。 */
    private var repeatedBackWhilePending = false
    /** 当前操作提示，后一个提示会取消前一个，避免退出提示排在启动说明之后。 */
    private var browserHintToast: Toast? = null
    /** 标记是否允许将无账号内容的性能采样写入本地调试缓存。 */
    private val isDebugBuild: Boolean
        get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** 初始化原生容器、会话和内置扩展；扩展安装成功后才加载首页。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        geckoView = GeckoView(this).apply {
            setBackgroundColor(Color.rgb(22, 24, 35))
            coverUntilFirstPaint(Color.rgb(22, 24, 35))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        val root = FrameLayout(this)
        root.addView(geckoView, FrameLayout.LayoutParams(-1, -1))
        root.addView(progressBar, FrameLayout.LayoutParams(-1, (3 * resources.displayMetrics.density).toInt()))
        browserPointerView = BrowserPointerView(this).apply {
            onPointerMoved = { _, _ ->
                val now = SystemClock.uptimeMillis()
                if (::geckoSession.isInitialized && now - lastPointerHoverTime >= 80) {
                    lastPointerHoverTime = now
                    dispatchBrowserPointerEvent(MotionEvent.ACTION_HOVER_MOVE)
                }
            }
        }
        root.addView(browserPointerView, FrameLayout.LayoutParams(-1, -1))
        browserPlaybackOverlay = BrowserPlaybackOverlay(this)
        val overlayMargin = (24 * resources.displayMetrics.density).toInt()
        root.addView(browserPlaybackOverlay, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(overlayMargin, 0, overlayMargin, overlayMargin)
        })
        setContentView(root)
        // Android 11 必须先创建 DecorView，才能取得窗口 Insets 控制器。
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        initializeEmbeddedSession()
        showBrowserHint("鼠标模式：方向键移动，确认点击；菜单键切换播放模式", Toast.LENGTH_LONG)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.uptimeMillis()
                if (rootExitHintTime != 0L && now - rootExitHintTime <= 2000 &&
                    !isVideoFullscreen && !isPageVideoModalActive && !hasPageLoginDialog) {
                    Log.i(TAG, "Root exit confirmed")
                    finish()
                    return
                }
                rootExitHintTime = 0L
                Log.i(TAG, "Gecko back requested: fullscreen=$isVideoFullscreen, connected=${remoteControlPort != null}")
                if (isVideoFullscreen) {
                    geckoSession.exitFullScreen()
                    sendRemoteCommand("exit-video")
                } else if (isRootBrowserLocation && isPageOverlayStateKnown && !isPageVideoModalActive && !hasPageLoginDialog) {
                    navigateBrowserBack(atRootPage = true)
                } else if (!sendRemoteCommand("back")) {
                    navigateBrowserBack()
                }
            }
        })
    }

    /**
     * 先注册导航与控制委托，再打开持久化会话、配置固定 UA、安装扩展，最后请求网页。
     * 任一初始化阶段失败都会保留完整异常日志并显示错误，不自动切换到旧内核。
     */
    private fun initializeEmbeddedSession() {
        val runtime = EmbeddedBrowserRuntime.getGeckoRuntime(this)
        val settings = GeckoSessionSettings.Builder()
            // 使用默认持久化资料；静态资源按服务端缓存头复用并验证更新，保留登录状态。
            .usePrivateMode(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            .build()
        geckoSession = GeckoSession(settings)
        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            /** 导航及 SPA 位置变化用于区分首页和播放器，不等待网页按键队列才显示首页提示。 */
            override fun onLocationChange(session: GeckoSession, url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                val location = url?.let { Uri.parse(it) }
                isRootBrowserLocation = (location?.host == "www.douyin.com" || location?.host == "douyin.com") &&
                    (location.path == "/jingxuan" || location.path == "/") &&
                    !location.queryParameterNames.contains("modal_id")
                rootExitHintTime = 0L
            }

            // 网站用新窗口打开视频时，继续使用当前电视会话，保留正常的返回历史。
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val scheme = Uri.parse(uri).scheme
                if (scheme == "https" || scheme == "http") {
                    Log.i(TAG, "Gecko open link in current session: $uri")
                    session.loadUri(uri)
                }
                return null
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                canNavigateBack = canGoBack
            }
        }
        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                // 新文档首帧绘制前沿用深色底，避免页面切换出现白色闪屏。
                geckoView.coverUntilFirstPaint(Color.rgb(22, 24, 35))
                remoteControlPort = null
                pendingBackCommandSequence = 0L
                rootExitHintTime = 0L
                isPageVideoModalActive = false
                hasPageLoginDialog = false
                isPageOverlayStateKnown = false
                updateBrowserPlaybackControlMode()
                progressBar.visibility = View.VISIBLE
                Log.i(TAG, "Gecko page start: $url")
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                progressBar.progress = progress
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                progressBar.visibility = View.GONE
                Log.i(TAG, "Gecko page stop: success=$success")
            }
        }
        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                isVideoFullscreen = fullScreen
                updateBrowserPlaybackControlMode()
            }

            override fun onCrash(session: GeckoSession) {
                Log.e(TAG, "Gecko content process crashed")
                showBrowserHint("浏览器进程异常，请重新打开应用", Toast.LENGTH_LONG)
            }
        }
        geckoSession.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                val autoplay = perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                    perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                return if (autoplay) {
                    GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                } else {
                    null
                }
            }
        }
        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)
        geckoView.requestFocus()
        // UA 已由兼容性策略固定，无需等待内容进程返回不会使用的默认 UA。
        val comparisonUserAgent = if (isDebugBuild) intent.getStringExtra("browser_user_agent") else null
        geckoSession.settings.userAgentOverride = comparisonUserAgent ?: BrowserIdentity.createDouyinTabletUserAgent()
        EmbeddedBrowserRuntime.getRemoteExtension(this).accept(extensionInstalled@{ extension ->
            if (extension == null) {
                reportBrowserInitializationFailure("安装遥控器扩展", IllegalStateException("Gecko returned no extension"))
                return@extensionInstalled
            }
            geckoSession.webExtensionController.setMessageDelegate(extension, createRemoteMessageDelegate(), NATIVE_APP)
            geckoSession.loadUri(HOME_URL)
        }, { error -> reportBrowserInitializationFailure("安装遥控器扩展", error ?: IllegalStateException("Gecko extension installation failed without a cause")) })
    }

    /**
     * 仅允许同一会话的抖音顶层页面建立控制通道；扩展不接收或执行任意代码。
     *
     * @return 负责检查来源及接入控制通道的委托。
     */
    private fun createRemoteMessageDelegate(): WebExtension.MessageDelegate {
        return object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                val host = Uri.parse(port.sender.url).host.orEmpty()
                if (port.sender.session !== geckoSession || !port.sender.isTopLevel ||
                    (host != "douyin.com" && !host.endsWith(".douyin.com"))) {
                    port.disconnect()
                    return
                }
                remoteControlPort = port
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        if (remoteControlPort !== port || message !is JSONObject) return
                        receiveBrowserMessage(message)
                    }

                    override fun onDisconnect(port: WebExtension.Port) {
                        if (remoteControlPort === port) {
                            remoteControlPort = null
                            pendingBackCommandSequence = 0L
                        }
                    }
                })
            }
        }
    }

    /** 根据封闭消息类型处理返回、输入法、诊断与异常；不读取页面账号输入值。 */
    private fun receiveBrowserMessage(message: JSONObject) {
        when (message.optString("type")) {
            "ready" -> {
                Log.i(TAG, "Gecko remote ready: $message")
                if (isDebugBuild) sendRemoteCommand("probe")
            }
            "result" -> {
                Log.i(TAG, "Gecko remote command: $message")
                val repeatedRootBack = message.optLong("sequence") == pendingBackCommandSequence && repeatedBackWhilePending
                if (message.optLong("sequence") == pendingBackCommandSequence) {
                    pendingBackCommandSequence = 0L
                    repeatedBackWhilePending = false
                }
                if (message.optString("result") == "input-focused") {
                    geckoView.post {
                        getSystemService(InputMethodManager::class.java).showSoftInput(geckoView, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                if (message.optString("result") == "video-modal-closed") {
                    isPageVideoModalActive = false
                    updateBrowserPlaybackControlMode()
                }
                if (message.optString("result") == "browser-back") navigateBrowserBack(repeatedRootBack)
                if (message.optString("result") == "dialog-closed") {
                    hasPageLoginDialog = false
                    rootExitHintTime = 0L
                }
                if (message.optString("result") == "root-back") {
                    if (hasPageLoginDialog) showBrowserHint("登录界面正在加载，请稍候再返回")
                    else navigateBrowserBack(repeatedRootBack, true)
                }
                if (message.optString("result") == "video-close-not-found") {
                    showBrowserHint("请用光标点击播放器左上角返回；已保留当前播放界面", Toast.LENGTH_LONG)
                }
                if (message.optString("result") in arrayOf("video-switch-not-found", "video-not-found", "video-not-seekable")) {
                    showBrowserHint("当前页面没有可操作的视频或相邻视频，请切回鼠标模式选择", Toast.LENGTH_SHORT)
                }
            }
            "performance" -> {
                Log.i(TAG, "Gecko performance: $message")
                if (isDebugBuild) {
                    cacheDir.resolve("browser-performance.json").writeText(message.toString())
                }
            }
            "playback-context" -> {
                isPageOverlayStateKnown = true
                isPageVideoModalActive = message.optBoolean("active")
                hasPageLoginDialog = message.optBoolean("dialog")
                if (isPageVideoModalActive || hasPageLoginDialog) rootExitHintTime = 0L
                updateBrowserPlaybackControlMode()
            }
            "media-state" -> {
                mediaPositionSeconds = message.optDouble("position", 0.0)
                mediaDurationSeconds = message.optDouble("duration", 0.0)
                browserPlaybackOverlay.updateMediaProgress(mediaPositionSeconds, mediaDurationSeconds, message.optBoolean("reveal"))
            }
            "error" -> {
                if (message.optLong("sequence") == pendingBackCommandSequence) {
                    pendingBackCommandSequence = 0L
                }
                Log.e(TAG, "Gecko remote error: $message")
            }
        }
    }

    /**
     * 弹窗和全屏均未消费返回后，先回退历史；主页面首次提示，连续第二次才退出。
     * @param repeatedRootBack 网页繁忙期间是否已收到两秒内的第二次返回，仅主页面可以消费该退出意图。
     * @param atRootPage 扩展是否已确认当前为首页，避免 SPA 残留历史绕过首页二次返回规则。
     */
    private fun navigateBrowserBack(repeatedRootBack: Boolean = false, atRootPage: Boolean = false) {
        if (canNavigateBack && !atRootPage) {
            rootExitHintTime = 0L
            geckoSession.goBack()
        } else {
            if (repeatedRootBack) {
                Log.i(TAG, "Root exit confirmed after queued double back")
                finish()
                return
            }
            rootExitHintTime = SystemClock.uptimeMillis()
            Log.i(TAG, "Root exit hint shown")
            showBrowserHint("再按一次返回退出", Toast.LENGTH_SHORT)
        }
    }

    /**
     * 将一条封闭遥控命令投递到当前文档；断开期间不缓存按键，避免新页面收到旧操作。
     *
     * @param action 约定的导航、确认、返回、登录或诊断命令。
     * @param dx 水平方向，只接受调用方生成的 -1、0、1。
     * @param dy 垂直方向，只接受调用方生成的 -1、0、1。
     * @return 已投递命令或已合并到未完成的返回请求时为 true；通道不存在时为 false。
     */
    private fun sendRemoteCommand(action: String, dx: Int = 0, dy: Int = 0): Boolean {
        val port = remoteControlPort
        if (port == null) {
            if (action == "seek" || action == "switch-video" || action == "toggle-playback") {
                showBrowserHint("页面正在加载，请稍候再操作播放", Toast.LENGTH_SHORT)
            }
            return false
        }
        // 控制条先显示最近确认的时间，网页完成快进后再用真实新位置更新，避免界面等待 JS。
        if (action == "seek") browserPlaybackOverlay.updateMediaProgress(mediaPositionSeconds, mediaDurationSeconds, true)
        // 返回结果到达前只保留一个请求，不按固定时间吞键；完成后下一次返回可正常执行。
        if (action == "back" && pendingBackCommandSequence != 0L) {
            repeatedBackWhilePending = SystemClock.uptimeMillis() - pendingBackRequestTime <= 2000
            Log.i(TAG, "Gecko back already pending: sequence=$pendingBackCommandSequence, repeated=$repeatedBackWhilePending")
            return true
        }
        if (action == "back") {
            pendingBackCommandSequence = commandSequence + 1
            pendingBackRequestTime = SystemClock.uptimeMillis()
            repeatedBackWhilePending = false
        }
        port.postMessage(JSONObject().put("action", action).put("dx", dx).put("dy", dy)
            .put("sequence", ++commandSequence).put("sentAt", System.currentTimeMillis()))
        return true
    }

    /** 拦截方向、确认与菜单；返回交给 AndroidX，音量等系统按键保持系统处理。 */
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 只有连续返回才允许退出，任何其他实体键操作都取消上一次主页面退出提示。
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) rootExitHintTime = 0L
        // Android 11 电视使用实体返回键；先交给统一回退回调，避免 GeckoView 默认键处理退出页面。
        // 触屏手势返回仍由 AndroidX 回调处理，不在这里模拟手势。
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onBackPressedDispatcher.onBackPressed()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                sendRemoteCommand("seek", if (event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 1 else -1)
            }
            return true
        }
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            else -> null
        }
        val confirm = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        val menu = event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_SETTINGS
        if (direction == null && !confirm && !menu) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) {
            if (direction != null && event.keyCode == lastDirectionCode) browserPointerView.stopPointerMovement()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (direction != null) {
            lastDirectionCode = event.keyCode
            if (playbackControlMode) {
                if (event.repeatCount == 0) {
                    if (direction.first != 0) sendRemoteCommand("seek", direction.first)
                    else sendRemoteCommand("switch-video", dy = direction.second)
                }
            } else {
                browserPointerView.startPointerMovement(direction.first, direction.second)
            }
        } else if (event.repeatCount == 0) {
            if (menu) {
                browserPointerView.stopPointerMovement()
                showBrowserMenu()
            } else if (playbackControlMode) {
                sendRemoteCommand("toggle-playback")
            } else {
                browserPointerView.stopPointerMovement()
                dispatchBrowserPointerEvent(MotionEvent.ACTION_HOVER_MOVE)
                browserPointerView.showPointerClickFeedback()
                val downTime = SystemClock.uptimeMillis()
                dispatchBrowserPointerEvent(MotionEvent.ACTION_DOWN, downTime)
                dispatchBrowserPointerEvent(MotionEvent.ACTION_UP, downTime)
            }
        }
        return true
    }

    /**
     * 将遥控点击作为浏览器原生鼠标输入投递，支持 iframe、链接和输入框，不调用 DOM click。
     * 坐标与 GeckoView 物理像素一致；事件在主线程发送后立即回收。
     * @param action 鼠标悬停、按下或抬起动作。
     * @param downTime 同一次按下和抬起共用的单调时间，单位毫秒。
     */
    private fun dispatchBrowserPointerEvent(action: Int, downTime: Long = SystemClock.uptimeMillis()) {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = browserPointerView.pointerX
            y = browserPointerView.pointerY
            pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f
            size = 1f
        }
        val buttonState = if (action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0
        val motionEvent = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
            arrayOf(properties), arrayOf(coordinates), 0, buttonState, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0)
        try {
            if (action == MotionEvent.ACTION_HOVER_MOVE) geckoView.onGenericMotionEvent(motionEvent)
            else geckoView.onTouchEvent(motionEvent)
        } finally {
            motionEvent.recycle()
        }
    }

    /** 使用原生菜单切换鼠标/播放模式并提供滚动、快进退和重载，网页繁忙时菜单仍可响应。 */
    private fun showBrowserMenu() {
        val items = mutableListOf(
            if (playbackControlMode) "切换到鼠标模式" else "切换到播放模式",
            "向下滚动", "向上滚动", "快退 10 秒", "快进 10 秒", "重新加载页面")
        if (isDebugBuild) items.add("诊断：采样当前页面")
        AlertDialog.Builder(this, R.style.ThemeOverlay_DouyinTV_Menu).setTitle(if (playbackControlMode) "播放模式" else "鼠标模式")
            .setAdapter(ArrayAdapter(this, R.layout.tv_menu_item, android.R.id.text1, items)) { _, index ->
                when (index) {
                    0 -> {
                        browserControlModeState.toggleBrowserControlMode()
                        browserPointerView.visibility = if (playbackControlMode) View.GONE else View.VISIBLE
                        showBrowserHint(if (playbackControlMode)
                            "播放模式：上下切视频，左右快进退，确认播放/暂停" else
                            "鼠标模式：方向键移动，确认点击", Toast.LENGTH_LONG)
                    }
                    1 -> dispatchBrowserScroll(-6f)
                    2 -> dispatchBrowserScroll(6f)
                    3 -> sendRemoteCommand("seek", -1)
                    4 -> sendRemoteCommand("seek", 1)
                    5 -> geckoSession.reload()
                    6 -> sendRemoteCommand("probe")
                }
            }.show()
    }

    /** 合并原生全屏与网页播放弹层状态；进入时保存模式，退出后恢复模式及原光标坐标。 */
    private fun updateBrowserPlaybackControlMode() {
        val previous = playbackControlMode
        browserControlModeState.updateBrowserPlaybackContext(isVideoFullscreen || isPageVideoModalActive)
        browserPointerView.stopPointerMovement()
        browserPointerView.visibility = if (playbackControlMode) View.GONE else View.VISIBLE
        if (previous != playbackControlMode) {
            Log.i(TAG, "Browser control mode changed: playback=$playbackControlMode, nativeFullscreen=$isVideoFullscreen, videoModal=$isPageVideoModalActive")
        }
    }

    /**
     * 向光标所在网页区域发送原生鼠标滚轮，支持列表与弹窗内部滚动。
     * @param verticalSteps 垂直滚轮步数，正数向上、负数向下，由浏览器处理滚动距离。
     */
    private fun dispatchBrowserScroll(verticalSteps: Float) {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = browserPointerView.pointerX
            y = browserPointerView.pointerY
            setAxisValue(MotionEvent.AXIS_VSCROLL, verticalSteps)
        }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
            arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try { geckoView.onGenericMotionEvent(event) } finally { event.recycle() }
    }

    /**
     * 立即替换当前操作提示，避免遥控器提示在系统 Toast 队列中累积。
     * @param message 本次操作的提示文案。
     * @param duration Android Toast 显示时长常量。
     */
    private fun showBrowserHint(message: String, duration: Int = Toast.LENGTH_SHORT) {
        browserHintToast?.cancel()
        browserHintToast = Toast.makeText(this, message, duration).also { it.show() }
    }

    /** 初始化失败时记录具体阶段和完整异常，保留现场供诊断，不进行无限重启。 */
    private fun reportBrowserInitializationFailure(stage: String, error: Throwable) {
        Log.e(TAG, "Gecko initialization failed: stage=$stage", error)
        showBrowserHint("$stage 失败：${error.message}", Toast.LENGTH_LONG)
        progressBar.visibility = View.GONE
    }

    /** Activity 隐藏时暂停会话活动，避免后台浏览继续占用视频与页面资源。 */
    override fun onPause() {
        if (::browserPointerView.isInitialized) browserPointerView.stopPointerMovement()
        Log.i(TAG, "Gecko activity paused")
        if (::geckoSession.isInitialized) geckoSession.setActive(false)
        super.onPause()
    }

    /** Activity 恢复时重新激活当前会话，不重新加载网页或清除资料。 */
    override fun onResume() {
        super.onResume()
        if (::geckoSession.isInitialized) geckoSession.setActive(true)
        Log.i(TAG, "Gecko activity resumed")
    }

    /** 关闭当前会话和消息通道，保留进程运行时及磁盘上的登录、缓存资料。 */
    override fun onDestroy() {
        browserHintToast?.cancel()
        remoteControlPort?.disconnect()
        remoteControlPort = null
        if (::geckoSession.isInitialized) geckoSession.close()
        super.onDestroy()
    }

    companion object {
        /** Java 和扩展双方约定的本地消息目标名。 */
        private const val NATIVE_APP = "douyin_tv"
        /** 首次启动和重新进入时的业务首页，浏览器资料独立持久化。 */
        private const val HOME_URL = "https://www.douyin.com/jingxuan?from_nav=1"
        /** 统一定位内置内核加载和按键消息的日志标签。 */
        private const val TAG = "DouyinGecko"
    }
}
