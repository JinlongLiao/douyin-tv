package com.douyin.tv.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

/**
 * 快进快退时短暂显示的原生播放进度；不依赖网站是否显示播放器控制条。
 * @param context 当前 Activity 的视图上下文。
 */
class BrowserPlaybackOverlay(context: Context) : LinearLayout(context) {
    /** 当前时间和总时长，秒转换为分秒或时分秒显示。 */
    private val playbackTimeTextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
    }
    /** 播放比例，0–1000；纯显示，不占用网页光标点击。 */
    private val playbackProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(0xFFFE2C55.toInt())
        progressBackgroundTintList = ColorStateList.valueOf(0xFF4B4D59.toInt())
    }
    /** 最后一次请求显示后四秒隐藏，后续时间更新不延长显示期限。 */
    private val hidePlaybackProgress = Runnable { visibility = View.GONE }

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(0xF0161823.toInt())
            cornerRadius = 16 * resources.displayMetrics.density
            setStroke(1, 0x33FFFFFF)
        }
        val padding = (18 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        addView(playbackTimeTextView, LayoutParams(-1, -2))
        addView(playbackProgressBar, LayoutParams(-1, (4 * resources.displayMetrics.density).toInt()))
        isClickable = false
        isFocusable = false
        visibility = View.GONE
    }

    /**
     * 刷新播放时间；只有 seek 等明确操作请求显示时才展开控制条。
     * @param positionSeconds 当前播放位置，单位秒，非法值按 0 处理。
     * @param durationSeconds 总时长，单位秒；直播或未知时长显示未知且比例为 0。
     * @param reveal 为 true 时显示四秒；普通状态更新只更新已有控制条。
     */
    fun updateMediaProgress(positionSeconds: Double, durationSeconds: Double, reveal: Boolean) {
        val position = if (positionSeconds.isFinite()) positionSeconds.coerceAtLeast(0.0) else 0.0
        val duration = if (durationSeconds.isFinite()) durationSeconds.coerceAtLeast(0.0) else 0.0
        playbackTimeTextView.text = "${formatPlaybackTime(position)} / ${if (duration > 0) formatPlaybackTime(duration) else "--:--"}"
        playbackProgressBar.progress = if (duration > 0) (position / duration * 1000).toInt().coerceIn(0, 1000) else 0
        if (reveal) {
            visibility = View.VISIBLE
            removeCallbacks(hidePlaybackProgress)
            postDelayed(hidePlaybackProgress, 4000)
        }
    }

    /** View 脱离窗口后取消隐藏回调，避免 Activity 已关闭仍持有延时任务。 */
    override fun onDetachedFromWindow() {
        removeCallbacks(hidePlaybackProgress)
        super.onDetachedFromWindow()
    }

    /** 将非负秒数转为常规播放器时间格式，不显示毫秒。 */
    private fun formatPlaybackTime(seconds: Double): String {
        val total = seconds.toLong()
        return if (total >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
        else String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
    }
}
