// === barrage.js ===

    function initializeBarrageWebSocket() {
        if (!currentUser) return;

        try {
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const wsUrl = `${protocol}//${window.location.host}/ws/eeg?userId=${currentUser.userId}`;

            barrageWebSocket = new WebSocket(wsUrl);

            barrageWebSocket.onopen = function() {
                wsReconnectAttempts = 0; // 连接成功，重置计数器
            };

            barrageWebSocket.onmessage = function(event) {
                try {
                    const data = JSON.parse(event.data);
                    // 【顶部状态栏】累加数据包计数
                    window._topbarPacketTotal = (window._topbarPacketTotal || 0) + 1;
                    if (data.type === 'REAL_TIME_BARRAGE' && data.data.type === 'NEW_BARRAGE') {
                        handleNewBarrage(data.data.barrage);
                    } else if (data.type === 'BARRAGE_DELETION' && data.data.type === 'BARRAGE_DELETED') {
                        handleBarrageDeleted(data.data.barrageId);
                    }
                    
                    // 【大屏强化】将数据抛给 live-dashboard.js 去渲染图表
                    if (window.updateDashboardData && data.type) {
                        console.debug('[WS→Dashboard]', data.type, Array.isArray(data.data) ? `[${data.data.length} items]` : typeof data.data);
                        window.updateDashboardData(data.type, data.data);
                    }
                } catch (error) {
                    console.error('解析WebSocket消息失败:', error);
                }
            };

            barrageWebSocket.onclose = function(event) {
                console.warn('⚠️ 弹幕WebSocket连接已关闭, 代码:', event.code, '原因:', event.reason);
                
                if (isBarrageActive && wsReconnectAttempts < WS_MAX_RECONNECT) {
                    wsReconnectAttempts++;
                    // 指数退避：5s → 10s → 20s → 40s → 80s
                    const delay = Math.min(5000 * Math.pow(2, wsReconnectAttempts - 1), 80000);
                    setTimeout(() => {
                        if (isBarrageActive) {
                            initializeBarrageWebSocket();
                        }
                    }, delay);
                } else if (wsReconnectAttempts >= WS_MAX_RECONNECT) {
                    console.error('❌ WebSocket 重连次数已达上限，已停止重连');
                    showAlert('warning', '实时推送连接中断，请手动刷新页面');
                }
            };

            barrageWebSocket.onerror = function(error) {
                console.error('❌ 弹幕WebSocket连接发生错误:', error);
            };

        } catch (error) {
            console.error('❌ 初始化弹幕WebSocket失败:', error);
        }
    }

// 处理新弹幕：串行模式 + 始终追新
    function handleNewBarrage(barrage) {
        if (!barrage) return;

        // 1. 历史记录管理 (仅在不重复时更新)
        const isDuplicate = barrageHistory.some(b => b.id === barrage.id);
        if (!isDuplicate) {
            barrageHistory.unshift(barrage);
            if (barrageHistory.length > 50) barrageHistory.pop();
            if (isSidebarExpanded) updateBarrageHistoryDisplay();

            // 【通知徽标】如果用户不在通知面板，显示红点提醒
            const notifPanel = document.getElementById('panel-notifs');
            const isNotifVisible = notifPanel && notifPanel.style.display !== 'none';
            if (!isNotifVisible && typeof showNotifBadge === 'function') {
                showNotifBadge();
            }
            // 同步更新通知面板内的列表
            updateBarrageHistoryDisplay();
        }

        // 2. 弹幕飘动显示 (无论侧边栏是否有，只要新到就必须飘)
        // 【关键修复】劫持逻辑：将新来的存为 pending，它会“踢掉”还没来得及播的旧消息
        window.pendingBarrage = barrage;

        // 如果目前屏幕上是空的，立即开启“接力”
        if (!window.isBarrageShowing) {
            triggerNextBarrage();
        } else {
        }
    }

    function triggerNextBarrage() {
        // 如果没人排队或者有人正在播，就原地待命
        if (!window.pendingBarrage || window.isBarrageShowing) return;

        const latest = window.pendingBarrage;
        window.pendingBarrage = null; // 取走后清空
        createFloatingBarrage(latest);
    }

    function createFloatingBarrage(barrage) {
        // 【设置面板】如果用户关闭了弹幕飘动，跳过动画但保留通知记录
        if (window._barrageEnabled === false) {
            window.isBarrageShowing = false;
            return;
        }

        const container = DOM_CACHE.barrageFloatArea; // 【修复】使用 HTML 中真实的 ID
        if (!container) {
            console.error('❌ 未找到弹幕容器: barrageFloatArea');
            window.isBarrageShowing = false; // 容错：防止锁死
            return;
        }

        // 占场锁：开启
        window.isBarrageShowing = true;

        const stateColor = getStateColor(barrage.primaryState);
        const stateDesc = getStateDescription(barrage.primaryState);
        
        // 【视觉净化】剥离所有末尾的时间字符串 和 开头重复的状态标签
        const rawContent = barrage.content || '未检测到有效分析';
        const pureContent = rawContent
            .replace(/\s*\[\d{2}:\d{2}:\d{2}.*?\]/g, '')  // 去掉末尾时间
            .replace(/^【[^】]*】\s*/g, '')                   // 去掉开头状态标签
            .trim();

        const barrageEl = document.createElement('div');
        barrageEl.className = 'floating-barrage';
        barrageEl.style.borderLeftColor = stateColor;
        barrageEl.innerHTML = `
            <span style="color: ${stateColor}; margin-right: 12px; font-weight: 900;">【${stateDesc}】</span>
            <span>${pureContent}</span>
        `;

        container.appendChild(barrageEl);

        // 【安全保险】如果动画因为某些原因没能触发 animationend，30秒后强制解锁
        const safetyTimer = setTimeout(() => {
            if (window.isBarrageShowing) {
                console.warn('⚠️ 弹幕播放超时，强制解锁队列');
                window.isBarrageShowing = false;
                triggerNextBarrage();
            }
        }, 30000);

        // 动画播完的处理
        barrageEl.addEventListener('animationend', () => {
            clearTimeout(safetyTimer); // 动画正常结束，清除保险丝
            barrageEl.remove();
            
            // 完美间隙：空出 1.2 秒后，尝试召唤下一条最“新鲜”的弹幕
            setTimeout(() => {
                window.isBarrageShowing = false;
                triggerNextBarrage();
            }, 1200);
        });
    }

// 处理弹幕删除
    function handleBarrageDeleted(barrageId) {

        // 从历史记录中移除
        barrageHistory = barrageHistory.filter(b => b.id !== barrageId);

        // 更新历史列表显示
        if (isSidebarExpanded) {
            updateBarrageHistoryDisplay();
        }

        showAlert('success', '弹幕已删除');
    }

// 删除弹幕（前端按钮调用）
    async function deleteBarrage(barrageId) {
        // 添加确认弹窗
        if (!confirm('确定要删除这条分析记录吗？此操作不可撤销。')) {
            return;
        }

        try {
            const response = await fetch(API_ROUTES.EEG.BARRAGE_BY_ID(barrageId), {
                method: 'DELETE'
            });
            if (response.status === 401) { handleUnauthorized(); return; }

            const data = await response.json();
            if (data.success) {
                // 直接从 DOM 中移除该卡片，并带一点点淡出效果
                const card = document.querySelector(`[data-id="${barrageId}"]`);
                if (card) {
                    card.style.opacity = '0';
                    card.style.transform = 'translateY(10px)';
                    setTimeout(() => card.remove(), 300);
                }
                showAlert('success', '记录已成功移除');
            } else {
                showAlert('error', data.error || '删除失败');
            }
        } catch (error) {
            console.error('删除弹幕失败:', error);
            showAlert('error', '网络错误，删除失败');
        }
    }

// 格式化弹幕时间
    function formatBarrageTime(startTime, endTime) {
        try {
            const start = new Date(startTime);
            const end = new Date(endTime);
            const startStr = start.toLocaleTimeString('zh-CN', {
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit'
            });
            const endStr = end.toLocaleTimeString('zh-CN', {
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit'
            });
            return `[${startStr} ~ ${endStr}]`;
        } catch (error) {
            return '[时间未知]';
        }
    }

// 切换弹幕侧边栏 → 现在弹出通知下拉浮层
    function toggleBarrageSidebar() {
        if(typeof toggleNotifDropdown === 'function') {
            toggleNotifDropdown();
        }
        isSidebarExpanded = true;
    }

// 加载弹幕历史
    async function loadBarrageHistory() {
        if (!currentUser) return;

        try {
            const response = await fetch(API_ROUTES.EEG.BARRAGE + '?limit=50');

            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();

            if (data.success) {
                barrageHistory = data.barrages || [];
                updateBarrageHistoryDisplay();
            } else {
                console.error('加载弹幕历史失败:', data.error);
            }
        } catch (error) {
            console.error('加载弹幕历史失败:', error);
        }
    }

// 定时检查新弹幕（备用方案，如果WebSocket不可用）
    async function checkForNewBarrages() {
        if (!isBarrageActive || !currentUser) return;

        try {
            const response = await fetch(API_ROUTES.EEG.BARRAGE + '?limit=5');

            if (response.status === 401) {
                console.warn('⚠️ 弹幕轮询未授权(401)，正在停止轮询...');
                if (window.barrageTimer) {
                    clearInterval(window.barrageTimer);
                    window.barrageTimer = null;
                }
                return;
            }

            if (response.ok) {
                const data = await response.json();
                if (data.success && data.barrages && data.barrages.length > 0) {
                    const latestBarrage = data.barrages[0];

                    // 检查是否是新弹幕
                    if (!barrageHistory.find(b => b.id === latestBarrage.id)) {
                        handleNewBarrage(latestBarrage);
                    }
                }
            }
        } catch (error) {
            // 静默处理错误，避免过多日志
            console.debug('检查新弹幕失败:', error);
        }
    }

// 更新弹幕历史显示 — 卡片轮播模式
    window._notifCurrentIdx = 0;

    function updateBarrageHistoryDisplay() {
        const barrageList = document.getElementById('barrageList');
        if (!barrageList) return;

        if (!barrageHistory || barrageHistory.length === 0) {
            window._notifCurrentIdx = 0;
            barrageList.innerHTML = `
                <div class="barrage-empty">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path>
                        <path d="M13.73 21a2 2 0 0 1-3.46 0"></path>
                    </svg>
                    暂无通知<br>
                    <small>启动实时分析后，分析结果将显示在这里</small>
                </div>
            `;
            return;
        }

        // Clamp index
        if (window._notifCurrentIdx >= barrageHistory.length) window._notifCurrentIdx = barrageHistory.length - 1;
        if (window._notifCurrentIdx < 0) window._notifCurrentIdx = 0;

        // Build carousel
        let cardsHtml = '';
        barrageHistory.forEach((barrage, idx) => {
            let timeSource = barrage.createdAt;
            if (typeof timeSource === 'string') timeSource = timeSource.replace(' ', 'T');
            
            const createdTime = new Date(timeSource).toLocaleString('zh-CN', {
                year: 'numeric', month: 'numeric', day: 'numeric',
                hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
            });
            
            const stateDesc = getStateDescription(barrage.primaryState);
            const confidence = barrage.confidenceScore ? (barrage.confidenceScore * 100).toFixed(1) + '%' : 'N/A';
            const rawContent = barrage.content || barrage.recommendation || '无分析内容';
            const mainContent = rawContent
                .replace(/\s*\[\d{2}:\d{2}:\d{2}.*?\]/g, '')  // 去掉末尾时间
                .replace(/^【[^】]*】\s*/g, '')                   // 去掉开头状态标签
                .trim();
            const activeClass = idx === window._notifCurrentIdx ? 'active' : '';

            cardsHtml += `
                <div class="notif-card ${activeClass}" data-idx="${idx}">
                    <div class="notif-card-body">
                        <div class="notif-card-text">【${stateDesc}】 ${mainContent}</div>
                    </div>
                    <div class="notif-card-footer">
                        <span class="notif-card-time">${createdTime}</span>
                        <span class="notif-card-conf">置信度: ${confidence}</span>
                        <button class="notif-card-del" onclick="event.stopPropagation(); deleteBarrageItem(${barrage.id})" title="删除">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                <polyline points="3,6 5,6 21,6"></polyline>
                                <path d="M19,6v14a2,2,0,0,1-2,2H7a2,2,0,0,1-2-2V6"></path>
                                <path d="M8,6V4a2,2,0,0,1,2-2h4a2,2,0,0,1,2,2V6"></path>
                            </svg>
                        </button>
                    </div>
                </div>
            `;
        });

        // Dots indicator
        let dotsHtml = '';
        if (barrageHistory.length > 1 && barrageHistory.length <= 8) {
            dotsHtml = '<div class="notif-dots">';
            barrageHistory.forEach((_, idx) => {
                dotsHtml += `<span class="notif-dot ${idx === window._notifCurrentIdx ? 'active' : ''}" onclick="goToNotif(${idx})"></span>`;
            });
            dotsHtml += '</div>';
        }

        // Counter
        const counterHtml = `<div class="notif-counter">${window._notifCurrentIdx + 1} / ${barrageHistory.length}</div>`;

        barrageList.innerHTML = `
            <div class="notif-carousel" id="notifCarousel">
                ${cardsHtml}
            </div>
            ${counterHtml}
            ${dotsHtml}
        `;

        // Attach wheel listener with global throttle
        const carousel = document.getElementById('notifCarousel');
        if (carousel) {
            carousel.onwheel = function(e) {
                e.preventDefault();
                if (window._notifWheelLock) return;
                // Ignore tiny delta from trackpad inertia
                if (Math.abs(e.deltaY) < 15) return;
                window._notifWheelLock = true;
                if (e.deltaY > 0) goToNotif(window._notifCurrentIdx + 1);
                else goToNotif(window._notifCurrentIdx - 1);
                setTimeout(() => { window._notifWheelLock = false; }, 500);
            };
        }
    }

    // Navigate to a specific card
    window.goToNotif = function(idx) {
        if (!barrageHistory || barrageHistory.length === 0) return;
        if (idx < 0 || idx >= barrageHistory.length) return;

        const oldIdx = window._notifCurrentIdx;
        window._notifCurrentIdx = idx;
        const cards = document.querySelectorAll('.notif-card');
        const dots = document.querySelectorAll('.notif-dot');
        const counter = document.querySelector('.notif-counter');

        if (!cards.length) return;

        const direction = idx > oldIdx ? 'down' : 'up';

        cards.forEach((card, i) => {
            card.classList.remove('active', 'exit-up', 'exit-down', 'enter-up', 'enter-down');
            if (i === oldIdx) {
                card.classList.add(direction === 'down' ? 'exit-up' : 'exit-down');
            }
            if (i === idx) {
                card.classList.add('active', direction === 'down' ? 'enter-up' : 'enter-down');
            }
        });

        dots.forEach((dot, i) => {
            dot.classList.toggle('active', i === idx);
        });

        if (counter) counter.textContent = `${idx + 1} / ${barrageHistory.length}`;
    };

// 删除弹幕项
    async function deleteBarrageItem(barrageId) {
        if (!confirm('确定要删除这条弹幕吗？')) {
            return;
        }

        try {
            const response = await fetch(API_ROUTES.EEG.BARRAGE_BY_ID(barrageId), {
                method: 'DELETE'
            });

            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();

            if (data.success) {
                // 从本地历史记录中移除
                barrageHistory = barrageHistory.filter(b => b.id !== barrageId);

                // 更新显示
                updateBarrageHistoryDisplay();

                showAlert('success', '弹幕已删除');
            } else {
                showAlert('error', data.error || '删除失败');
            }
        } catch (error) {
            console.error('删除弹幕失败:', error);
            showAlert('error', '网络错误');
        }
    }

    function initBarrageSystem() {
        // 可以在这里添加其他的弹幕系统初始化逻辑
    }

