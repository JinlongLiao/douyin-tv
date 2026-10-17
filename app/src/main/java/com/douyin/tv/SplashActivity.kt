package com.douyin.tv

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity

/** 启动时隐藏系统栏，并立即进入内置浏览器，避免额外的启动等待。 */
class SplashActivity : AppCompatActivity() {

    /** 创建内容视图后启用 Android 11 沉浸模式，再转交启动参数并关闭启动页。 */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // 内容视图创建后再访问控制器，保证窗口的 DecorView 已经就绪。
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }

        // 统一进入 APK 内置内核；不再根据启动参数选择系统 WebView。
        startActivity(Intent(this, EmbeddedBrowserActivity::class.java).apply {
            putExtra("browser_user_agent", intent.getStringExtra("browser_user_agent"))
        })
        finish()
    }
}
