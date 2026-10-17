// 使用确定性计时器验证诊断能区分无帧回调与事件循环阻塞，并在结束时释放计时任务。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

/** 执行真实扩展；帧回调故意不触发，以独立验证计时器调度延迟。 */
function createPerformanceProbe() {
    let now = 0;
    let nextTimer = 0;
    let listener;
    const timers = new Map();
    const messages = [];
    const context = {
        document: { hidden: false, addEventListener() {}, removeEventListener() {}, querySelectorAll: () => [] },
        navigator: { userAgent: 'Android tablet', platform: 'Linux' },
        performance: { now: () => now, timeOrigin: 1000 },
        innerWidth: 1920, innerHeight: 1080,
        requestAnimationFrame() {},
        setTimeout: (callback, delay) => {
            const id = ++nextTimer;
            timers.set(id, { callback, due: now + delay });
            return id;
        },
        clearTimeout: id => timers.delete(id),
        tvNativePort: { postMessage: message => messages.push(message),
            onMessage: { addListener: callback => { listener = callback; } } }
    };
    vm.runInNewContext(fs.readFileSync('app/src/main/assets/browser-extension/remote-controls.js', 'utf8'), context);
    listener({ action: 'probe' });
    return {
        messages, timers,
        /** 按截止时间执行任务；blockedUntil 模拟线程在该时间前无法执行已到期回调。 */
        advanceProbeTime(target, blockedUntil = 0) {
            while (timers.size) {
                const [id, timer] = [...timers.entries()].sort((a, b) => a[1].due - b[1].due)[0];
                if (timer.due > target) break;
                now = Math.max(now, timer.due, blockedUntil);
                timers.delete(id);
                timer.callback();
            }
            now = target;
        }
    };
}

test('没有帧回调但计时器正常时，不误报事件循环阻塞且结束后无残留计时任务', () => {
    const probe = createPerformanceProbe();
    probe.advanceProbeTime(8000);
    const result = probe.messages.find(message => message.type === 'performance');
    assert.equal(result.fps, 0);
    assert.equal(result.maxEventLoopDelay, 0);
    assert.ok(result.eventLoopSamples >= 70);
    assert.equal(probe.timers.size, 0);
});

test('线程阻塞六百毫秒时，诊断报告实际调度延迟并正确停止采样', () => {
    const probe = createPerformanceProbe();
    probe.advanceProbeTime(700, 700);
    probe.advanceProbeTime(8000);
    const result = probe.messages.find(message => message.type === 'performance');
    assert.equal(result.maxEventLoopDelay, 600);
    assert.equal(result.longTaskSupported, false);
    assert.equal(probe.timers.size, 0);
});
