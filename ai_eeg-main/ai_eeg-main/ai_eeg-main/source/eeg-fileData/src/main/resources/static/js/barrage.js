// === barrage.js === [v2.0 悬浮球模式]

    function initializeBarrageWebSocket() {
        if (!currentUser) return;

        try {
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const wsUrl = `${protocol}//${window.location.host}/ws/eeg?userId=${currentUser.userId}`;

            barrageWebSocket = new WebSocket(wsUrl);

            barrageWebSocket.onopen = function() {
                wsReconnectAttempts = 0;
            };

            barrageWebSocket.onmessage = function(event) {
                try {
                    const data = JSON.parse(event.data);
                    if (data.type === 'REAL_TIME_BARRAGE' && data.data.type === 'NEW_BARRAGE') {
                        handleNewBarrage(data.data.barrage);
                    } else if (data.type === 'BARRAGE_DELETION' && data.data.type === 'BARRAGE_DELETED') {
                        handleBarrageDeleted(data.data.barrageId);
                    }
                } catch (error) {
                    console.error('解析WebSocket消息失败:', error);
                }
            };

            barrageWebSocket.onclose = function(event) {
                console.warn('⚠️ 弹幕WebSocket连接已关闭, 代码:', event.code, '原因:', event.reason);
                
                if (isBarrageActive && wsReconnectAttempts < WS_MAX_RECONNECT) {
                    wsReconnectAttempts++;
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

// 处理新弹幕：悬浮球模式 - 不再飘动，改为红点通知
    function handleNewBarrage(barrage) {
        if (!barrage) return;

        // 1. 历史记录管理
        const isDuplicate = barrageHistory.some(b => b.id === barrage.id);
        if (!isDuplicate) {
            barrageHistory.unshift(barrage);
            if (barrageHistory.length > 50) barrageHistory.pop();
            
            // 更新侧边栏（如果已展开）
            if (isSidebarExpanded) {
                updateBarrageHistoryDisplay();
            }

            // 2. 悬浮球红点通知
            unreadBarrageCount++;
            updateFloatingBallBadge();

            // 3. 更新监测页面的时间线
            if (currentTab === 'monitor') {
                updateMonitorTimeline();
            }

            // 4. 更新频段数据（如果弹幕包含频段信息）
            if (barrage.bandData || barrage.primaryState) {
                updateBandDataFromBarrage(barrage);
            }
        }
    }

    // 更新悬浮球红点（显示未读数量）
    function updateFloatingBallBadge() {
        const badge = DOM_CACHE.floatingBallBadge;
        if (!badge) return;
        
        if (unreadBarrageCount > 0) {
            // 显示数字（超过99显示99+）
            badge.textContent = unreadBarrageCount > 99 ? '99+' : unreadBarrageCount;
            badge.style.display = 'block';
            
            // 重新触发弹入动画
            badge.style.animation = 'none';
            badge.offsetHeight; // 强制重排
            badge.style.animation = 'badgePulse 1.5s ease-in-out infinite, badgeEnter 0.35s cubic-bezier(0.34, 1.56, 0.64, 1)';
        } else {
            badge.style.display = 'none';
            badge.textContent = '';
        }
    }

// 处理弹幕删除
    function handleBarrageDeleted(barrageId) {
        barrageHistory = barrageHistory.filter(b => b.id !== barrageId);
        if (isSidebarExpanded) {
            updateBarrageHistoryDisplay();
        }
        showAlert('success', '弹幕已删除');
    }

// 删除弹幕（前端按钮调用）
    async function deleteBarrage(barrageId) {
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

// 切换弹幕侧边栏（悬浮球点击触发）
    function toggleBarrageSidebar() {
        const sidebar = DOM_CACHE.barrageSidebar;
        const overlay = DOM_CACHE.barrageSidebarOverlay;
        
        isSidebarExpanded = !isSidebarExpanded;

        if (isSidebarExpanded) {
            sidebar.classList.add('expanded');
            overlay.classList.add('visible');
            
            // 清除未读
            unreadBarrageCount = 0;
            updateFloatingBallBadge();
            
            // 加载并显示历史弹幕
            loadBarrageHistory();
        } else {
            sidebar.classList.remove('expanded');
            overlay.classList.remove('visible');
        }
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
                    if (!barrageHistory.find(b => b.id === latestBarrage.id)) {
                        handleNewBarrage(latestBarrage);
                    }
                }
            }
        } catch (error) {
            console.debug('检查新弹幕失败:', error);
        }
    }

// 更新弹幕历史显示（竖向卡片列表）
    function updateBarrageHistoryDisplay() {
        const barrageList = DOM_CACHE.barrageList;

        if (!barrageHistory || barrageHistory.length === 0) {
            barrageList.innerHTML = `
                <div class="barrage-empty">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
                    </svg>
                    暂无分析记录<br>
                    <small>启动实时分析后，记录将在此显示</small>
                </div>
            `;
            return;
        }

        let html = '';
        barrageHistory.forEach(barrage => {
            let timeSource = barrage.createdAt;
            if (typeof timeSource === 'string') {
                timeSource = timeSource.replace(' ', 'T');
            }
            
            const createdTime = new Date(timeSource).toLocaleString('zh-CN', {
                year: 'numeric',
                month: 'numeric',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit',
                hour12: false
            });
            
            const stateDesc = getStateDescription(barrage.primaryState);
            const confidence = barrage.confidenceScore ? (barrage.confidenceScore * 100).toFixed(1) + '%' : 'N/A';
            
            const rawContent = barrage.content || barrage.recommendation || '无分析内容';
            const mainContent = rawContent.replace(/\s*\[\d{2}:\d{2}:\d{2}.*?\]/g, '').trim();

            html += `
                <div class="barrage-item" data-state="${barrage.primaryState}" data-id="${barrage.id}">
                    <button class="barrage-delete-btn" onclick="deleteBarrageItem(${barrage.id})">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <polyline points="3,6 5,6 21,6"></polyline>
                            <path d="M19,6 L19,20 C19,21 18,22 17,22 L7,22 C6,22 5,21 5,20 L5,6"></path>
                            <path d="M8,6 L8,4 C8,3 9,2 10,2 L14,2 C15,2 16,3 16,4 L16,6"></path>
                        </svg>
                    </button>
                    <div class="barrage-content">
                        【${stateDesc}】 ${mainContent}
                    </div>
                    <div class="barrage-meta">
                        <span class="barrage-time">${createdTime}</span>
                        <span class="barrage-confidence">置信度: ${confidence}</span>
                    </div>
                </div>
            `;
        });

        barrageList.innerHTML = html;
    }

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
                barrageHistory = barrageHistory.filter(b => b.id !== barrageId);
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
        // 初始化悬浮球状态
        updateFloatingBallBadge();
    }
