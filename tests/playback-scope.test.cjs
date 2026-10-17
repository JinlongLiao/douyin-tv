// 覆盖真实站点保留旧弹层、多个激活播放器及预创建隐藏弹层的选择边界。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/browser-extension/playback-scope.js', 'utf8');

function createScope(document, href = 'https://www.douyin.com/jingxuan') {
    const context = { document, innerWidth: 1920, innerHeight: 1080, location: { href }, URL };
    vm.createContext(context);
    vm.runInContext(script, context);
    return context;
}

test('旧弹层仍有尺寸时，选择实际命中的前台弹层', () => {
    const old = {};
    const foreground = {};
    const scope = createScope({ elementFromPoint: () => ({ closest: () => foreground }),
        querySelectorAll: () => [old, foreground] });
    assert.equal(scope.findBrowserPlaybackModal(), foreground);
});

test('同一弹层保留多个激活播放器时，匹配当前 URL 视频而非第一个', () => {
    const old = { classList: { contains: () => false } };
    const foreground = { classList: { contains: value => value === 'video_12345' } };
    const modal = { querySelectorAll: () => [old, foreground] };
    const scope = createScope({}, 'https://www.douyin.com/jingxuan?modal_id=12345');
    assert.equal(scope.findBrowserActivePlayer(modal), foreground);
});

test('没有 URL 标识时按前台命中定位播放器', () => {
    const foreground = {};
    const old = {};
    const modal = { querySelectorAll: () => [foreground, old], contains: player => player === foreground };
    const scope = createScope({ elementFromPoint: () => ({ closest: () => foreground }) });
    assert.equal(scope.findBrowserActivePlayer(modal), foreground);
});

test('未命中弹层时忽略透明、屏外或零尺寸候选，不把预创建节点当作全屏', () => {
    const visible = { checkVisibility: () => true, getBoundingClientRect: () => ({ left: 0, top: 0, right: 1920, bottom: 1080, width: 1920, height: 1080 }) };
    const transparent = { checkVisibility: () => false, getBoundingClientRect: () => ({ left: 0, top: 0, right: 1920, bottom: 1080, width: 1920, height: 1080 }) };
    const hidden = { getBoundingClientRect: () => ({ width: 0, height: 0 }) };
    const offscreen = { getBoundingClientRect: () => ({ left: 0, right: 1920, top: 2000, bottom: 3080,
        width: 1920, height: 1080 }) };
    const scope = createScope({ querySelectorAll: () => [visible, transparent, hidden, offscreen] });
    assert.equal(scope.findBrowserPlaybackModal(), visible);
});

test('关闭按钮必须命中当前画面，不能点击被覆盖的旧播放器按钮', () => {
    const foreground = { getBoundingClientRect: () => ({ left: 10, top: 10, right: 50, bottom: 50, width: 40, height: 40 }) };
    const old = { getBoundingClientRect: () => ({ left: 100, top: 10, right: 140, bottom: 50, width: 40, height: 40 }),
        contains: () => false };
    const scope = createScope({ querySelectorAll: () => [foreground, old],
        elementFromPoint: x => x < 100 ? foreground : {} });
    assert.equal(scope.findBrowserPlaybackCloseButton(), foreground);
});
