package com.douyin.tv.ui

/** 保存进入播放器前的操作模式，重复进入通知不会覆盖原模式；仅由主线程持有。 */
class BrowserControlModeState {
    /** 当前是否用方向键控制视频，默认 false 表示鼠标模式。 */
    var playbackControlMode = false
        private set
    /** 进入播放器前的模式快照；null 表示目前没有自动切换的播放上下文。 */
    private var modeBeforePlayback: Boolean? = null

    /** 用户通过菜单主动切换当前模式，不改变进入播放器前的快照。 */
    fun toggleBrowserControlMode() {
        playbackControlMode = !playbackControlMode
    }

    /**
     * 进入播放器时保存一次并自动切到播放模式，退出时恢复原模式。
     * @param active 原生全屏或网站全屏播放弹层是否仍然激活。
     */
    fun updateBrowserPlaybackContext(active: Boolean) {
        if (active) {
            if (modeBeforePlayback == null) {
                modeBeforePlayback = playbackControlMode
                playbackControlMode = true
            }
        } else {
            modeBeforePlayback?.let { playbackControlMode = it }
            modeBeforePlayback = null
        }
    }
}
