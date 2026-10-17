// 直接执行 Activity 注入的 JavaScript，覆盖遥控器方向选择与单视频页面回归。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const activitySource = fs.readFileSync('app/src/main/java/com/douyin/tv/MainActivity.kt', 'utf8');
const engineSource = fs.readFileSync('app/src/main/assets/browser-extension/focus-engine.js', 'utf8');
const source = activitySource + '\n' + engineSource;

function createItem(left, top, width = 100, height = 100) {
    return {
        type: 'control',
        el: { getBoundingClientRect: () => ({ left, top, right: left + width,
            bottom: top + height, width, height }) }
    };
}

function selectNeighbor(current, candidates, dx, dy) {
    const start = source.indexOf('function findGridNeighbor(');
    const end = source.indexOf('TV.findGridNeighbor', start);
    const context = { TV: { items: [current, ...candidates] },
        document: { body: { contains: () => true } } };
    vm.createContext(context);
    vm.runInContext(source.slice(start, end), context);
    return context.findGridNeighbor(current, dx, dy);
}

for (const [name, dx, dy] of [['右', 1, 0], ['左', -1, 0], ['下', 0, 1], ['上', 0, -1]]) {
    test(`向${name}移动不能选中反方向的重叠控件`, () => {
        const current = createItem(200, 200);
        const opposite = createItem(200 - dx * 10, 200 - dy * 10);
        const expected = createItem(200 + dx * 200, 200 + dy * 200);
        assert.equal(selectNeighbor(current, [opposite, expected], dx, dy), expected);
        assert.equal(selectNeighbor(current, [opposite], dx, dy), null);
    });
}

test('首页只有一个视频时仍移动焦点，不跳过菜单和按钮', () => {
    const method = source.slice(source.indexOf('    private fun focusEngineNavigate('),
        source.indexOf('    private fun focusEngineAction('));
    const script = method.match(/val script = """([\s\S]*?)"""\.trimIndent/)[1]
        .replace(/\$\{if \(dy > 0\)[^}]+\}/g, 'window.innerHeight*0.6')
        .replace(/\$dx/g, '1').replace(/\$dy/g, '0');
    let moved = false;
    const context = {
        location: { href: 'https://www.douyin.com/jingxuan?from_nav=1' },
        document: { querySelector: () => null, querySelectorAll: () => [{}] },
        window: { __tvFocus: { hasModal: () => false, move: (dx, dy) => { assert.equal(dx, 1);
            assert.equal(dy, 0); moved = true; } } }
    };
    assert.equal(vm.runInNewContext(script, context), 'focus');
    assert.equal(moved, true);
});

test('确认键仅触发一次 click，保留按下和抬起事件', () => {
    const start = source.indexOf('function fireRemoteClick(');
    const end = source.indexOf('function getActionTarget(', start);
    const events = [];
    const el = { focus: () => {}, dispatchEvent: e => events.push(e.type),
        click: () => events.push('click') };
    class RemoteMouseEvent { constructor(type) { this.type = type; } }
    const context = { window: {}, centerOf: () => ({ x: 5, y: 5 }), MouseEvent: RemoteMouseEvent };
    vm.createContext(context);
    vm.runInContext(source.slice(start, end), context);
    assert.equal(context.fireRemoteClick(el), true);
    assert.deepEqual(events, ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']);
});

test('登录弹窗优先于视频页导航', () => {
    const method = source.slice(source.indexOf('    private fun focusEngineNavigate('),
        source.indexOf('    private fun focusEngineAction('));
    const script = method.match(/val script = """([\s\S]*?)"""\.trimIndent/)[1]
        .replace(/\$\{if \(dy > 0\)[^}]+\}/g, 'window.innerHeight*0.6')
        .replace(/\$dx/g, '0').replace(/\$dy/g, '1');
    let moved = false;
    const context = { window: { __tvFocus: { hasModal: () => true,
        move: () => { moved = true; } } } };
    assert.equal(vm.runInNewContext(script, context), 'dialog-focus');
    assert.equal(moved, true);
});

test('实机登录面板打开时焦点范围限制在面板内，隐藏后恢复页面', () => {
    const start = source.indexOf('function findFocusScope(');
    const end = source.indexOf('TV.hasModal', start);
    const panel = {};
    let visible = true;
    const context = { document: { querySelectorAll: () => [panel] }, isVisible: () => visible };
    vm.createContext(context);
    vm.runInContext(source.slice(start, end), context);
    assert.equal(context.findFocusScope(), panel);
    visible = false;
    assert.equal(context.findFocusScope(), context.document);
});

test('稳定页面连续移动复用候选，弹窗范围变化时才重建', () => {
    const start = source.indexOf('function moveFocus(');
    const end = source.indexOf('TV.move =', start);
    const pageScope = {};
    let currentScope = pageScope;
    let gathers = 0;
    const current = { el: { scrollIntoView: () => {} } };
    const next = { el: { scrollIntoView: () => {} } };
    const context = {
        TV: { scope: pageScope, dirty: false, current, items: [current, next] },
        findFocusScope: () => currentScope,
        gather: () => { gathers++; context.TV.scope = currentScope; context.TV.dirty = false; },
        gatherLight: () => {}, isVisible: () => true,
        document: { body: { contains: () => true } },
        findGridNeighbor: () => next, findNearestInDirection: () => next,
        drawBox: () => {}, window: {}
    };
    vm.createContext(context);
    vm.runInContext(source.slice(start, end), context);
    context.moveFocus(1, 0);
    context.moveFocus(-1, 0);
    assert.equal(gathers, 0);
    currentScope = {};
    context.moveFocus(0, 1);
    assert.equal(gathers, 1);
});

test('body 尚未创建时等待 DOM 就绪，不发布半初始化焦点引擎', () => {
    const script = engineSource;
    let eventName;
    const context = { window: {}, document: { body: null,
        addEventListener: (name, callback, options) => {
            eventName = name;
            assert.equal(typeof callback, 'function');
            assert.equal(options.once, true);
        } } };
    assert.equal(vm.runInNewContext(script, context), 'waiting-for-dom');
    assert.equal(eventName, 'DOMContentLoaded');
    assert.equal(context.window.__tvFocus, undefined);
});

test('重复加载回调只刷新已有引擎，保留用户选中控件', () => {
    const script = engineSource;
    let refreshed = 0;
    const selected = {};
    const engine = { current: selected, refresh: () => refreshed++, draw: () => {} };
    const context = { window: { __tvFocus: engine }, document: { body: {} },
        console: { log: () => {} } };
    assert.equal(vm.runInNewContext(script, context), 'refreshed');
    assert.equal(refreshed, 1);
    assert.equal(engine.current, selected);
});

test('视频外层卡片通过封面内层触发真实点击，不跳转作者资料', () => {
    const start = engineSource.indexOf('function getActionTarget(');
    const end = engineSource.indexOf('function getPrimaryVideo(', start);
    const image = {};
    const card = { matches: selector => selector === '[data-aweme-id]',
        querySelector: selector => { assert.equal(selector, 'img,video'); return image; } };
    const context = {};
    vm.createContext(context);
    vm.runInContext(engineSource.slice(start, end), context);
    assert.equal(context.getActionTarget(card), image);
});

test('网站可聚焦的 tabpanel 列表容器不能成为可点击候选', () => {
    const start = engineSource.indexOf('function gather()');
    const end = engineSource.indexOf('TV.refresh = gather;', start);
    const panel = { getAttribute: key => key === 'role' ? 'tabpanel' : null, textContent: '整个视频列表' };
    const button = { getAttribute: key => key === 'role' ? 'button' : null, textContent: '登录' };
    const document = { querySelectorAll: selector => selector.startsWith('a[href], button') ? [panel, button] : [] };
    const context = { document, TV: { scope: document }, findFocusScope: () => document,
        isVisible: () => true, makeItem: (el, type, name) => ({ el, type, name }),
        gatherVersion: 0, console: { log: () => {} } };
    vm.createContext(context);
    vm.runInContext(engineSource.slice(start, end), context);
    assert.equal(context.gather(), 1);
    assert.equal(context.TV.items[0].el, button);
});

for (const panelId of ['douyin_login_comp_flat_panel', 'douyin_login_comp_h5_panel']) {
    test(`识别并关闭实际登录面板 ${panelId}`, () => {
        const start = engineSource.indexOf('function findFocusScope(');
        const end = engineSource.indexOf('TV.dirty = true;', engineSource.indexOf('TV.dismissModal'));
        const close = {};
        const titleId = `${panelId}_title`;
        const title = { parentElement: { querySelector: () => ({ parentElement: close }) } };
        const panel = { querySelector: selector => selector.includes(`#${titleId}`) ? title : null };
        const clicks = [];
        const context = {
            TV: {}, isVisible: () => true, fireRemoteClick: element => clicks.push(element),
            document: { querySelectorAll: selector => selector.split(',').some(s => s.trim() === `#${panelId}`) ? [panel] : [] }
        };
        vm.runInNewContext(engineSource.slice(start, end) + 'return true; };', context);
        assert.equal(context.findFocusScope(), panel);
        assert.equal(context.TV.dismissModal(), true);
        assert.deepEqual(clicks, [close]);
    });
}

test('背景属性变化只标记候选过期，不安排弹窗可见性扫描', () => {
    const start = engineSource.indexOf('var refreshTimer;');
    const end = engineSource.indexOf('observer.observe(', start);
    let callback;
    let scheduled = 0;
    const document = { body: { contains: () => true } };
    const context = { document, TV: { scope: document, current: { el: {} }, dirty: false },
        label: { contains: () => false }, hl: { contains: () => false },
        MutationObserver: class { constructor(handler) { callback = handler; } },
        setTimeout: () => scheduled++, clearTimeout() {} };
    vm.runInNewContext(engineSource.slice(start, end), context);
    callback([{ target: { matches: () => false }, addedNodes: [], removedNodes: [] }]);
    assert.equal(context.TV.dirty, true);
    assert.equal(scheduled, 0);
    callback([{ target: { matches: () => false }, addedNodes: [
        { nodeType: 1, matches: () => true }], removedNodes: [] }]);
    assert.equal(scheduled, 1);
    document.body.contains = () => false;
    callback([{ target: { matches: () => false }, addedNodes: [], removedNodes: [] }]);
    assert.equal(scheduled, 2);
});
