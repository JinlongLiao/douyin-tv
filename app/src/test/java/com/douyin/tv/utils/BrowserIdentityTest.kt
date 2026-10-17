package com.douyin.tv.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证两种内核都保留真实 Android 与引擎版本，并使用平板而非 Windows/手机身份。 */
class BrowserIdentityTest {
    /** 默认新内核使用经过视频页实测的 Android 平板兼容身份。 */
    @Test
    fun douyinCompatibilityUserAgentUsesVerifiedAndroidTabletIdentity() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 11; VIDAA_TV Build/RP1A.200720.011) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.120 Safari/537.36",
            BrowserIdentity.createDouyinTabletUserAgent()
        )
    }

    /** Chromium 系统 UA 只移除嵌入控件和手机标记，保留真实系统与 Chrome 83。 */
    @Test
    fun chromiumUserAgentPreservesAndroidAndEngineVersion() {
        val native = "Mozilla/5.0 (Linux; Android 11; VIDAA_TV; wv) AppleWebKit/537.36 Version/4.0 Chrome/83.0.4103.120 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 11; VIDAA_TV) AppleWebKit/537.36 Chrome/83.0.4103.120 Safari/537.36",
            BrowserIdentity.createTabletUserAgent(native)
        )
    }

    /** Gecko 平板 UA 保持 Android 11 和 Firefox 158，不伪造 Chromium 或 Windows。 */
    @Test
    fun geckoUserAgentPreservesAndroidAndEngineVersion() {
        val native = "Mozilla/5.0 (Android 11; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0"
        assertEquals(
            "Mozilla/5.0 (Android 11; Tablet; rv:158.0) Gecko/158.0 Firefox/158.0",
            BrowserIdentity.createTabletUserAgent(native)
        )
    }
}
