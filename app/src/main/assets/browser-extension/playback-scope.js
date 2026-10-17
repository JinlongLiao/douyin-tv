/** 在扩展隔离环境中共享播放器定位，避免控制被新弹层覆盖的旧播放器。 */

/**
 * 优先选择屏幕中心命中的播放弹层，未命中时回退到最后一个可见弹层。
 * @returns {Element|null} 当前前台播放弹层；尚未显示时返回 null。
 * @see https://developer.mozilla.org/en-US/docs/Web/API/Element/checkVisibility
 */
function findBrowserPlaybackModal() {
    var selector = '[data-e2e="modal-video-container"]';
    var hit = document.elementFromPoint && document.elementFromPoint(innerWidth / 2, innerHeight / 2);
    var hitModal = hit && hit.closest(selector);
    if (hitModal) return hitModal;
    var modals = document.querySelectorAll(selector);
    for (var index = modals.length - 1; index >= 0; index--) {
        if (modals[index].checkVisibility &&
            !modals[index].checkVisibility({ opacityProperty: true, visibilityProperty: true })) continue;
        var bounds = modals[index].getBoundingClientRect();
        if (bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 && bounds.top < innerHeight &&
            bounds.right > 0 && bounds.left < innerWidth) return modals[index];
    }
    return null;
}

/**
 * 在前台弹层内按 URL 中的公开视频标识定位激活播放器，再回退到命中节点和末尾候选。
 * 站点切换期间可能保留多个 feed-active-video，不能使用全页第一个匹配节点。
 * 无弹层时保留普通视频页的激活播放器选择，不查询卡片列表。
 * @param {Element|null} modal 当前前台弹层；null 表示普通页面。
 * @returns {Element|null} 属于当前内容的激活播放器，尚未创建时返回 null。
 */
function findBrowserActivePlayer(modal) {
    var selector = '[data-e2e="feed-active-video"]';
    if (!modal) return document.querySelector(selector);
    var players = modal.querySelectorAll(selector);
    var videoId = new URL(location.href).searchParams.get('modal_id');
    if (videoId && /^\d+$/.test(videoId)) {
        for (var index = 0; index < players.length; index++) {
            if (players[index].classList.contains('video_' + videoId)) return players[index];
        }
    }
    var hit = document.elementFromPoint && document.elementFromPoint(innerWidth / 2, innerHeight / 2);
    var hitPlayer = hit && hit.closest(selector);
    if (hitPlayer && modal.contains(hitPlayer)) return hitPlayer;
    return players.length ? players[players.length - 1] : null;
}

/**
 * 优先从前台弹层的直接宿主选取唯一关闭按钮，允许网站自动隐藏控制条。
 * 实机按钮位于容器外且会 visibility:hidden，但其点击处理仍可用；不能要求鼠标命中。
 * 宿主结构不符时仅回退到实际可命中的按钮，找不到则保留播放器。
 * @param {Element} modal 已定位的前台播放弹层。
 * @returns {Element|null} 属于当前弹层的关闭按钮，未找到时返回 null。
 */
function findBrowserPlaybackCloseButton(modal) {
    var hostButtons = modal && modal.parentElement && modal.parentElement.querySelectorAll('.o7hAjQkB');
    if (hostButtons && hostButtons.length === 1) return hostButtons[0];
    var buttons = document.querySelectorAll('.o7hAjQkB');
    for (var index = buttons.length - 1; index >= 0; index--) {
        var bounds = buttons[index].getBoundingClientRect();
        if (bounds.width <= 0 || bounds.height <= 0) continue;
        var hit = document.elementFromPoint((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2);
        if (hit && (hit === buttons[index] || buttons[index].contains(hit))) return buttons[index];
    }
    return null;
}
