/** GeckoView 内置扩展的封闭遥控命令协议；性能采样仅由调试 APK 请求。 */
(function () {
    'use strict';

    /**
     * 播放弹层内只选择激活播放器，避免其加载阶段误选仍有尺寸的背景直播预览。
     * 主视频尚未显示时仍返回该节点，让调用方报告未就绪；普通页面按可见面积选择。
     */
    function findVisibleVideo() {
        var modal = findBrowserPlaybackModal();
        var scope = modal ? (findBrowserActivePlayer(modal) || modal) : document;
        var videos = scope.querySelectorAll('video');
        var selected = null;
        var selectedArea = 0;
        videos.forEach(function (video) {
            var rect = video.getBoundingClientRect();
            var width = Math.max(0, Math.min(innerWidth, rect.right) - Math.max(0, rect.left));
            var height = Math.max(0, Math.min(innerHeight, rect.bottom) - Math.max(0, rect.top));
            if (width * height > selectedArea) {
                selectedArea = width * height;
                selected = video;
            }
        });
        return selected || (scope !== document && videos.length ? videos[0] : null);
    }

    /** 关闭网站实际显示的登录弹窗；鼠标导航不再安装空间焦点引擎。 */
    function dismissBrowserDialog() {
        var panels = document.querySelectorAll('#douyin_login_comp_flat_panel, #douyin_login_comp_h5_panel, [role="dialog"], [aria-modal="true"]');
        for (var index = panels.length - 1; index >= 0; index--) {
            var panel = panels[index];
            var rect = panel.getBoundingClientRect();
            if (!rect.width || !rect.height) continue;
            var title = panel.querySelector('#douyin_login_comp_flat_panel_title, #douyin_login_comp_h5_panel_title');
            var icon = title && title.parentElement.querySelector('svg');
            var close = (icon && icon.parentElement) || panel.querySelector('[aria-label="关闭"], [aria-label="Close"], [data-e2e="close"]');
            if (close) { close.click(); return true; }
        }
        return false;
    }

    /** 使用实机播放器切换箭头，列表页按 data-aweme-id 切换卡片；没有目标时明确返回失败。 */
    function switchBrowserVideo(direction) {
        // 网站会预加载邻居视频，并隐藏控制条；只操作激活播放器，不要求按钮当前可见。
        var modal = findBrowserPlaybackModal();
        var activePlayer = findBrowserActivePlayer(modal);
        var arrow = (activePlayer || modal || document).querySelector(direction > 0 ?
            '[data-e2e="video-switch-next-arrow"]' : '[data-e2e="video-switch-prev-arrow"]');
        if (arrow && arrow.getAttribute('aria-disabled') !== 'true') {
            arrow.click();
            return 'video-switched';
        }
        var cards = Array.from(document.querySelectorAll('[data-aweme-id]'));
        var video = findVisibleVideo();
        var currentCard = video && video.closest('[data-aweme-id]');
        var currentId = new URL(location.href).searchParams.get('modal_id') ||
            ((location.pathname.match(/\/video\/(\d+)/) || [])[1]);
        var currentIndex = cards.findIndex(function (card) {
            return card === currentCard || (currentId && card.getAttribute('data-aweme-id') === currentId);
        });
        if (currentIndex < 0) return 'video-switch-not-found';
        var next = cards[currentIndex + direction];
        if (!next) return 'video-switch-not-found';
        next.scrollIntoView({ block: 'center', behavior: 'auto' });
        var target = next.querySelector('a[href*="/video/"], a[href*="/note/"], img, video') || next;
        target.click();
        return 'video-switched';
    }

    /** 仅保留返回、播放和诊断协议；方向移动和普通点击完全走 Android 原生鼠标事件。 */
    function performRemoteCommand(command) {
        if (command.action === 'back' || command.action === 'exit-video') {
            if (dismissBrowserDialog()) return 'dialog-closed';
            var videoModal = findBrowserPlaybackModal();
            if (videoModal) {
                var modalBounds = videoModal.getBoundingClientRect();
                if (modalBounds.width > 0 && modalBounds.height > 0) {
                    // 实机关闭按钮在弹层外的同级宿主，无 aria-label/data-e2e；已验证该点击回到原列表。
                    var modalClose = findBrowserPlaybackCloseButton(videoModal);
                    if (modalClose) { modalClose.click(); return 'video-modal-closed'; }
                    // 网站改版找不到关闭按钮时保留播放页，绝不把全屏返回降级为退出应用。
                    return 'video-close-not-found';
                }
            }
            var pseudo = document.querySelector('video.tv-pseudo-fullscreen-video');
            if (pseudo) {
                pseudo.classList.remove('tv-pseudo-fullscreen-video');
                document.body.classList.remove('tv-video-fullscreen-active');
                return 'fullscreen-closed';
            }
            if (command.action === 'exit-video') return 'fullscreen-closed';
            // 播放弹层尚在加载时，也不能把它误认成主页面并触发退出。
            if (new URL(location.href).searchParams.has('modal_id')) return 'video-close-not-found';
            return location.pathname === '/' || location.pathname === '/jingxuan' ? 'root-back' : 'browser-back';
        }
        if (command.action === 'switch-video') return switchBrowserVideo(command.dy);
        var video = findVisibleVideo();
        if (!video) return 'video-not-found';
        if (command.action === 'seek') {
            if (!isFinite(video.duration) || video.duration <= 0) return 'video-not-seekable';
            video.currentTime = Math.max(0, Math.min(video.duration, video.currentTime + command.dx * 10));
            publishBrowserMediaState(video, true);
            return 'video-seek';
        }
        if (command.action === 'toggle-playback') {
            if (video.paused) video.play().catch(reportPlaybackError); else video.pause();
            publishBrowserMediaState(video, true);
            return 'video-toggled';
        }
        return 'unknown-command';
    }

    /** 状态最多每秒更新一次；主动快进立即更新，并让原生控制条显示四秒。 */
    var lastMediaStateAt = 0;
    function publishBrowserMediaState(video, reveal) {
        if (!video || video.tagName !== 'VIDEO') return;
        var now = Date.now();
        if (!reveal && now - lastMediaStateAt < 1000) return;
        // 播放弹层与背景列表可同时保留 video，只汇报实际可见的主视频。
        if (!reveal && video !== findVisibleVideo()) return;
        lastMediaStateAt = now;
        tvNativePort.postMessage({ type: 'media-state', position: video.currentTime,
            duration: isFinite(video.duration) ? video.duration : 0, paused: video.paused, reveal: !!reveal });
    }
    document.addEventListener('timeupdate', function (event) { publishBrowserMediaState(event.target, false); }, true);

    /** 保留播放失败阶段和异常堆栈，供 native 日志定位解码或网页兼容问题。 */
    function reportPlaybackError(error) {
        tvNativePort.postMessage({ type: 'error', stage: 'video-play', error: String(error), stack: error.stack || '' });
    }

    /** 采样八秒帧回调、事件循环延迟、长任务和缓冲状态；不读取表单、Cookie 或账户信息。 */
    function collectBrowserPerformance() {
        var started = performance.now();
        var visibleAtStart = !document.hidden;
        var visibilityChanges = [];
        function recordVisibilityChange() { visibilityChanges.push(document.visibilityState); }
        document.addEventListener('visibilitychange', recordVisibilityChange);
        var previous = started;
        var frames = 0;
        var gaps = [];
        var longTasks = [];
        var running = true;
        var observer = null;
        var eventLoopDelays = [];
        var eventLoopTimer;
        // Firefox 不支持 Long Tasks API 时，独立计时可区分线程阻塞与低频画面刷新。
        // 每次仅安排下一个 100 ms 回调；采样结束主动取消，不留下常驻计时任务。
        function sampleBrowserEventLoopDelay() {
            var expected = performance.now() + 100;
            eventLoopTimer = setTimeout(function () {
                if (!running) return;
                eventLoopDelays.push(Math.max(0, performance.now() - expected));
                sampleBrowserEventLoopDelay();
            }, 100);
        }
        sampleBrowserEventLoopDelay();
        if (typeof PerformanceObserver !== 'undefined' && PerformanceObserver.supportedEntryTypes.includes('longtask')) {
            observer = new PerformanceObserver(function (list) {
                list.getEntries().forEach(function (entry) { longTasks.push(entry.duration); });
            });
            observer.observe({ entryTypes: ['longtask'] });
        }
        function measureFrame() {
            if (!running) return;
            var now = performance.now();
            gaps.push(now - previous);
            previous = now;
            frames++;
            requestAnimationFrame(measureFrame);
        }
        requestAnimationFrame(measureFrame);
        setTimeout(function () {
            running = false;
            clearTimeout(eventLoopTimer);
            if (observer) observer.disconnect();
            document.removeEventListener('visibilitychange', recordVisibilityChange);
            var elapsed = performance.now() - started;
            gaps.sort(function (left, right) { return left - right; });
            eventLoopDelays.sort(function (left, right) { return left - right; });
            tvNativePort.postMessage({
                type: 'performance', ua: navigator.userAgent, platform: navigator.platform,
                sampleStartedAt: performance.timeOrigin + started,
                visibleEntireSample: visibleAtStart && !document.hidden && visibilityChanges.every(function (state) { return state === 'visible'; }),
                visibilityChanges: visibilityChanges,
                seconds: elapsed / 1000, fps: frames * 1000 / elapsed,
                frameGapP95: gaps[Math.floor(gaps.length * 0.95)] || 0,
                maxFrameGap: Math.max(0, ...gaps), longTaskSupported: !!observer,
                // 调度延迟单位为毫秒；浏览器计时节流也会影响读数，不等同于 LongTask 时长。
                eventLoopSamples: eventLoopDelays.length,
                eventLoopDelayP95: eventLoopDelays[Math.floor(eventLoopDelays.length * 0.95)] || 0,
                maxEventLoopDelay: Math.max(0, ...eventLoopDelays),
                longTasks: longTasks.length, longTaskMs: longTasks.reduce(function (sum, duration) { return sum + duration; }, 0),
                elements: document.querySelectorAll('*').length, viewport: [innerWidth, innerHeight],
                videos: Array.from(document.querySelectorAll('video')).map(function (video) {
                    var quality = video.getVideoPlaybackQuality();
                    return { ready: video.readyState, network: video.networkState, paused: video.paused,
                        time: video.currentTime, buffered: video.buffered.length ? video.buffered.end(video.buffered.length - 1) : 0,
                        width: video.videoWidth, height: video.videoHeight,
                        frames: quality.totalVideoFrames, droppedFrames: quality.droppedVideoFrames };
                })
            });
        }, 8000);
    }

    tvNativePort.onMessage.addListener(function (command) {
        if (command.action === 'probe') { collectBrowserPerformance(); return; }
        var started = performance.now();
        var queueMs = Date.now() - command.sentAt;
        try {
            var result = performRemoteCommand(command);
            tvNativePort.postMessage({ type: 'result', sequence: command.sequence, result: result,
                queueMs: queueMs, jsMs: performance.now() - started });
        } catch (error) {
            tvNativePort.postMessage({ type: 'error', stage: 'remote-command', action: command.action, sequence: command.sequence,
                error: String(error), stack: error.stack || '' });
        }
    });
    tvNativePort.postMessage({ type: 'ready', ua: navigator.userAgent, platform: navigator.platform });
})();
