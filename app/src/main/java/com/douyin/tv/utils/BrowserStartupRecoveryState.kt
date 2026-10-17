package com.douyin.tv.utils

/**
 * 仅在会话首次启动时恢复站点误分流的下载页；由 Activity 主线程独占。
 * 等落地页加载成功后再重试一次，保留站点初始化资料；失败不重试，避免导航循环。
 */
class BrowserStartupRecoveryState {
    /** 首次成功落地前保持 true；消费恢复或进入其他页面后永久变为 false。 */
    private var awaitingStartupLanding = true

    /**
     * 判断本次完成的顶层导航是否需要重新请求原首页；调用成功即消费唯一重试机会。
     * about:blank 和加载失败不消费机会，正常首页、用户页面及外部站点成功后终止启动恢复。
     *
     * @param host 最近导航位置的主机；初始化空文档为 null。
     * @param path 最近导航位置的路径；仅抖音精确 /home 下载页允许恢复。
     * @param pageLoaded 本次顶层导航是否成功完成。
     * @return 是否应重新请求原首页，不改变 UA、缓存、登录资料或后续导航策略。
     */
    fun shouldRetryStartupHome(host: String?, path: String?, pageLoaded: Boolean): Boolean {
        if (!awaitingStartupLanding || !pageLoaded || host == null) {
            return false
        }
        awaitingStartupLanding = false
        return (host == "www.douyin.com" || host == "douyin.com") && path == "/home"
    }
}
