// 验证只在实际播放弹层进出时通知原生层，不对背景内容变化重复切换模式。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('播放弹层加入和移除通知模式变化，同状态及背景变化不重复通知', () => {
    const messages = [];
    const frames = [];
    let observer;
    let connected = false;
    const modal = { nodeType: 1, matches: selector => selector === '[data-e2e="modal-video-container"]', querySelector: () => null,
        getBoundingClientRect: () => ({ width: 1920, height: 1080 }) };
    const document = { querySelector: () => null, body: { contains: () => connected } };
    const context = { document, requestAnimationFrame: callback => frames.push(callback),
        tvNativePort: { postMessage: message => messages.push(message) },
        MutationObserver: class { constructor(callback) { observer = callback; } observe() {} } };
    vm.runInNewContext(fs.readFileSync('app/src/main/assets/browser-extension/playback-context.js', 'utf8'), context);
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
    const document = { querySelector: () => null, body: { contains: node => connected.has(node) } };
    const context = { document, requestAnimationFrame: callback => frames.push(callback),
        tvNativePort: { postMessage: message => messages.push(message) },
        MutationObserver: class { constructor(callback) { observer = callback; } observe() {} } };
    vm.runInNewContext(fs.readFileSync('app/src/main/assets/browser-extension/playback-context.js', 'utf8'), context);
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
