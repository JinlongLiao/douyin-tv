package com.douyin.tv.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证首次分流恢复的范围、一次性消费和失败边界。 */
class BrowserStartupRecoveryStateTest {
    /** 初始化空页和未成功加载不能抢先消费恢复机会。 */
    @Test
    fun retryDownloadLandingOnlyOnceAfterSuccessfulLoad() {
        val state = BrowserStartupRecoveryState()
        assertFalse(state.shouldRetryStartupHome(null, "blank", true))
        assertFalse(state.shouldRetryStartupHome("www.douyin.com", "/home", false))
        assertTrue(state.shouldRetryStartupHome("www.douyin.com", "/home", true))
        assertFalse(state.shouldRetryStartupHome("www.douyin.com", "/home", true))
    }

    /** 已进入正式首页后不再接管后续下载页导航。 */
    @Test
    fun preserveDownloadNavigationAfterNormalStartup() {
        val state = BrowserStartupRecoveryState()
        assertFalse(state.shouldRetryStartupHome("www.douyin.com", "/jingxuan", true))
        assertFalse(state.shouldRetryStartupHome("www.douyin.com", "/home", true))
    }

    /** 不把相似域名或子域的路径当作受控首页。 */
    @Test
    fun neverRecoverExternalOrUnrecognizedLanding() {
        for (host in listOf("example.com", "www.douyin.com.example.com", "other.douyin.com")) {
            val state = BrowserStartupRecoveryState()
            assertFalse(state.shouldRetryStartupHome(host, "/home", true))
            assertFalse(state.shouldRetryStartupHome("www.douyin.com", "/home", true))
        }
    }
}
