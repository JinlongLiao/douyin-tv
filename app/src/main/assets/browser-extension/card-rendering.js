/**
 * 将屏外卡片渲染规则显式加入页面样式，便于在实际页面核对加载结果。
 * 在 document_start 创建一次样式节点，使首次布局即可跳过屏外卡片。
 * 此时 head 可能尚不存在，因此允许挂到 documentElement；不扫描卡片或轮询。
 * 280px 接近列表实测高度；auto 在卡片渲染后记住真实高度。
 * 来源：https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/content-visibility
 */
(function () {
    var style = document.createElement('style');
    style.textContent = '[data-aweme-id] { content-visibility: auto !important; ' +
        'contain-intrinsic-block-size: auto 280px; }';
    (document.head || document.documentElement).appendChild(style);
})();
