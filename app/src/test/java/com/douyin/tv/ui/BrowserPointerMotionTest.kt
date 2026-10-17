package com.douyin.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证短按能精细定位，连续移动不会在等待区或卡帧后突然跳跃。 */
class BrowserPointerMotionTest {
    /** 常见快速按下/抬起只移动四像素，不额外累计连续帧位移。 */
    @Test
    fun shortPressKeepsFourPixelPrecisionWithoutDrift() {
        assertEquals(4f, BrowserPointerMotion.TAP_STEP_PIXELS, 0f)
        assertEquals(0f, BrowserPointerMotion.calculatePointerTravel(80, 0.016f), 0f)
        assertEquals(0f, BrowserPointerMotion.calculatePointerTravel(179, 0.016f), 0f)
    }

    /** 初始长按每帧小幅移动，持续按住后才提升大距离移动速度。 */
    @Test
    fun heldDirectionAcceleratesAfterPrecisionPeriod() {
        val slow = BrowserPointerMotion.calculatePointerTravel(200, 0.016f)
        val medium = BrowserPointerMotion.calculatePointerTravel(800, 0.016f)
        val fast = BrowserPointerMotion.calculatePointerTravel(1500, 0.016f)
        assertTrue(slow < 2f)
        assertTrue(medium > slow)
        assertTrue(fast > medium)
    }

    /** 主线程卡顿后的单帧最多移动 39 像素，不补偿整个停顿时间。 */
    @Test
    fun stalledFrameDoesNotCauseLargeCursorJump() {
        assertEquals(39f, BrowserPointerMotion.calculatePointerTravel(2000, 2f), 0.001f)
    }
}
