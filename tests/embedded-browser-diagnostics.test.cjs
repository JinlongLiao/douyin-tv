// 执行实际内置扩展，验证鼠标模式不安装旧焦点引擎，以及播放/返回协议。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/browser-extension/remote-controls.js', 'utf8');

function createBrowserBridge(document) {
    const messages = [];
    let listener;
    const context = { document: { addEventListener() {}, querySelector: () => null, ...document }, window: {},
        navigator: { userAgent: 'Android tablet', platform: 'Linux' },
        performance: { now: () => 10 }, URL,
        location: { href: 'https://www.douyin.com/jingxuan', pathname: '/jingxuan' },
        innerWidth: 1920, innerHeight: 1080,
        tvNativePort: { postMessage: message => messages.push(message),
            onMessage: { addListener: callback => { listener = callback; } } }
    };
    vm.runInNewContext(script, context);
    return { messages, send: action => listener({ ...action, sequence: 1, sentAt: Date.now() }) };
}

test('鼠标模式不加载无障碍焦点框脚本', () => {
    const manifest = JSON.parse(fs.readFileSync('app/src/main/assets/browser-extension/manifest.json', 'utf8'));
    assert.equal(manifest.content_scripts.some(entry => entry.js?.includes('focus-engine.js')), false);
});

test('快进快退约束在视频时长内并请求显示原生进度条', () => {
    const video = { tagName: 'VIDEO', currentTime: 95, duration: 100, paused: false,
        getBoundingClientRect: () => ({ left: 0, top: 0, right: 1920, bottom: 1080 }) };
    const bridge = createBrowserBridge({ querySelectorAll: () => [video] });
    bridge.send({ action: 'seek', dx: 1 });
    assert.equal(video.currentTime, 100);
    const state = bridge.messages.find(message => message.type === 'media-state');
    assert.equal(state.reveal, true);
    assert.equal(state.position, 100);
    video.currentTime = 4;
    bridge.send({ action: 'seek', dx: -1 });
    assert.equal(video.currentTime, 0);
});

test('内置扩展返回键关闭 H5 登录面板，不请求退出浏览器', () => {
    let dismissed = 0;
    const close = { click: () => dismissed++ };
    const title = { parentElement: { querySelector: () => ({ parentElement: close }) } };
    const panel = { getBoundingClientRect: () => ({ width: 566, height: 720 }), querySelector: () => title };
    const bridge = createBrowserBridge({ querySelectorAll: () => [panel] });
    bridge.send({ action: 'back' });
    assert.equal(dismissed, 1);
    assert.equal(bridge.messages.find(message => message.type === 'result').result, 'dialog-closed');
});

test('播放模式按列表顺序切换下一张视频封面', () => {
    let clicked = false;
    const current = {};
    const next = { scrollIntoView() {}, querySelector: () => ({ click: () => { clicked = true; } }) };
    const video = { closest: () => current,
        getBoundingClientRect: () => ({ left: 0, top: 0, right: 1920, bottom: 1080 }) };
    const bridge = createBrowserBridge({ querySelectorAll: selector => selector === 'video' ? [video] : [current, next] });
    bridge.send({ action: 'switch-video', dy: 1 });
    assert.equal(clicked, true);
    assert.equal(bridge.messages.find(message => message.type === 'result').result, 'video-switched');
});


test('播放弹层控制条隐藏时仍操作激活视频的箭头，不扫描背景列表', () => {
    let clicked = false;
    const arrow = { getAttribute: () => null, getBoundingClientRect: () => ({ width: 32, height: 32 }),
        click: () => { clicked = true; } };
    const activePlayer = { querySelector: selector => {
        assert.equal(selector, '[data-e2e="video-switch-next-arrow"]');
        return arrow;
    } };
    const bridge = createBrowserBridge({ querySelector: selector => {
        assert.equal(selector, '[data-e2e="feed-active-video"]');
        return activePlayer;
    }, querySelectorAll: () => { throw new Error('不应扫描列表'); } });
    bridge.send({ action: 'switch-video', dy: 1 });
    assert.equal(clicked, true);
    assert.equal(bridge.messages.find(message => message.type === 'result').result, 'video-switched');
});

test('全屏弹层返回关闭播放器，不请求浏览器历史或应用退出', () => {
    let closed = false;
    const modal = { getBoundingClientRect: () => ({ width: 1920, height: 1080 }) };
    const bridge = createBrowserBridge({ querySelectorAll: () => [], querySelector: selector =>
        selector === '[data-e2e="modal-video-container"]' ? modal :
            selector === '.o7hAjQkB' ? { click: () => { closed = true; } } : null });
    bridge.send({ action: 'back' });
    assert.equal(closed, true);
    assert.equal(bridge.messages.find(message => message.type === 'result').result, 'video-modal-closed');
});

test('全屏关闭控件失效时保留播放界面，不能降级为退出应用', () => {
    const modal = { getBoundingClientRect: () => ({ width: 1920, height: 1080 }) };
    const bridge = createBrowserBridge({ querySelectorAll: () => [], querySelector: selector =>
        selector === '[data-e2e="modal-video-container"]' ? modal : null });
    bridge.send({ action: 'back' });
    assert.equal(bridge.messages.find(message => message.type === 'result').result, 'video-close-not-found');
});

test('首页返回明确请求二次退出规则，不依赖浏览器残留历史', () => {
    const bridge = createBrowserBridge({ querySelectorAll: () => [] });
    bridge.send({ action: 'back' });
    assert.equal(bridge.messages.find(message => message.type === 'result').result, 'root-back');
});
