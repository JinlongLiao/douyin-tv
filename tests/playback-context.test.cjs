// 验证只在实际播放弹层进出时通知原生层，不对背景内容变化重复切换模式。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const scopeScript = fs.readFileSync('app/src/main/assets/browser-extension/playback-scope.js', 'utf8');
const playbackScript = fs.readFileSync('app/src/main/assets/browser-extension/playback-context.js', 'utf8');

test('播放弹层加入和移除通知模式变化，同状态及背景变化不重复通知', () => {
    const messages = [];
    const frames = [];
    let observer;
    let connected = false;
    const modal = { nodeType: 1, matches: selector => selector === '[data-e2e="modal-video-container"]', querySelector: () => null,
        getBoundingClientRect: () => ({ left: 0, top: 0, right: 1920, bottom: 1080, width: 1920, height: 1080 }) };
    const document = { querySelector: () => null,
        querySelectorAll: selector => connected && selector === '[data-e2e="modal-video-container"]' ? [modal] : [],
        addEventListener() {},
        body: { contains: () => connected } };
    const context = { document, innerWidth: 1920, innerHeight: 1080,
        requestAnimationFrame: callback => frames.push(callback),
        tvNativePort: { postMessage: message => messages.push(message) },
        MutationObserver: class { constructor(callback) { observer = callback; } observe() {} } };
    vm.runInNewContext(scopeScript + '\n' + playbackScript, context);
    assert.deepEqual(messages.map(message => message.active), [false]);
    connected = true;
    observer([{ type: 'childList', addedNodes: [modal] }]);
    frames.shift()();
    assert.deepEqual(messages.map(message => message.active), [false, true]);
    observer([{ type: 'attributes', target: { contains: () => false } }]);
    assert.equal(frames.length, 0);
    observer([{ type: 'attributes', target: modal }]);
    frames.shift()();
    assert.equal(messages.length, 2);
    connected = false;
    observer([{ type: 'childList', addedNodes: [] }]);
    frames.shift()();
    assert.deepEqual(messages.map(message => message.active), [false, true, false]);
});

test('登录加载占位也通知原生阻止首页直接退出，移除后解除', () => {
    const messages = [];
    const frames = [];
    const connected = new Set();
    let observer;
    const login = { nodeType: 1, matches: selector => selector.includes('#login-panel-new'), querySelector: () => null };
    const document = { querySelector: () => null, querySelectorAll: () => [], addEventListener() {},
        body: { contains: node => connected.has(node) } };
    const context = { document, innerWidth: 1920, innerHeight: 1080,
        requestAnimationFrame: callback => frames.push(callback),
        tvNativePort: { postMessage: message => messages.push(message) },
        MutationObserver: class { constructor(callback) { observer = callback; } observe() {} } };
    vm.runInNewContext(scopeScript + '\n' + playbackScript, context);
    connected.add(login);
    observer([{ type: 'childList', addedNodes: [login] }]);
    frames.shift()();
    assert.equal(messages.at(-1).dialog, true);
    assert.equal(messages.at(-1).active, false);
    connected.delete(login);
    observer([{ type: 'childList', addedNodes: [] }]);
    frames.shift()();
    assert.equal(messages.at(-1).dialog, false);
});

test('播放弹层暂停背景预览并拦住再次自动播放，关闭弹层后不干预列表播放', () => {
    let connected = true;
    let observer;
    let playListener;
    const frames = [];
    function createVideo(paused) {
        return { tagName: 'VIDEO', paused, pauses: 0, closest: () => null,
            pause() { this.paused = true; this.pauses++; } };
    }
    const preview = createVideo(false);
    const idlePreview = createVideo(true);
    const foreground = createVideo(false);
    const transitioning = createVideo(false);
    const modal = { contains: video => video === foreground,
        getBoundingClientRect: () => ({ left: 0, top: 0, right: 1920, bottom: 1080, width: 1920, height: 1080 }) };
    foreground.closest = () => modal;
    transitioning.closest = () => ({});
    const document = {
        querySelector: selector => selector === '[data-e2e="modal-video-container"]' ? modal : null,
        querySelectorAll: selector => selector === 'video' ? [preview, idlePreview, foreground, transitioning] :
            connected ? [modal] : [],
        body: { contains: node => connected && node === modal },
        addEventListener(name, listener, capture) {
            assert.equal(name, 'play');
            assert.equal(capture, true);
            playListener = listener;
        }
    };
    const context = { document, innerWidth: 1920, innerHeight: 1080,
        requestAnimationFrame: callback => frames.push(callback),
        tvNativePort: { postMessage() {} },
        MutationObserver: class { constructor(callback) { observer = callback; } observe() {} } };
    vm.runInNewContext(scopeScript + '\n' + playbackScript, context);
    assert.equal(preview.paused, true);
    assert.equal(idlePreview.pauses, 0);
    assert.equal(foreground.paused, false);
    assert.equal(transitioning.paused, false);
    preview.paused = false;
    playListener({ target: preview });
    assert.equal(preview.paused, true);
    assert.equal(foreground.pauses, 0);
    connected = false;
    observer([{ type: 'childList', addedNodes: [] }]);
    frames.shift()();
    preview.paused = false;
    playListener({ target: preview });
    assert.equal(preview.paused, false);
});

test('预创建隐藏播放弹层显示后仍能通知进入播放模式', () => {
    let visible = false;
    let observer;
    const frames = [];
    const messages = [];
    const modal = { getBoundingClientRect: () => ({ left: 0, top: 0, right: 1920, bottom: 1080, width: visible ? 1920 : 0, height: visible ? 1080 : 0 }) };
    const document = {
        querySelector: selector => selector === '[data-e2e="modal-video-container"]' ? modal : null,
        querySelectorAll: selector => selector === '[data-e2e="modal-video-container"]' ? [modal] : [],
        body: { contains: () => true }, addEventListener() {}
    };
    const context = { document, innerWidth: 1920, innerHeight: 1080,
        requestAnimationFrame: callback => frames.push(callback),
        tvNativePort: { postMessage: message => messages.push(message) },
        MutationObserver: class { constructor(callback) { observer = callback; } observe() {} } };
    vm.runInNewContext(scopeScript + '\n' + playbackScript, context);
    assert.equal(messages.at(-1).active, false);
    visible = true;
    observer([{ type: 'attributes', target: modal }]);
    frames.shift()();
    assert.equal(messages.at(-1).active, true);
});

test('新弹层覆盖旧弹层后跟踪前台节点，旧节点移除不再触发模式回退', () => {
    const frames = [];
    const messages = [];
    const connected = new Set();
    let observer;
    let hitModal;
    function createModal() {
        return { nodeType: 1, matches: selector => selector === '[data-e2e="modal-video-container"]',
            querySelector: () => null, contains: () => false,
            getBoundingClientRect: () => ({ left: 0, top: 0, right: 1920, bottom: 1080, width: 1920, height: 1080 }) };
    }
    const old = createModal();
    const foreground = createModal();
    connected.add(old);
    hitModal = old;
    const document = {
        elementFromPoint: () => ({ closest: () => hitModal }),
        querySelector: () => null,
        querySelectorAll: selector => selector === '[data-e2e="modal-video-container"]' ? Array.from(connected) : [],
        body: { contains: node => connected.has(node) }, addEventListener() {}
    };
    const context = { document, innerWidth: 1920, innerHeight: 1080,
        requestAnimationFrame: callback => frames.push(callback),
        tvNativePort: { postMessage: message => messages.push(message) },
        MutationObserver: class { constructor(callback) { observer = callback; } observe() {} } };
    vm.runInNewContext(scopeScript + '\n' + playbackScript, context);
    connected.add(foreground);
    hitModal = foreground;
    observer([{ type: 'childList', target: document.body, addedNodes: [foreground] }]);
    assert.equal(frames.length, 1);
    frames.shift()();
    connected.delete(old);
    observer([{ type: 'childList', target: document.body, addedNodes: [] }]);
    assert.equal(frames.length, 0);
    assert.deepEqual(messages.map(message => message.active), [true]);
});
