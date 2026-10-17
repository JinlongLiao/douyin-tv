/** 只跟踪播放/登录弹层结构与祖先可见性，不安装焦点候选或全页定时扫描。 */
(function () {
    var videoSelector = '[data-e2e="modal-video-container"]';
    var loginSelector = '#login-panel-new, #douyin_login_comp_flat_panel, #douyin_login_comp_h5_panel';
    var videoModal = document.querySelector(videoSelector);
    var loginPanel = document.querySelector(loginSelector);
    var publishedActive = null;
    var publishedDialog = null;
    var scheduled = false;

    /** 只在状态改变时通知原生；登录加载占位也保守地视作弹窗，避免加载中误退出。 */
    function publishPlaybackContext() {
        scheduled = false;
        var bounds = videoModal && document.body.contains(videoModal) && videoModal.getBoundingClientRect();
        var active = !!(bounds && bounds.width > 0 && bounds.height > 0);
        var dialog = !!(loginPanel && document.body.contains(loginPanel));
        if (active !== publishedActive || dialog !== publishedDialog) {
            publishedActive = active;
            publishedDialog = dialog;
            tvNativePort.postMessage({ type: 'playback-context', active: active, dialog: dialog });
        }
    }

    /** 只在已跟踪节点失效时，从本次新增子树查找替代节点，不反复查询整份文档。 */
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
        if (!videoModal) videoModal = findAddedPanel(records, videoSelector);
        if (!loginPanel) loginPanel = findAddedPanel(records, loginSelector);
        var changed = videoModal !== previousVideoModal || loginPanel !== previousLoginPanel;
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
    if (document.body) {
        observer.observe(document.body, { childList: true, subtree: true, attributes: true,
            attributeFilter: ['class', 'style', 'hidden', 'aria-hidden'] });
        publishPlaybackContext();
    }
})();
