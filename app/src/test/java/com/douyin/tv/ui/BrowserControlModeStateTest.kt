package com.douyin.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证自动播放模式只保存一次原模式，并在退出时正确恢复。 */
class BrowserControlModeStateTest {
    /** 多次播放状态通知不能覆盖进入前的鼠标模式。 */
    @Test
    fun repeatedPlaybackEntryRestoresOriginalMouseMode() {
        val state = BrowserControlModeState()
        state.updateBrowserPlaybackContext(true)
        assertTrue(state.playbackControlMode)
        state.updateBrowserPlaybackContext(true)
        state.updateBrowserPlaybackContext(false)
        assertFalse(state.playbackControlMode)
    }

    /** 原本手动使用播放模式时，退出播放器仍恢复播放模式。 */
    @Test
    fun playbackExitRestoresPreviouslySelectedPlaybackMode() {
        val state = BrowserControlModeState()
        state.toggleBrowserControlMode()
        state.updateBrowserPlaybackContext(true)
        state.updateBrowserPlaybackContext(false)
        assertTrue(state.playbackControlMode)
    }

    /** 播放器内手动切换模式不修改进入前的快照；下一次进入重新保存。 */
    @Test
    fun manualModeChangeInsidePlaybackPreservesEntrySnapshot() {
        val state = BrowserControlModeState()
        state.updateBrowserPlaybackContext(true)
        state.toggleBrowserControlMode()
        assertFalse(state.playbackControlMode)
        state.updateBrowserPlaybackContext(true)
        assertFalse(state.playbackControlMode)
        state.updateBrowserPlaybackContext(false)
        assertFalse(state.playbackControlMode)
        state.updateBrowserPlaybackContext(true)
        assertTrue(state.playbackControlMode)
    }
}
