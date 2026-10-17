package com.douyin.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.douyin.tv.utils.CookieHelper
import com.douyin.tv.utils.WebViewOptimizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingText: TextView
    /** 保存全屏回调控制器，兼容 API 21，避免调用 API 26 才提供的 getter。 */
    private lateinit var douyinWebChromeClient: DouyinWebChromeClient
    private lateinit var cookieManager: CookieManager
    private lateinit var prefs: SharedPreferences

    private val douyinUrl = "https://www.douyin.com/jingxuan?from_nav=1"
    private val tag = "DouyinTV"

    // 按键控制
    // 仅限制同一按键的长按重复，不吞掉快速切换方向或返回。
    private var lastRemoteKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var lastKeyEventTime = 0L
    private val KEY_DEBOUNCE_MS = 120L

    // 视频控制
    private var isVideoPlaying = false
    private var lastAutoSwitchTime = 0L
    private var videoEndedCount = 0

    // 页面状态
    private var webViewReady = false
    private var pageLoadAttempts = 0
    private val MAX_LOAD_ATTEMPTS = 3

    // 验证码滑动
    private var captchaDragging = false

    // 登录状态监控
    private var loginCheckJob: Job? = null

    private var overlayId = -1
    private var overlay: FrameLayout? = null
    private var customVideoView: View? = null
    private var customVideoViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isPseudoVideoFullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullScreen()
        try {
            setupLayout()
            setupWebView()
            setupRemoteBackNavigation()
            setupCookieManager()
            setupLoadingUI()
            prefs = getSharedPreferences("douyin_tv_prefs", Context.MODE_PRIVATE)
            CookieHelper.restoreCookies(this, douyinUrl)
            webView.loadUrl(douyinUrl)
            showLoadingMessage("正在加载抖音,请稍候...")
            lifecycleScope.launch {
                delay(5000)
                hideLoadingOverlay()
            }
        } catch (e: Exception) {
            Log.e(tag, "onCreate error", e)
            showErrorAndRestart("应用启动失败: ${e.message}")
        }
    }

    private fun setupFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.decorView.windowInsetsController
            controller?.let {
                it.hide(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** 创建叠放的网页和进度条，加载遮罩由 setupLoadingUI 加入同一容器。 */
    private fun setupLayout() {
        // 网页、进度条和加载遮罩叠放，避免遮罩作为纵向子项挤占网页高度。
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // 让 WebView 稳定持有 TV 焦点，再把遥控器按键统一交给 Activity 处理。
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setOnKeyListener { _, keyCode, event ->
                handleRemoteKey(keyCode, event)
            }
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (3 * resources.displayMetrics.density).toInt(),
                Gravity.TOP
            )
            max = 100
            visibility = View.GONE
            progressDrawable?.setTint(Color.parseColor("#FE2C55"))
        }

        rootLayout.addView(webView)
        rootLayout.addView(progressBar)
        setContentView(rootLayout)
        webView.requestFocus()
    }

    /** 在网页上方显示居中的加载提示，隐藏时不改变网页尺寸。 */
    private fun setupLoadingUI() {
        overlayId = View.generateViewId()
        overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#CC000000"))
            id = overlayId
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            gravity = android.view.Gravity.CENTER
        }

        val pb = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FE2C55"))
        }

        loadingText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
            text = "正在加载..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }

        content.addView(pb)
        content.addView(loadingText)
        overlay?.addView(content)

        (rootView as? ViewGroup)?.addView(overlay)
    }

    private fun hideLoadingOverlay() {
        runOnUiThread { overlay?.visibility = View.GONE }
    }

    private val rootView: View
        get() = (findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)

    private fun showLoadingMessage(message: String) {
        runOnUiThread { loadingText.text = message }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        try {
            // 调试构建允许通过 ADB 检查真实 DOM 和性能，发布构建不开放调试。
            WebView.setWebContentsDebuggingEnabled(
                applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            )
            WebViewOptimizer.optimizeForTV(webView, applicationContext)
            webView.webViewClient = DouyinWebViewClient()
            douyinWebChromeClient = DouyinWebChromeClient()
            webView.webChromeClient = douyinWebChromeClient
            webView.addJavascriptInterface(VideoJsInterface(), "AndroidInterface")
            setupTouchHandler()
        } catch (e: Exception) {
            Log.e(tag, "setupWebView error", e)
            throw RuntimeException("WebView初始化失败", e)
        }
    }

    private fun setupTouchHandler() {
        webView.setOnTouchListener { _, event ->
            try { handleTouchEvent(event) } catch (e: Exception) { Log.e(tag, "Touch event error", e) }
            false
        }
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { captchaDragging = true }
            MotionEvent.ACTION_MOVE -> {
                if (captchaDragging) {
                    val moveEvent = MotionEvent.obtain(
                        event.downTime, event.eventTime, MotionEvent.ACTION_MOVE,
                        event.x, event.y, event.metaState
                    )
                    webView.dispatchTouchEvent(moveEvent)
                    moveEvent.recycle()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { captchaDragging = false }
        }
        return false
    }

    private fun setupCookieManager() {
        try {
            cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        } catch (e: Exception) {
            Log.e(tag, "CookieManager setup error", e)
        }
    }

    inner class DouyinWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

        @RequiresApi(Build.VERSION_CODES.M)
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                Log.e(tag, "Page load error: ${error?.description}")
                pageLoadAttempts++
                if (pageLoadAttempts <= MAX_LOAD_ATTEMPTS) {
                    runOnUiThread {
                        showLoadingMessage("页面加载失败,正在重试...(${pageLoadAttempts}/$MAX_LOAD_ATTEMPTS)")
                        lifecycleScope.launch {
                            delay(2000L * pageLoadAttempts)
                            webView.reload()
                        }
                    }
                }
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            webViewReady = false
            pageLoadAttempts = 0
            showLoadingMessage("正在加载抖音...")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            webViewReady = true
            webView.requestFocus()
            hideLoadingOverlay()
            try {
                CookieHelper.saveCookies(this@MainActivity, douyinUrl)
                cookieManager.flush()
                injectAutoPlayScript()
                injectCaptchaSupport()
                injectLoginDetectionScript()
                injectTVOptimizationCSS()
                checkLoginStatus()
                initFocusEngine()
            } catch (e: Exception) {
                Log.e(tag, "onPageFinished error", e)
            }
        }
    }

    inner class DouyinWebChromeClient : WebChromeClient() {
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (customVideoView != null) {
                callback?.onCustomViewHidden()
                return
            }
            customVideoView = view
            customVideoViewCallback = callback
            webView.visibility = View.GONE
            (window.decorView as? FrameLayout)?.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            setupFullScreen()
        }

        override fun onHideCustomView() {
            val view = customVideoView ?: return
            (window.decorView as? FrameLayout)?.removeView(view)
            customVideoView = null
            customVideoViewCallback?.onCustomViewHidden()
            customVideoViewCallback = null
            isPseudoVideoFullscreen = false
            webView.visibility = View.VISIBLE
            webView.requestFocus()
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (newProgress < 100) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = newProgress
            } else {
                progressBar.visibility = View.GONE
            }
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            if (consoleMessage != null) {
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e(tag, "[WebView] ${consoleMessage.message()}")
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(tag, "[WebView] ${consoleMessage.message()}")
                    else -> Log.d(tag, "[WebView] ${consoleMessage.message()}")
                }
            }
            return true
        }
    }

    inner class VideoJsInterface {
        @JavascriptInterface
        fun onVideoEnded() { isVideoPlaying = false }

        @JavascriptInterface
        fun onVideoPlaying() { isVideoPlaying = true; videoEndedCount = 0 }

        @JavascriptInterface
        fun onVideoPaused() { isVideoPlaying = false }

        @JavascriptInterface
        fun onVideoFullscreenChanged(isFullscreen: Boolean) {
            isPseudoVideoFullscreen = isFullscreen
        }

        @JavascriptInterface
        fun onLoginStatusChanged(isLoggedIn: Boolean) {
            runOnUiThread {
                if (isLoggedIn) showLoadingMessage("登录成功,正在加载...")
                else Toast.makeText(this@MainActivity, "检测到登录弹窗,请使用手机扫码", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun injectCaptchaSupport() {
        val script = """
            (function() {
                // SPA 回调可能重复触发，同一文档只注册一组监听器和定时器。
                if (window.__tvCaptchaInstalled) return;
                window.__tvCaptchaInstalled = true;
                function createTouch(target, x, y) {
                    try {
                        return new Touch({
                            identifier: 0,
                            target: target,
                            clientX: x,
                            clientY: y,
                            pageX: x,
                            pageY: y,
                            screenX: x,
                            screenY: y
                        });
                    } catch(e) { return null; }
                }
                function dispatchTouch(target, type, x, y) {
                    try {
                        var touch = createTouch(target, x, y);
                        if (!touch) return;
                        var ev = new TouchEvent(type, {
                            touches: type === 'touchend' ? [] : [touch],
                            targetTouches: type === 'touchend' ? [] : [touch],
                            changedTouches: [touch],
                            bubbles: true,
                            cancelable: true
                        });
                        target.dispatchEvent(ev);
                    } catch(e) {}
                }
                function enableCaptchaSlider() {
                    const sliderBtn = document.querySelector('.secsdk_captcha_drag_button') ||
                                     document.querySelector('[class*="drag-button"]');
                    if (sliderBtn && !sliderBtn.hasAttribute('data-captcha-enabled')) {
                        sliderBtn.setAttribute('data-captcha-enabled', 'true');
                        sliderBtn.style.cursor = 'grab';
                        let isDragging = false;
                        sliderBtn.addEventListener('mousedown', function(e) {
                            isDragging = true;
                            dispatchTouch(sliderBtn, 'touchstart', e.clientX, e.clientY);
                        });
                        document.addEventListener('mousemove', function(e) {
                            if (isDragging) dispatchTouch(sliderBtn, 'touchmove', e.clientX, e.clientY);
                        });
                        document.addEventListener('mouseup', function() {
                            if (isDragging) {
                                isDragging = false;
                                dispatchTouch(sliderBtn, 'touchend', 0, 0);
                            }
                        });
                    }
                }
                setInterval(enableCaptchaSlider, 1000);
                enableCaptchaSlider();
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun injectAutoPlayScript() {
        val script = """
            (function() {
                // SPA 回调可能重复触发，同一文档只注册一组监听器和定时器。
                if (window.__tvAutoPlayInstalled) return;
                window.__tvAutoPlayInstalled = true;
                function ensureSoundEnabled(video) {
                    try {
                        video.muted = false;
                        if (isFinite(video.volume)) video.volume = 1.0;
                    } catch(e) {}
                }
                function monitorVideo() {
                    document.querySelectorAll('video').forEach(video => {
                        if (!video.hasAttribute('data-monitored')) {
                            video.setAttribute('data-monitored', 'true');
                            ensureSoundEnabled(video);
                            video.addEventListener('loadedmetadata', function() { ensureSoundEnabled(video); });
                            video.addEventListener('playing', function() {
                                try { AndroidInterface.onVideoPlaying(); } catch(e) {}
                            });
                            video.addEventListener('pause', function() {
                                try { AndroidInterface.onVideoPaused(); } catch(e) {}
                            });
                            video.addEventListener('ended', function() {
                                try { AndroidInterface.onVideoEnded(); } catch(e) {}
                                try {
                                    if (window.__tvFocus && window.__tvFocus.playNextVideo) {
                                        window.__tvFocus.playNextVideo(video);
                                    } else {
                                        window.dispatchEvent(new WheelEvent('wheel', { deltaY: window.innerHeight, bubbles:true, cancelable:true }));
                                        window.scrollBy({ top: window.innerHeight * 0.9, behavior: 'smooth' });
                                    }
                                } catch(e) {}
                            });
                            video.setAttribute('autoplay', 'true');
                            video.setAttribute('playsinline', 'true');
                            video.setAttribute('preload', 'metadata');
                        }
                    });
                }
                const observer = new MutationObserver(function() { monitorVideo(); });
                observer.observe(document.body, { childList: true, subtree: true });
                setInterval(monitorVideo, 5000);
                monitorVideo();
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun injectLoginDetectionScript() {
        val script = """
            (function() {
                // SPA 回调可能重复触发，同一文档只注册一组监听器和定时器。
                if (window.__tvLoginDetectionInstalled) return;
                window.__tvLoginDetectionInstalled = true;
                let hasNotifiedLogin = false;
                function checkLoginModal() {
                    const loginModal = document.querySelector('[class*="login-modal"]') ||
                                      document.querySelector('[class*="LoginModal"]') ||
                                      document.querySelector('[class*="loginDialog"]') ||
                                      document.querySelector('[data-e2e="login-modal"]');
                    if (loginModal && loginModal.offsetParent !== null && !hasNotifiedLogin) {
                        hasNotifiedLogin = true;
                        try { AndroidInterface.onLoginStatusChanged(false); } catch(e) {}
                    }
                }
                function checkLoginSuccess() {
                    const userAvatar = document.querySelector('[data-e2e="user-avatar"]') ||
                                      document.querySelector('[class*="user-avatar"]');
                    if (userAvatar) {
                        hasNotifiedLogin = false;
                        try { AndroidInterface.onLoginStatusChanged(true); } catch(e) {}
                    }
                }
                setTimeout(function() {
                    setInterval(checkLoginModal, 5000);
                    setInterval(checkLoginSuccess, 5000);
                }, 8000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun injectTVOptimizationCSS() {
        val css = """
            (function() {
                if (document.getElementById('tv-optimize-style')) return;
                const style = document.createElement('style');
                style.id = 'tv-optimize-style';
                style.innerHTML = `
                    * { cursor: none !important; }
                    *:focus { outline: none !important; }
                    video:-webkit-full-screen,
                    video:fullscreen {
                        width: 100vw !important;
                        height: 100vh !important;
                        object-fit: contain !important;
                        background: #000;
                    }
                    video.tv-pseudo-fullscreen-video {
                        position: fixed !important;
                        top: 0 !important;
                        left: 0 !important;
                        right: 0 !important;
                        bottom: 0 !important;
                        width: 100vw !important;
                        height: 100vh !important;
                        max-width: none !important;
                        max-height: none !important;
                        object-fit: contain !important;
                        background: #000 !important;
                        z-index: 2147483646 !important;
                    }
                    body.tv-video-fullscreen-active {
                        overflow: hidden !important;
                    }
                    body.tv-video-fullscreen-active #tv-hl-box {
                        display: none !important;
                    }
                `;
                document.head.appendChild(style);
            })();
        """.trimIndent()
        webView.evaluateJavascript(css, null)
    }

    /** 统一接管遥控器事件；未识别的按键继续交给系统。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return handleRemoteKey(event.keyCode, event) || super.dispatchKeyEvent(event)
    }

    /** 返回依次退出视频全屏、回退网页、关闭应用，同时支持系统返回回调。 */
    private fun setupRemoteBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "Boolean(window.__tvFocus && window.__tvFocus.dismissModal())"
                ) { dismissed ->
                    if (dismissed != "true" && !tryExitVideoFullscreen()) {
                        if (webView.canGoBack()) webView.goBack() else finish()
                    }
                }
            }
        })
    }

    /** 消费方向和媒体按键序列，同键长按按间隔节流；返回由系统回调处理。 */
    private fun handleRemoteKey(keyCode: Int, event: KeyEvent): Boolean {
        if (!isRemoteNavigationKey(keyCode)) return false
        if (event.action == KeyEvent.ACTION_UP) return true
        if (event.action != KeyEvent.ACTION_DOWN) return false
        // 返回交给 AndroidX 返回回调，不参与遥控器重复按键节流。
        // 确认、菜单和媒体动作只响应首次按下，避免长按反复开关弹窗或播放状态。
        if (event.repeatCount > 0 && keyCode !in listOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
            )) return true
        val currentTime = SystemClock.uptimeMillis()
        if (event.repeatCount > 0 && keyCode == lastRemoteKeyCode &&
            currentTime - lastKeyEventTime < KEY_DEBOUNCE_MS) return true
        lastRemoteKeyCode = keyCode
        lastKeyEventTime = currentTime
        return handleRemoteKeyDown(keyCode)
    }

    /** 识别由应用处理的方向、确认、菜单和媒体键。 */
    private fun isRemoteNavigationKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> true
            else -> false
        }
    }

    /** 把已通过节流的遥控器按下事件映射到网页操作。 */
    private fun handleRemoteKeyDown(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { focusEngineNavigate(0, -1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { focusEngineNavigate(0, 1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { focusEngineNavigate(-1, 0); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { focusEngineNavigate(1, 0); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { focusEngineAction(); true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { handleMenuKey(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { toggleVideoPlayPause(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { playVideo(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { pauseVideo(); true }
            else -> false
        }
    }

    /** 注入网页空间焦点引擎，跟踪动态控件并为遥控器提供可视高亮。 */
    private fun initFocusEngine() {
        if (!webViewReady) return
        val script = assets.open("browser-extension/focus-engine.js").bufferedReader().use {
            it.readText()
        }

        webView.evaluateJavascript(script) { result ->
            Log.d(tag, "Focus engine init: $result")
        }
    }

    private fun focusEngineNavigate(dx: Int, dy: Int) {
        val script = """
            (function() {
                if (window.__tvFocus && window.__tvFocus.hasModal()) {
                    window.__tvFocus.move($dx,$dy);
                    return 'dialog-focus';
                }
                var fullscreenVideo = document.querySelector('video.tv-pseudo-fullscreen-video');
                if (fullscreenVideo) {
                    if ($dx !== 0) {
                        try {
                            fullscreenVideo.currentTime = Math.max(0, Math.min((fullscreenVideo.duration || fullscreenVideo.currentTime + 15), fullscreenVideo.currentTime + ($dx > 0 ? 10 : -10)));
                            return 'fullscreen-seek';
                        } catch(e) {}
                    }
                    if ($dy > 0 && window.__tvFocus && window.__tvFocus.playNextVideo) {
                        return window.__tvFocus.playNextVideo(fullscreenVideo);
                    }
                    if ($dy < 0) {
                        window.scrollBy({ top: -window.innerHeight * 0.85, behavior: 'auto' });
                        return 'fullscreen-prev-scroll';
                    }
                }
                function primaryVideo() {
                    var best = null, bestScore = 0;
                    document.querySelectorAll('video').forEach(function(v) {
                        var r = v.getBoundingClientRect();
                        if (r.width < 80 || r.height < 80 || r.bottom < 0 || r.top > window.innerHeight) return;
                        var visibleW = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
                        var visibleH = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
                        var score = visibleW * visibleH;
                        if (score > bestScore) { bestScore = score; best = v; }
                    });
                    return best;
                }
                var href = location.href;
                var inVideoPage = href.indexOf('/video/') > -1 || href.indexOf('/note/') > -1;
                if (inVideoPage) {
                    var v = primaryVideo();
                    if (v && $dx !== 0) {
                        try {
                            v.currentTime = Math.max(0, Math.min((v.duration || v.currentTime + 15), v.currentTime + ($dx > 0 ? 10 : -10)));
                            return 'seek';
                        } catch(e) {}
                    }
                    if ($dy !== 0) {
                        window.dispatchEvent(new WheelEvent('wheel', { deltaY: $dy > 0 ? window.innerHeight : -window.innerHeight, bubbles:true, cancelable:true }));
                        window.scrollBy({ top: ($dy > 0 ? 1 : -1) * window.innerHeight * 0.9, behavior: 'smooth' });
                        return 'switch-video';
                    }
                }
                if (window.__tvFocus) {
                    window.__tvFocus.move($dx,$dy);
                } else {
                    window.scrollBy(0, ${if (dy > 0) "window.innerHeight*0.6" else "-window.innerHeight*0.6"});
                }
                return 'focus';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun focusEngineAction() {
        val script = """
            (function() {
                if (window.__tvFocus && window.__tvFocus.hasModal()) {
                    window.__tvFocus.activate();
                    return 'dialog-action';
                }
                var fullscreenVideo = document.querySelector('video.tv-pseudo-fullscreen-video');
                if (fullscreenVideo) {
                    try {
                        if (fullscreenVideo.paused) { fullscreenVideo.play(); return 'fullscreen-video-play'; }
                        fullscreenVideo.pause(); return 'fullscreen-video-pause';
                    } catch(e) {}
                }
                function primaryVideo() {
                    var best = null, bestScore = 0;
                    document.querySelectorAll('video').forEach(function(v) {
                        var r = v.getBoundingClientRect();
                        if (r.width < 80 || r.height < 80 || r.bottom < 0 || r.top > window.innerHeight) return;
                        var visibleW = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
                        var visibleH = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
                        var score = visibleW * visibleH;
                        if (score > bestScore) { bestScore = score; best = v; }
                    });
                    return best;
                }
                var href = window.location.href;
                if (href.indexOf('/video/') > -1 || href.indexOf('/note/') > -1) {
                    var v = primaryVideo();
                    if (v) {
                        try {
                            if (!document.fullscreenElement && !v.classList.contains('tv-pseudo-fullscreen-video')) {
                                try { v.muted = false; v.volume = 1; } catch(e) {}
                                try { v.play(); } catch(e) {}
                                try {
                                    if (v.requestFullscreen) v.requestFullscreen();
                                    else if (v.webkitRequestFullscreen) v.webkitRequestFullscreen();
                                } catch(e) {}
                                v.classList.add('tv-pseudo-fullscreen-video');
                                document.body.classList.add('tv-video-fullscreen-active');
                                try { AndroidInterface.onVideoFullscreenChanged(true); } catch(e) {}
                                return 'video-fullscreen';
                            }
                            if (v.paused) { v.play(); return 'video-play'; }
                            v.pause(); return 'video-pause';
                        } catch(e) {}
                    }
                }
                if (window.__tvFocus) {
                    var result = window.__tvFocus.activate();
                    return result;
                }
                // 如果已经在视频播放页，尝试全屏
                var href = window.location.href;
                if (href.indexOf('/video/') > -1 || href.indexOf('/note/') > -1) {
                    try {
                        if (!document.fullscreenElement) {
                            document.documentElement.requestFullscreen();
                            return 'fullscreen-entered';
                        } else {
                            document.exitFullscreen();
                            return 'fullscreen-exited';
                        }
                    } catch(e) {}
                }
                return 'no-engine';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            Log.d(tag, "Focus action: $result")
        }
    }

    private fun tryExitVideoFullscreen(): Boolean {
        if (customVideoView != null) {
            douyinWebChromeClient.onHideCustomView()
            return true
        }
        if (!isPseudoVideoFullscreen) return false
        val script = """
            (function() {
                var pseudo = document.querySelector('video.tv-pseudo-fullscreen-video');
                if (pseudo) {
                    pseudo.classList.remove('tv-pseudo-fullscreen-video');
                    document.body.classList.remove('tv-video-fullscreen-active');
                    try { AndroidInterface.onVideoFullscreenChanged(false); } catch(e) {}
                    return 'pseudo-exited';
                }
                if (document.fullscreenElement) {
                    document.exitFullscreen();
                    return 'exited';
                }
                return 'no-fullscreen';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
        isPseudoVideoFullscreen = false
        return true
    }

    private fun handleMenuKey() {
        val script = """
            (function() {
                const loginBtn = document.querySelector('[class*="login"]') ||
                                document.querySelector('[class*="Login"]') ||
                                document.querySelector('button[type="submit"]') ||
                                document.querySelector('[data-e2e="login"]');
                if (loginBtn) { loginBtn.click(); return 'clicked_login'; }
                const slider = document.querySelector('.secsdk_captcha_drag_button') ||
                              document.querySelector('[class*="drag-button"]') ||
                              document.querySelector('[class*="slider"]');
                if (slider) {
                    const rect = slider.getBoundingClientRect();
                    const startX = rect.left + rect.width / 2;
                    const startY = rect.top + rect.height / 2;
                    const endX = window.innerWidth - 100;
                    slider.dispatchEvent(new MouseEvent('mousedown', { clientX: startX, clientY: startY, bubbles: true }));
                    setTimeout(function() {
                        document.dispatchEvent(new MouseEvent('mousemove', { clientX: endX, clientY: startY, bubbles: true }));
                    }, 200);
                    setTimeout(function() {
                        document.dispatchEvent(new MouseEvent('mouseup', { clientX: endX, clientY: startY, bubbles: true }));
                    }, 500);
                    return 'auto_slide_captcha';
                }
                return 'no_action';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            Log.d(tag, "Menu key action: $result")
            runOnUiThread {
                when (result?.replace("\"", "")) {
                    "clicked_login" -> Toast.makeText(this, "已点击登录按钮", Toast.LENGTH_SHORT).show()
                    "auto_slide_captcha" -> Toast.makeText(this, "已自动滑动验证码", Toast.LENGTH_SHORT).show()
                    else -> {}
                }
            }
        }
    }

    private fun toggleVideoPlayPause() {
        val script = """
            (function() {
                function primaryVideo() {
                    var best = null, bestScore = 0;
                    document.querySelectorAll('video').forEach(function(v) {
                        var r = v.getBoundingClientRect();
                        if (r.width < 80 || r.height < 80 || r.bottom < 0 || r.top > window.innerHeight) return;
                        var visibleW = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
                        var visibleH = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
                        var score = visibleW * visibleH;
                        if (score > bestScore) { bestScore = score; best = v; }
                    });
                    return best;
                }
                const video = primaryVideo() || document.querySelector('video');
                if (video) { video.paused ? video.play() : video.pause(); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun playVideo() {
        webView.evaluateJavascript("(function() { const videos = Array.from(document.querySelectorAll('video')); const v = videos.sort(function(a,b){return (b.getBoundingClientRect().width*b.getBoundingClientRect().height)-(a.getBoundingClientRect().width*a.getBoundingClientRect().height);})[0]; if (v && v.paused) v.play(); })();", null)
    }

    private fun pauseVideo() {
        webView.evaluateJavascript("(function() { const videos = Array.from(document.querySelectorAll('video')); const v = videos.sort(function(a,b){return (b.getBoundingClientRect().width*b.getBoundingClientRect().height)-(a.getBoundingClientRect().width*a.getBoundingClientRect().height);})[0]; if (v && !v.paused) v.pause(); })();", null)
    }

    private fun checkLoginStatus() {
        lifecycleScope.launch {
            delay(3000)
            val isLoggedIn = CookieHelper.isLoggedIn(douyinUrl)
            if (!isLoggedIn) {
                Log.d(tag, "Not logged in, waiting for user to scan QR code")
                showLoadingMessage("请使用手机抖音扫码登录")
            } else {
                Log.d(tag, "Already logged in")
            }
        }
    }

    private fun showErrorAndRestart(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            delay(2000)
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            webView.onResume()
            webView.resumeTimers()
            webView.requestFocus()
        } catch (e: Exception) {
            Log.e(tag, "onResume error", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            webView.onPause()
            webView.pauseTimers()
            cookieManager.flush()
            CookieHelper.saveCookies(this, douyinUrl)
        } catch (e: Exception) {
            Log.e(tag, "onPause error", e)
        }
    }

    override fun onStop() {
        super.onStop()
        loginCheckJob?.cancel()
    }

    override fun onDestroy() {
        loginCheckJob?.cancel()
        try {
            webView.apply {
                loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
                clearHistory()
                // 保留磁盘资源缓存，避免下次启动重新下载网页脚本和图片。
                removeAllViews()
                destroy()
            }
        } catch (e: Exception) {
            Log.e(tag, "onDestroy error", e)
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }
}
