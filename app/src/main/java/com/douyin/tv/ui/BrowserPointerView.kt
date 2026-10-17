package com.douyin.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.Choreographer
import android.view.View

/**
 * 原生鼠标光标；方向键按住时按显示帧移动，不等待网页 JavaScript 或无障碍节点。
 * @param context 当前 Activity 的视图上下文。
 */
class BrowserPointerView(context: Context) : View(context), Choreographer.FrameCallback {
    /** 光标中心的视图内横坐标，单位为物理像素。 */
    var pointerX = 0f
        private set
    /** 光标中心的视图内纵坐标，单位为物理像素。 */
    var pointerY = 0f
        private set
    /** 光标移动后通知浏览器更新鼠标悬停；回调在主线程执行。 */
    var onPointerMoved: ((Float, Float) -> Unit)? = null
    /** 当前水平移动方向，-1/0/1；零方向表示停止。 */
    private var directionX = 0
    /** 当前垂直移动方向，-1/0/1。 */
    private var directionY = 0
    /** 上一帧单调时间，单位纳秒；0 表示下一帧重新建立时间基准。 */
    private var previousFrame = 0L
    /** 本次持续按住方向键的开始时间，单位毫秒，用于长按加速。 */
    private var movementStartedAt = 0L
    /** 圆点白色中心，在深色页面和视频上保持醒目。 */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    /** 低透明度暗底保证明亮画面上的边界，避免大面积遮挡视频。 */
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    /** 抖音青色错位边缘，位于白点左上方。 */
    private val cyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF25F4EE.toInt() }
    /** 抖音粉色错位边缘，位于白点右下方。 */
    private val pinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFE2C55.toInt() }
    /** 仅点击时出现的细扩散环，避免常驻同心圆造成靶心观感。 */
    private val clickRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    /** 点击扩散环的起始时间，单位毫秒；0 表示尚未点击。 */
    private var clickStartedAt = 0L

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 首次布局居中，尺寸改变时保持位置并限制在网页视口内。 */
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (oldWidth == 0 || oldHeight == 0) {
            pointerX = width / 2f
            pointerY = height / 2f
        }
        pointerX = pointerX.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        pointerY = pointerY.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
    }

    /** 绘制原生光标，不拦截网页触摸和输入法焦点。 */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(pointerX, pointerY, 15f, haloPaint)
        canvas.drawCircle(pointerX - 2.5f, pointerY - 1.5f, 10f, cyanPaint)
        canvas.drawCircle(pointerX + 2.5f, pointerY + 1.5f, 10f, pinkPaint)
        canvas.drawCircle(pointerX, pointerY, 8f, fillPaint)
        val elapsed = SystemClock.uptimeMillis() - clickStartedAt
        if (clickStartedAt != 0L && elapsed < 180) {
            val fraction = elapsed / 180f
            clickRingPaint.color = cyanPaint.color
            clickRingPaint.alpha = ((1f - fraction) * 180).toInt()
            canvas.drawCircle(pointerX - 2f, pointerY - 1f, 12f + fraction * 18f, clickRingPaint)
            clickRingPaint.color = pinkPaint.color
            clickRingPaint.alpha = ((1f - fraction) * 180).toInt()
            canvas.drawCircle(pointerX + 2f, pointerY + 1f, 12f + fraction * 18f, clickRingPaint)
            postInvalidateOnAnimation((pointerX - 34).toInt(), (pointerY - 34).toInt(),
                (pointerX + 34).toInt(), (pointerY + 34).toInt())
        }
    }

    /** 确认点击时显示 180 ms 圆环反馈；动画只在原生光标区域重绘。 */
    fun showPointerClickFeedback() {
        clickStartedAt = SystemClock.uptimeMillis()
        invalidate((pointerX - 34).toInt(), (pointerY - 34).toInt(),
            (pointerX + 34).toInt(), (pointerY + 34).toInt())
    }

    /**
     * 开始指定方向移动；首次按下精细移动 4 像素，重复按下由显示帧连续处理。
     * @param dx 水平方向 -1/0/1。
     * @param dy 垂直方向 -1/0/1。
     */
    fun startPointerMovement(dx: Int, dy: Int) {
        if (directionX == dx && directionY == dy) return
        stopPointerMovement()
        directionX = dx
        directionY = dy
        movementStartedAt = SystemClock.uptimeMillis()
        movePointerBy(dx * BrowserPointerMotion.TAP_STEP_PIXELS, dy * BrowserPointerMotion.TAP_STEP_PIXELS)
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** 松键、切换模式或 Activity 隐藏时停止后续帧，防止光标自行漂移。 */
    fun stopPointerMovement() {
        directionX = 0
        directionY = 0
        previousFrame = 0L
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /** 短按等待区不漂移，长按逐级加速；暂停后的首帧不跳跃。 */
    override fun doFrame(frameTimeNanos: Long) {
        if (directionX == 0 && directionY == 0) return
        if (previousFrame != 0L) {
            val seconds = ((frameTimeNanos - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            val distance = BrowserPointerMotion.calculatePointerTravel(
                SystemClock.uptimeMillis() - movementStartedAt, seconds)
            if (distance > 0f) movePointerBy(directionX * distance, directionY * distance)
        }
        previousFrame = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** 限制坐标并仅重绘光标前后区域；悬停通知不影响原生光标绘制。 */
    private fun movePointerBy(dx: Float, dy: Float) {
        val previousX = pointerX
        val previousY = pointerY
        pointerX = (pointerX + dx).coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        pointerY = (pointerY + dy).coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        invalidate(minOf(previousX, pointerX).toInt() - 35, minOf(previousY, pointerY).toInt() - 35,
            maxOf(previousX, pointerX).toInt() + 35, maxOf(previousY, pointerY).toInt() + 35)
        onPointerMoved?.invoke(pointerX, pointerY)
    }
}
