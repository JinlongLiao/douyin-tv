package com.douyin.tv.utils

/** 管理 Android 平板身份；网站兼容 UA 与实际内核版本分别记录。 */
object BrowserIdentity {
    /**
     * 返回电视实测可进入抖音视频页的 Android 平板兼容身份。
     * Firefox 平板身份会被网站分流至下载页，因此仅在 UA 中保留已验证的 Chrome 兼容标记；
     * 该标记不代表实际内核版本，实际加载与渲染始终由 APK 内置 GeckoView 完成。
     *
     * @return 已在 Android 11 VIDAA 电视验证的兼容 UA，不包含 Windows 或手机标记。
     */
    fun createDouyinTabletUserAgent(): String =
        "Mozilla/5.0 (Linux; Android 11; VIDAA_TV Build/RP1A.200720.011) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.120 Safari/537.36"

    /**
     * 从内核提供的真实 UA 移除手机及嵌入控件标记，禁止伪装 Windows 或其他浏览器版本。
     *
     * @param engineUserAgent 当前浏览器内核生成的原始 UA。
     * @return 保留 Android、设备及真实内核版本的平板 UA。
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/User-Agent/Firefox">Firefox Android 平板 UA 约定</a>
     * @see <a href="https://github.com/GoogleChrome/developer.chrome.com/blob/main/site/en/docs/multidevice/user-agent/index.md">Chrome Android 手机和平板 UA 约定</a>
     */
    fun createTabletUserAgent(engineUserAgent: String): String {
        val tabletUserAgent = if (engineUserAgent.contains("Firefox/")) {
            engineUserAgent.replace("; Mobile", "; Tablet")
        } else {
            engineUserAgent
        }
        return tabletUserAgent.replace("; wv", "")
            .replace(" Version/4.0", "")
            .replace("; Mobile", "")
            .replace(" Mobile Safari/", " Safari/")
    }
}
