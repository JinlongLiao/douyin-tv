/** 电视遥控器空间焦点引擎，供系统 WebView 与内置 GeckoView 共用。 */
(function initializeTVFocus() {
    // 部分电视 WebView 的完成回调早于 body 创建，等待 DOM 就绪再初始化。
    if (!document.body) {
        document.addEventListener('DOMContentLoaded', initializeTVFocus, { once:true });
        return 'waiting-for-dom';
    }
    console.log('[TV] initFocusEngine called');
    if (window.__tvFocus && typeof window.__tvFocus.refresh === 'function') {
        // 同一文档的加载回调可重复出现，刷新候选时保留用户已经选中的控件。
        window.__tvFocus.refresh();
        window.__tvFocus.draw();
        return 'refreshed';
    }
    // 所有接口安装成功后才发布引擎，避免半初始化对象阻断后续重试。
    var TV = {};

    // 高亮框
    var oldHl = document.getElementById('tv-hl-box');
    if (oldHl) oldHl.remove();
    var hl = document.createElement('div');
    hl.id = 'tv-hl-box';
    hl.style.cssText = [
        'position:fixed','top:0','left:0','width:0','height:0',
        'border:4px solid #FE2C55','border-radius:8px',
        'box-shadow:0 0 16px rgba(254,44,85,1)',
        'pointer-events:none','z-index:2147483647',
        'transition:transform 0.15s ease-out, width 0.15s, height 0.15s',
        'display:none','background:transparent','box-sizing:border-box',
        'transform:translate(0,0)','will-change:transform,width,height'
    ].join(';');
    document.body.appendChild(hl);
    TV.hl = hl;

    // 状态提示
    var oldLabel = document.getElementById('tv-hl-label');
    if (oldLabel) oldLabel.remove();
    var label = document.createElement('div');
    label.id = 'tv-hl-label';
    label.style.cssText = [
        'position:fixed','top:8px','left:50%','transform:translateX(-50%)',
        'background:rgba(254,44,85,0.95)','color:#fff','padding:5px 14px',
        'border-radius:14px','font-size:13px','z-index:2147483647',
        'pointer-events:none','display:none','font-family:sans-serif',
        'max-width:70vw','white-space:nowrap','overflow:hidden',
        'text-overflow:ellipsis','font-weight:bold'
    ].join(';');
    document.body.appendChild(label);
    TV.label = label;

    function showLabel(text) {
        label.textContent = text;
        label.style.display = 'block';
        clearTimeout(TV.labelTimer);
        TV.labelTimer = setTimeout(function() { label.style.display = 'none'; }, 1500);
    }

    // 元素可见性 - 阈值降低
    function isVisible(el) {
        if (!el || !document.body.contains(el)) return false;
        var r = el.getBoundingClientRect();
        if (r.width < 10 || r.height < 10) return false;
        if (r.bottom < 0 || r.top > window.innerHeight) return false;
        if (r.right < 0 || r.left > window.innerWidth) return false;
        var st = window.getComputedStyle(el);
        if (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;
        return true;
    }

    function makeItem(el, type, name, actionEl, videoEl) {
        var r = el.getBoundingClientRect();
        return {
            el: el, actionEl: actionEl || el, videoEl: videoEl || null, type: type,
            name: (name || '').toString().trim().substring(0, 30),
            x: r.left + r.width / 2,
            y: r.top + r.height / 2,
            l: r.left, t: r.top,
            r: r.right, b: r.bottom,
            w: r.width, h: r.height
        };
    }

    function centerOf(el) {
        var r = el.getBoundingClientRect();
        return { x: r.left + r.width / 2, y: r.top + r.height / 2, rect: r };
    }

    function fireRemoteClick(el) {
        if (!el) return false;
        var c = centerOf(el);
        var opts = { bubbles:true, cancelable:true, composed:true, view:window, clientX:c.x, clientY:c.y };
        try { el.focus && el.focus({ preventScroll:true }); } catch(e) {}
        ['pointerdown','mousedown','pointerup','mouseup'].forEach(function(type) {
            try {
                var ev = type.indexOf('pointer') === 0 && window.PointerEvent
                    ? new PointerEvent(type, Object.assign({ pointerId:1, pointerType:'mouse', isPrimary:true }, opts))
                    : new MouseEvent(type, opts);
                el.dispatchEvent(ev);
            } catch(e) {}
        });
        try { el.click(); } catch(e) {}
        return true;
    }

    function getActionTarget(el) {
        if (!el) return null;
        // 实机卡片的点击委托在封面内层，外层 data-aweme-id 节点没有点击处理。
        // 点击封面让事件沿真实父链冒泡，不能误用作者链接或整个列表容器。
        if (el.matches('[data-aweme-id]')) return el.querySelector('img,video') || el;
        return el.closest('a[href],button,[role="button"],[role="tab"],[tabindex]') ||
               el.querySelector('a[href],button,[role="button"],[role="tab"],[tabindex]') ||
               el;
    }

    function getPrimaryVideo() {
        var best = null;
        var bestScore = 0;
        document.querySelectorAll('video').forEach(function(v) {
            var r = v.getBoundingClientRect();
            if (r.width < 80 || r.height < 80 || r.bottom < 0 || r.top > window.innerHeight) return;
            var visibleW = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
            var visibleH = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
            var score = visibleW * visibleH;
            if (score > bestScore) { bestScore = score; best = v; }
        });
        return best;
    }

    function findVideoForElement(el) {
        if (!el) return null;
        var direct = el.querySelector && el.querySelector('video');
        if (direct) return direct;
        var card = el.closest('[data-aweme-id], article, [class*="feed"], [class*="card"], [class*="video"], [class*="item"], [class*="Item"]');
        if (card) {
            var nested = card.querySelector && card.querySelector('video');
            if (nested) return nested;
        }
        var targetRect = el.getBoundingClientRect();
        var minOverlap = Math.max(1600, targetRect.width * targetRect.height * 0.18);
        var best = null, bestOverlap = 0;
        document.querySelectorAll('video').forEach(function(v) {
            var r = v.getBoundingClientRect();
            if (r.width < 80 || r.height < 80 || r.bottom < 0 || r.top > window.innerHeight) return;
            var overlapW = Math.max(0, Math.min(r.right, targetRect.right) - Math.max(r.left, targetRect.left));
            var overlapH = Math.max(0, Math.min(r.bottom, targetRect.bottom) - Math.max(r.top, targetRect.top));
            var overlap = overlapW * overlapH;
            if (overlap > bestOverlap && overlap >= minOverlap) {
                bestOverlap = overlap;
                best = v;
            }
        });
        return best;
    }

    function enterPseudoFullscreen(video) {
        document.querySelectorAll('video.tv-pseudo-fullscreen-video').forEach(function(v) {
            if (v !== video) v.classList.remove('tv-pseudo-fullscreen-video');
        });
        video.classList.add('tv-pseudo-fullscreen-video');
        document.body.classList.add('tv-video-fullscreen-active');
        try { AndroidInterface.onVideoFullscreenChanged(true); } catch(e) {}
        try { video.scrollIntoView({ block:'center', inline:'center' }); } catch(e) {}
    }

    function pauseOtherVideos(activeVideo) {
        document.querySelectorAll('video').forEach(function(v) {
            if (v !== activeVideo) {
                try { v.pause(); } catch(e) {}
                try { v.preload = 'none'; } catch(e) {}
            }
        });
    }

    function playFullscreenVideo(video) {
        if (!video) return false;
        pauseOtherVideos(video);
        try { video.muted = false; video.volume = 1; } catch(e) {}
        try { video.preload = 'auto'; } catch(e) {}
        try {
            var playResult = video.play();
            if (playResult && playResult.catch) playResult.catch(function(){});
        } catch(e) {}
        try {
            if (video.requestFullscreen) video.requestFullscreen();
            else if (video.webkitRequestFullscreen) video.webkitRequestFullscreen();
        } catch(e) {}
        enterPseudoFullscreen(video);
        return true;
    }

    function openItem(item) {
        var target = getActionTarget(item.actionEl || item.el);
        var beforeHref = location.href;
        var targetHref = target && target.getAttribute ? (target.getAttribute('href') || '') : '';
        fireRemoteClick(target);
        setTimeout(function() {
            if (targetHref && location.href === beforeHref) {
                try { location.href = targetHref; } catch(e) {}
            }
        }, 120);
        setTimeout(function() {
            var changed = location.href !== beforeHref;
            var videoPage = location.href.indexOf('/video/') > -1 || location.href.indexOf('/note/') > -1;
            if (!changed && !videoPage) return;
            var v = getPrimaryVideo();
            if (v) playFullscreenVideo(v);
        }, 1200);
    }

    function playNextVideo(currentVideo) {
        gather();
        var currentIndex = -1;
        if (TV.current) currentIndex = TV.items.indexOf(TV.current);
        if (currentIndex < 0 && currentVideo) {
            for (var i = 0; i < TV.items.length; i++) {
                if (TV.items[i].videoEl === currentVideo || findVideoForElement(TV.items[i].el) === currentVideo) {
                    currentIndex = i;
                    break;
                }
            }
        }
        var next = null;
        for (var j = Math.max(0, currentIndex + 1); j < TV.items.length; j++) {
            if (TV.items[j].type === 'video') { next = TV.items[j]; break; }
        }
        if (!next) {
            window.scrollBy({ top: window.innerHeight * 0.85, behavior: 'auto' });
            setTimeout(function() {
                gather();
                var videos = TV.items.filter(function(i) { return i.type === 'video'; });
                if (videos.length > 0) {
                    TV.current = videos[0];
                    drawBox();
                    var v = TV.current.videoEl || findVideoForElement(TV.current.el);
                    if (v) playFullscreenVideo(v); else openItem(TV.current);
                }
            }, 700);
            return 'scroll-next';
        }
        TV.current = next;
        try { next.el.scrollIntoView({ block:'center', inline:'nearest' }); } catch(e) {}
        setTimeout(function() {
            drawBox();
            var v = next.videoEl || findVideoForElement(next.el);
            if (v) playFullscreenVideo(v); else openItem(next);
        }, 150);
        return 'next';
    }
    TV.playNextVideo = playNextVideo;

    // 实机登录组件使用稳定 ID；弹窗出现后仅在弹窗内部导航。
    function findFocusScope() {
        // 网站会按设备和入口切换 flat/h5 登录布局，两种实测面板都必须限制焦点范围。
        var dialogs = document.querySelectorAll('#douyin_login_comp_flat_panel, #douyin_login_comp_h5_panel, [role="dialog"], [aria-modal="true"]');
        for (var i = dialogs.length - 1; i >= 0; i--) {
            if (isVisible(dialogs[i])) return dialogs[i];
        }
        return document;
    }
    TV.hasModal = function() { return findFocusScope() !== document; };
    TV.dismissModal = function() {
        var scope = findFocusScope();
        if (scope === document) return false;
        var title = scope.querySelector('#douyin_login_comp_flat_panel_title, #douyin_login_comp_h5_panel_title');
        var closeIcon = title && title.parentElement.querySelector('svg');
        var close = closeIcon && closeIcon.parentElement;
        close = close || scope.querySelector('[aria-label="关闭"], [aria-label="Close"], [data-e2e="close"]');
        if (!close) return false;
        fireRemoteClick(close);
        TV.dirty = true;
        return true;
    };

    // 候选只在页面内容、焦点范围或滚动变化时重建，按键复用缓存。
    TV.dirty = true;
    var gatherVersion = 0;
    function gather() {
        var scope = findFocusScope();
        var previous = TV.current;
        var sameScope = scope === TV.scope || (scope !== document && TV.scope &&
            scope.id && scope.id === TV.scope.id);
        if (!sameScope) previous = null;
        TV.scope = scope;
        TV.dirty = false;
        var items = [];
        var seen = new Set();
        function add(el, type, name, actionEl, videoEl) {
            if (!el || seen.has(el) || !isVisible(el)) return;
            seen.add(el);
            items.push(makeItem(el, type, name, actionEl, videoEl));
        }
        // 视频卡片：仅使用视频业务链接，否则点击卡片自身，避免误点作者资料。
        scope.querySelectorAll('[data-aweme-id]').forEach(function(el) {
            var link = el.querySelector('a[href*="/video/"], a[href*="/note/"]');
            var target = el;
            var video = el.querySelector('video');
            var titleEl = el.querySelector('.z72L2AHI') ||
                          el.querySelector('[class*="title"]') ||
                          el.querySelector('img[alt]') ||
                          el.querySelector('[class*="desc"]');
            var name = titleEl ?
                (titleEl.textContent || titleEl.getAttribute('alt') || '视频').trim() : '视频';
            add(target, 'video', name, link || el, video);
        });
        scope.querySelectorAll('[data-e2e*="feed"] a[href], [class*="feed"] a[href], [class*="video"] a[href], [class*="card"] a[href]').forEach(function(link) {
            var href = link.getAttribute('href') || '';
            var text = (link.textContent || link.getAttribute('aria-label') || '').trim();
            // feed 容器和作者链接不是视频卡片，必须有明确的视频业务路径。
            if (href.indexOf('/video/') < 0 && href.indexOf('/note/') < 0) return;
            var titleEl = link.querySelector('img[alt]') || link.querySelector('[class*="title"]') || link.querySelector('[class*="desc"]');
            var name = titleEl ? (titleEl.textContent || titleEl.getAttribute('alt') || text || '视频').trim() : (text || '视频');
            var card = link.closest('[data-aweme-id], article, [class*="feed"], [class*="card"], [class*="video"], [class*="item"], [class*="Item"]') || link;
            var video = card.querySelector && card.querySelector('video');
            add(card, 'video', name, link, video);
        });
        // 兜底：用 <a> 内有 /video/ 或 /note/ 路径的链接（无需 data-aweme-id）
        if (items.length === 0) {
            scope.querySelectorAll('a[href*="/video/"], a[href*="/note/"]').forEach(function(link) {
                if (!isVisible(link)) return;
                var r = link.getBoundingClientRect();
                if (r.width < 10 || r.height < 10) return;
                if (seen.has(link)) return;
                seen.add(link);
                var container = link.closest('[data-aweme-id]') ||
                                link.closest('article') ||
                                link.closest('[class*="feed"], [class*="card"], [class*="video"], [class*="item"], [class*="Item"]') ||
                                link;
                var titleEl = link.querySelector('img[alt]') || link.querySelector('[class*="title"]') || link.querySelector('[class*="desc"]');
                var name = titleEl ?
                    (titleEl.textContent || titleEl.getAttribute('alt') || '视频').trim() : '视频';
                var actionCard = container || link;
                var video = actionCard.querySelector && actionCard.querySelector('video');
                items.push(makeItem(actionCard, 'video', name, link, video));
            });
        }
        // 顶部 tab：支持 role="tab" 以及半糖 UI 组件的 tab
        scope.querySelectorAll('[role="tab"]').forEach(function(el) {
            var name = (el.textContent || '').trim();
            if (name) add(el, 'toptab', name);
        });
        scope.querySelectorAll('[aria-selected], [data-e2e*="tab"], nav a, header a').forEach(function(el) {
            var name = (el.textContent || el.getAttribute('aria-label') || '').trim();
            if (name && name.length < 20) add(el, 'toptab', name);
        });
        // 兜底：顶部导航链接（.semi-tabs-tab 类名的 tab 元素）
        if (items.filter(function(i){return i.type==='toptab'}).length === 0) {
            scope.querySelectorAll('.semi-tabs-tab, .web-nav-wrapper a, .NavBar-Container a, [class*="nav"] a, [class*="Nav"] a').forEach(function(el) {
                var name = (el.textContent || el.getAttribute('aria-label') || '').trim();
                if (name && name.length < 20) add(el, 'toptab', name);
            });
        }
        // 左侧菜单：.tab-discover 包裹的 <a>（不是 .tab-discover div 本身）
        scope.querySelectorAll('.tab-discover a').forEach(function(link) {
            var nameEl = link.querySelector('.Ou5DxC0w') || link.querySelector('span');
            var name = nameEl ? nameEl.textContent.trim() : (link.textContent.trim() || '菜单');
            add(link, 'leftnav', name);
        });
        // 兜底：左侧导航链接
        if (items.filter(function(i){return i.type==='leftnav'}).length === 0) {
            scope.querySelectorAll('.sidebar-container a, [class*="Sidebar"] a, [class*="side-nav"] a, aside a, [class*="menu"] a, [class*="Menu"] a').forEach(function(el) {
                var name = (el.textContent || el.getAttribute('aria-label') || '').trim();
                if (name && name.length < 20) add(el, 'leftnav', name);
            });
        }

        // 网站常规按钮、输入框和弹窗操作也必须能被遥控器选中。
        scope.querySelectorAll('a[href], button, input, textarea, select, [role="button"], [tabindex]').forEach(function(el) {
            if (el.disabled || el.getAttribute('aria-disabled') === 'true') return;
            // tabindex 允许面板承接原生键盘焦点，但面板不是可点击的业务控件。
            if (el.getAttribute('role') === 'tabpanel' || el.getAttribute('tabindex') === '-1') return;
            add(el, 'control', el.getAttribute('aria-label') || el.textContent || el.getAttribute('placeholder') || '控件');
        });
        if (scope !== document) {
            // 登录切换和关闭图标是普通 span/div，网页未给出 button 语义。
            scope.querySelectorAll('span').forEach(function(el) {
                var text = el.textContent.trim();
                if (text === '密码登录' || text === '验证码登录' || text === '扫码登录') {
                    add(el, 'control', text);
                }
            });
            var title = scope.querySelector('#douyin_login_comp_flat_panel_title, #douyin_login_comp_h5_panel_title');
            var closeIcon = title && title.parentElement.querySelector('svg');
            if (closeIcon) add(closeIcon.parentElement, 'control', '关闭登录弹窗');
            add(scope.querySelector('#douyin_login_comp_btn_id'), 'control', '登录');
        }
        TV.items = items;
        // React 可能替换同一个输入框或切换项的 DOM；按业务名称恢复选中项。
        TV.current = previous ? items.find(function(item) {
            return item.el === previous.el ||
                (item.type === previous.type && item.name === previous.name);
        }) || null : null;
        var vCount = items.filter(function(i){return i.type==='video'}).length;
        var tCount = items.filter(function(i){return i.type==='toptab'}).length;
        var lCount = items.filter(function(i){return i.type==='leftnav'}).length;
        if (vCount !== TV._lastVideoCount || tCount !== TV._lastTabCount || lCount !== TV._lastNavCount) {
            gatherVersion++;
            TV._lastVideoCount = vCount;
            TV._lastTabCount = tCount;
            TV._lastNavCount = lCount;
            console.log('[TV] gather v' + gatherVersion + ': video=' + vCount + ' tab=' + tCount + ' nav=' + lCount);
        }
        return items.length;
    }
    TV.refresh = gather;

    // 轻量版 gather：只更新 current 坐标，不重新查询 DOM
    function gatherLight() {
        if (!TV.items) return;
        var changed = false;
        for (var i = 0; i < TV.items.length; i++) {
            var it = TV.items[i];
            if (!it.el || !document.body.contains(it.el)) continue;
            var r = it.el.getBoundingClientRect();
            if (r.width === 0 || r.height === 0) continue;
            var nx = r.left + r.width / 2;
            var ny = r.top + r.height / 2;
            if (Math.abs(nx - it.x) > 2 || Math.abs(ny - it.y) > 2) {
                it.x = nx; it.y = ny;
                it.l = r.left; it.t = r.top;
                it.r = r.right; it.b = r.bottom;
                it.w = r.width; it.h = r.height;
                changed = true;
            }
        }
        if (changed) gatherVersion++;
        return gatherVersion;
    }
    TV.gatherLight = gatherLight;

    function drawBox() {
        if (!TV.current) { hl.style.display = 'none'; return; }
        var el = TV.current.el;
        if (!el || !document.body.contains(el)) { hl.style.display = 'none'; return; }
        var r = el.getBoundingClientRect();
        if (r.width < 5 || r.height < 5) { hl.style.display = 'none'; return; }
        // 实时更新 current 的坐标
        TV.current.x = r.left + r.width / 2;
        TV.current.y = r.top + r.height / 2;
        TV.current.l = r.left; TV.current.t = r.top;
        TV.current.r = r.right; TV.current.b = r.bottom;
        TV.current.w = r.width; TV.current.h = r.height;
        hl.style.display = 'block';
        hl.style.width = (r.width + 8) + 'px';
        hl.style.height = (r.height + 8) + 'px';
        hl.style.transform = 'translate(' + (r.left - 4) + 'px,' + (r.top - 4) + 'px)';
        var typeLabel = { leftnav:'菜单', toptab:'分类', video:'视频' };
        showLabel('▶ ' + (typeLabel[TV.current.type] || '') + ': ' + TV.current.name);
    }
    TV.draw = drawBox;

    // 2D 空间最近邻：在 (dx,dy) 方向找最近元素
    // 用扇形权重：方向上的距离 + 偏离方向的距离 * 2
    function findGridNeighbor(cur, dx, dy) {
        if (!TV.items || TV.items.length === 0 || !cur || !cur.el) return null;
        var curR = cur.el.getBoundingClientRect();
        var cx = curR.left + curR.width / 2;
        var cy = curR.top + curR.height / 2;
        var curArea = Math.max(1, curR.width * curR.height);
        var best = null;
        var bestScore = Infinity;
        for (var i = 0; i < TV.items.length; i++) {
            var it = TV.items[i];
            if (it.el === cur.el || !document.body.contains(it.el)) continue;
            var r = it.el.getBoundingClientRect();
            if (r.width < 10 || r.height < 10) continue;
            var ix = r.left + r.width / 2;
            var iy = r.top + r.height / 2;
            // 先严格过滤方向，避免重叠元素让焦点反向跳动。
            if ((dx > 0 && ix <= cx) || (dx < 0 && ix >= cx) ||
                (dy > 0 && iy <= cy) || (dy < 0 && iy >= cy)) continue;
            var primary = 0, secondary = 0, overlap = 0, overlapRatio = 0, gap = 0;
            if (dx > 0) {
                gap = r.left - curR.right;
                if (gap < -Math.min(80, curR.width * 0.18) && ix <= cx) continue;
                primary = Math.max(0, gap);
                secondary = Math.abs(iy - cy);
                overlap = Math.max(0, Math.min(r.bottom, curR.bottom) - Math.max(r.top, curR.top));
                overlapRatio = overlap / Math.max(1, Math.min(r.height, curR.height));
            } else if (dx < 0) {
                gap = curR.left - r.right;
                if (gap < -Math.min(80, curR.width * 0.18) && ix >= cx) continue;
                primary = Math.max(0, gap);
                secondary = Math.abs(iy - cy);
                overlap = Math.max(0, Math.min(r.bottom, curR.bottom) - Math.max(r.top, curR.top));
                overlapRatio = overlap / Math.max(1, Math.min(r.height, curR.height));
            } else if (dy > 0) {
                gap = r.top - curR.bottom;
                if (gap < -Math.min(80, curR.height * 0.22) && iy <= cy) continue;
                primary = Math.max(0, gap);
                secondary = Math.abs(ix - cx);
                overlap = Math.max(0, Math.min(r.right, curR.right) - Math.max(r.left, curR.left));
                overlapRatio = overlap / Math.max(1, Math.min(r.width, curR.width));
            } else if (dy < 0) {
                gap = curR.top - r.bottom;
                if (gap < -Math.min(80, curR.height * 0.22) && iy >= cy) continue;
                primary = Math.max(0, gap);
                secondary = Math.abs(ix - cx);
                overlap = Math.max(0, Math.min(r.right, curR.right) - Math.max(r.left, curR.left));
                overlapRatio = overlap / Math.max(1, Math.min(r.width, curR.width));
            }
            var sizePenalty = Math.abs((r.width * r.height) - curArea) / curArea * 10;
            var typeBonus = it.type === cur.type ? -30 : 20;
            var score = primary * 2 + secondary * (overlapRatio > 0.18 ? 0.55 : 1.25) - overlapRatio * 180 + sizePenalty + typeBonus;
            if (score < bestScore) {
                bestScore = score;
                best = it;
            }
        }
        return best;
    }
    TV.findGridNeighbor = findGridNeighbor;

    function findNearestInDirection(cur, dx, dy) {
        if (!TV.items || TV.items.length === 0) return null;
        // 实时刷新 cur 位置
        var curR = cur.el.getBoundingClientRect();
        var cx = curR.left + curR.width / 2;
        var cy = curR.top + curR.height / 2;

        var best = null;
        var bestScore = Infinity;

        for (var i = 0; i < TV.items.length; i++) {
            var it = TV.items[i];
            if (it.el === cur.el) continue;
            if (!document.body.contains(it.el)) continue;
            var r = it.el.getBoundingClientRect();
            if (r.width === 0 || r.height === 0) continue;
            var ix = r.left + r.width / 2;
            var iy = r.top + r.height / 2;
            var ddx = ix - cx;
            var ddy = iy - cy;

            // 方向过滤：只需轻微偏移
            if (dy > 0) {
                if (ddy < curR.height * 0.15) continue;
            } else if (dy < 0) {
                if (ddy > -curR.height * 0.15) continue;
            } else if (dx > 0) {
                if (ddx < curR.width * 0.15) continue;
            } else if (dx < 0) {
                if (ddx > -curR.width * 0.15) continue;
            }

            // 评分：主方向距离 + 偏移方向距离 * 2
            var primary, secondary;
            if (dy !== 0) {
                primary = Math.abs(ddy);
                secondary = Math.abs(ddx);
            } else {
                primary = Math.abs(ddx);
                secondary = Math.abs(ddy);
            }
            // 偏离方向超过屏幕一半就放弃
            if (dy !== 0 && secondary > window.innerWidth * 0.6) continue;
            if (dx !== 0 && secondary > window.innerHeight * 0.6) continue;

            var score = primary + secondary * 1.5;
            if (score < bestScore) {
                bestScore = score;
                best = it;
            }
        }
        return best;
    }

    function moveFocus(dx, dy) {
        // 弹窗范围立即更新，其余按键复用候选，避免反复扫描整个页面。
        if (TV.dirty || TV.scope !== findFocusScope()) gather();
        gatherLight();

        if (!TV.items || TV.items.length === 0) {
            window.scrollBy(0, dy * 200);
            return false;
        }

        var cur = TV.current;
        if (cur && (!document.body.contains(cur.el) || !isVisible(cur.el))) {
            cur = null;
            TV.current = null;
        }

        if (!cur) {
            var cx = window.innerWidth / 2;
            var cy = window.innerHeight / 2;
            var best = null, bestD = Infinity;
            TV.items.forEach(function(it) {
                var d = Math.hypot(it.x - cx, it.y - cy);
                if (d < bestD) { bestD = d; best = it; }
            });
            if (best) {
                TV.current = best;
                best.el.scrollIntoView({ block:'nearest', inline:'nearest', behavior:'auto' });
                drawBox();
            }
            return true;
        }

        var next = findGridNeighbor(cur, dx, dy) || findNearestInDirection(cur, dx, dy);

        if (!next) {
            // 没找到：如果是向下且当前是视频，尝试滚动
            if (dy > 0 && cur.type === 'video') {
                window.scrollBy({ top: window.innerHeight * 0.7, behavior: 'auto' });
                setTimeout(function() {
                    gather();
                    gatherLight();
                    var nxt = findNearestInDirection(TV.current, 0, 1);
                    if (nxt) {
                        TV.current = nxt;
                        nxt.el.scrollIntoView({ block:'center' });
                        setTimeout(drawBox, 60);
                    } else {
                        drawBox();
                    }
                }, 500);
                return true;
            }
            drawBox();
            return false;
        }

        TV.current = next;
        next.el.scrollIntoView({ block:'nearest', inline:'nearest', behavior:'auto' });
        drawBox();
        return true;
    }
    TV.move = moveFocus;

    function activate() {
        if (TV.dirty || TV.scope !== findFocusScope()) gather();
        if (!TV.current) { moveFocus(0, 0); return 'init'; }
        var item = TV.current;
        var el = item.el;
        if (!document.body.contains(el)) {
            console.log('[TV] current el detached, refresh & reselect');
            TV.current = null;
            moveFocus(0, 0);
            return 'reset';
        }
        console.log('[TV] activate ' + item.type + ': ' + item.name);

        if (item.type === 'video') {
            // 视频卡片：发送 pointer/mouse/click 事件链，兼容抖音 SPA 的点击路由。
            var selectedVideo = item.videoEl || null;
            if (!selectedVideo) selectedVideo = findVideoForElement(el);
            if (playFullscreenVideo(selectedVideo)) {
                showLabel('▶ 全屏播放: ' + item.name);
                return 'video-fullscreen';
            }
            openItem(item);
            showLabel('▶ 打开: ' + item.name);
            // 页面跳转后重置焦点
            setTimeout(function() {
                TV.current = null;
            }, 1500);
            return 'video-clicked';
        }

        // 输入控件需要真实 DOM 焦点，才能调起电视输入法。
        if (el.matches('input, textarea, select')) {
            el.focus();
            el.click();
            return 'input-focused';
        }
        // 顶部 tab / 左侧菜单及普通控件
        var inner = getActionTarget(el);
        var beforeHref = location.href;
        fireRemoteClick(inner);
        setTimeout(function() {
            var href = inner && inner.getAttribute ? inner.getAttribute('href') : '';
            if (href && location.href === beforeHref) {
                try { location.href = href; } catch(e) {}
            }
        }, 120);

        showLabel('✓ ' + item.name);
        // 分类切换后，等 DOM 刷新后自动跳到第一个视频
        setTimeout(function() {
            gather();
            gatherLight();
            var videos = TV.items.filter(function(i) { return i.type === 'video'; });
            if (videos.length > 0 && (item.type === 'leftnav' || item.type === 'toptab')) {
                TV.current = null;
            }
        }, 500);
        return 'clicked';
    }
    TV.activate = activate;

    // 节流的 DOM 变化监听
    var refreshTimer;
    var observer = new MutationObserver(function(records) {
        // 高亮提示的文字更新不是网页内容变化，避免刷新自身导致循环。
        if (records.every(function(record) {
            return record.target === label || label.contains(record.target) ||
                   record.target === hl || hl.contains(record.target);
        })) return;
        TV.dirty = true;
        // 背景视频、计数与卡片动画不需要查询弹窗可见性；只对弹窗结构变化或焦点移除立即迁移。
        // 其余变化保留 dirty，由下一次实际按键更新候选，避免空闲时反复触发页面布局。
        var dialogChanged = records.some(function(record) {
            var scope = TV.scope;
            if (scope && scope !== document &&
                (scope === record.target || scope.contains(record.target) ||
                 (record.target.contains && record.target.contains(scope)))) return true;
            var selector = '#douyin_login_comp_flat_panel, #douyin_login_comp_h5_panel, [role="dialog"], [aria-modal="true"]';
            if (record.target.matches && record.target.matches(selector)) return true;
            return Array.from(record.addedNodes || []).concat(Array.from(record.removedNodes || [])).some(function(node) {
                return node.nodeType === 1 &&
                    (node.matches(selector) || node.querySelector(selector));
            });
        });
        if (!dialogChanged && TV.current && document.body.contains(TV.current.el)) return;
        clearTimeout(refreshTimer);
        refreshTimer = setTimeout(function() {
            // 弹窗出现、关闭或当前控件被移除时立即迁移焦点；
            // 背景动画只标记候选过期，不在空闲时反复遍历页面。
            if (TV.scope !== findFocusScope() || !TV.current ||
                !document.body.contains(TV.current.el)) {
                gather();
                moveFocus(0, 0);
            }
        }, 100);
    });
    observer.observe(document.body, { childList:true, subtree:true, attributes:true, attributeFilter:['class', 'hidden', 'aria-hidden'] });
    TV.observer = observer;

    var scrollTimer;
    var scrollHandler = function() {
        TV.dirty = true;
        clearTimeout(scrollTimer);
        scrollTimer = setTimeout(function() {
            if (TV.current) drawBox();
        }, 16);
    };
    window.addEventListener('scroll', scrollHandler, { passive:true, capture:true });
    document.addEventListener('scroll', scrollHandler, { passive:true, capture:true });
    TV.scrollHandler = scrollHandler;

    // 兜底标记过期，避免空闲时周期性扫描 DOM。
    TV.refreshInterval = setInterval(function() { TV.dirty = true; }, 8000);

    var resizeHandler = function() {
        gather();
        if (TV.current) drawBox();
    };
    window.addEventListener('resize', resizeHandler);
    TV.resizeHandler = resizeHandler;

    TV.cleanup = function() {
        try { observer.disconnect(); } catch(e) {}
        try { window.removeEventListener('scroll', scrollHandler); } catch(e) {}
        try { document.removeEventListener('scroll', scrollHandler); } catch(e) {}
        try { window.removeEventListener('resize', resizeHandler); } catch(e) {}
        try { clearInterval(TV.refreshInterval); } catch(e) {}
        clearTimeout(refreshTimer);
        clearTimeout(scrollTimer);
        clearTimeout(TV.labelTimer);
        try { hl.remove(); label.remove(); } catch(e) {}
    };

    // 启动：候选收集成功后再让 Activity 可调用该引擎。
    gather();
    window.__tvFocus = TV;
    setTimeout(function() {
        moveFocus(0, 0);
    }, 300);

    return 'ok:' + TV.items.length;
})();
