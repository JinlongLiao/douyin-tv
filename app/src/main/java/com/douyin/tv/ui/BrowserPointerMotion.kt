package com.douyin.tv.ui

/** 遥控鼠标的精细短按与分段长按速度，纯计算以便验证小控件定位不发生短按漂移。 */
object BrowserPointerMotion {
    /** 短按一次移动四个物理像素，适合验证码、关闭等小控件微调。 */
    const val TAP_STEP_PIXELS = 4f

    /**
     * 计算本帧应连续移动的距离；前 180 ms 只使用短按步长，之后再分段加速。
     * @param heldMilliseconds 当前方向键持续按住的时长，单位毫秒。
     * @param frameSeconds 距离上一显示帧的时间，单位秒，最多计算 50 ms 以避免卡帧后跳跃。
     * @return 本帧沿方向移动的物理像素数，非负；短按等待区返回 0。
     */
    fun calculatePointerTravel(heldMilliseconds: Long, frameSeconds: Float): Float {
        val speed = when {
            heldMilliseconds < 180 -> 0f
            heldMilliseconds < 700 -> 120f
            heldMilliseconds < 1400 -> 360f
            else -> 780f
        }
        return speed * frameSeconds.coerceIn(0f, 0.05f)
    }
}
