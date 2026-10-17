/** 跟踪播放/登录弹层并暂停被遮住的视频，不安装焦点候选或全页定时扫描。 */
(function () {
    var videoSelector = '[data-e2e="modal-video-container"]';
    var loginSelector = '#login-panel-new, #douyin_login_comp_flat_panel, #douyin_login_comp_h5_panel';
    var videoModal = findBrowserPlaybackModal() || document.querySelector(videoSelector);
    var loginPanel = document.querySelector(loginSelector);
    var publishedActive = null;
    var publishedDialog = null;
    var scheduled = false;

    /** 暂停页面背景预览；保护所有播放弹层内的视频，避免干扰网站的播放器切换过程。 */
    function pauseBrowserBackgroundVideos() {
        document.querySelectorAll('video').forEach(function (video) {
            if (!video.paused && !video.closest(videoSelector)) video.pause();
        });
    }

    /** 只在状态改变时通知原生；登录加载占位也保守地视作弹窗，避免加载中误退出。 */
    function publishPlaybackContext() {
        scheduled = false;
        var foregroundModal = findBrowserPlaybackModal();
        // 未显示的预创建弹层仍需缓存，以便后续祖先样式变化能够触发状态更新。
        if (foregroundModal) videoModal = foregroundModal;
        else if (videoModal && !document.body.contains(videoModal)) videoModal = null;
        var active = !!foregroundModal;
        var dialog = !!(loginPanel && document.body.contains(loginPanel));
        if (active && !publishedActive) pauseBrowserBackgroundVideos();
        if (active !== publishedActive || dialog !== publishedDialog) {
            publishedActive = active;
            publishedDialog = dialog;
            tvNativePort.postMessage({ type: 'playback-context', active: active, dialog: dialog });
        }
    }

    /** 从本次新增子树查找弹层候选，避免为每次背景变更重新扫描整份文档。 */
    function findAddedPanel(records, selector) {
        for (var index = 0; index < records.length; index++) {
            var nodes = records[index].addedNodes || [];
            for (var nodeIndex = 0; nodeIndex < nodes.length; nodeIndex++) {
                var node = nodes[nodeIndex];
                if (node.nodeType !== 1) continue;
                var candidate = node.matches(selector) ? node : node.querySelector(selector);
                if (candidate) return candidate;
            }
        }
        return null;
    }

    /** 合并真正影响弹层的变更，背景进度和按钮样式不触发可见性查询。 */
    var observer = new MutationObserver(function (records) {
        var previousVideoModal = videoModal;
        var previousLoginPanel = loginPanel;
        if (videoModal && !document.body.contains(videoModal)) videoModal = null;
        if (loginPanel && !document.body.contains(loginPanel)) loginPanel = null;
        // 新弹层可能覆盖仍连接的旧弹层；只检查旧弹层外新增的子树，随后按前台命中重新定位。
        var addedVideoModal = findAddedPanel(records.filter(function (record) {
            return record.type === 'childList' && (!videoModal || !videoModal.contains(record.target));
        }), videoSelector);
        if (!videoModal) videoModal = addedVideoModal;
        if (!loginPanel) loginPanel = findAddedPanel(records, loginSelector);
        var changed = videoModal !== previousVideoModal || loginPanel !== previousLoginPanel ||
            !!(addedVideoModal && addedVideoModal !== previousVideoModal);
        if (!changed && videoModal) {
            changed = records.some(function (record) {
                return record.type === 'attributes' &&
                    (record.target === videoModal || record.target.contains(videoModal));
            });
        }
        if (changed && !scheduled) {
            scheduled = true;
            requestAnimationFrame(publishPlaybackContext);
        }
    });
    // 网站可能在弹层打开后才自动启动直播预览；捕获 play 事件即可处理，不增加常驻轮询。
    // 弹层关闭后停止干预，是否重新播放预览仍交给网站自身的用户交互处理。
    document.addEventListener('play', function (event) {
        if (!publishedActive || !videoModal || !document.body.contains(videoModal) ||
            event.target.tagName !== 'VIDEO') return;
        if (event.target.closest(videoSelector)) pauseBrowserBackgroundVideos();
        else event.target.pause();
    }, true);
    if (document.body) {
        observer.observe(document.body, { childList: true, subtree: true, attributes: true,
            attributeFilter: ['class', 'style', 'hidden', 'aria-hidden'] });
        publishPlaybackContext();
    }
})();
