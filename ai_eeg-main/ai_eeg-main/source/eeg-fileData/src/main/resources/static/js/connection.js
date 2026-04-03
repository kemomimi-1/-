// === connection.js ===

// 请求连接
    async function requestConnection() {
        const connectBtn = DOM_CACHE.connectBtn;
        connectBtn.disabled = true;
        connectBtn.textContent = '连接中...';

        try {
            const response = await fetch(API_ROUTES.CONNECTION.REQUEST, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    timezone: userTimezone,
                    notes: '从主页面发起的连接'
                })
            });

            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();

            if (response.ok && data.success) {
                showAlert('success', data.message);

                if (data.ports && data.ip) {
                    displayPortInfo(data.ports, data.ip);
                }

                startRealtimeStats();
                refreshConnectionStatus();
            } else {
                showAlert('error', data.error || '连接请求失败');
            }
        } catch (error) {
            console.error('连接请求失败:', error);
            showAlert('error', '网络错误，请检查连接');
        } finally {
            connectBtn.disabled = false;
            connectBtn.innerHTML = '🚀 请求连接';
        }
    }

// 显示端口信息
    function displayPortInfo(ports, ip) {
        DOM_CACHE.rawPort.textContent = ports.TimeSeriesRaw;
        DOM_CACHE.filtPort.textContent = ports.TimeSeriesFilt;
        DOM_CACHE.bandPort.textContent = ports.AvgBandPower;
        DOM_CACHE.serverIP.textContent = ip;
        DOM_CACHE.portInfo.classList.remove('hidden');
    }

// 隐藏端口信息
    function hidePortInfo() {
        DOM_CACHE.portInfo.classList.add('hidden');
    }

// 断开连接
    async function disconnectConnection() {
        const disconnectBtn = DOM_CACHE.disconnectBtn;
        disconnectBtn.disabled = true;
        disconnectBtn.textContent = '断开中...';

        // 【任务 5-3：熔断强制执行】如果有人正在疯狂点击播放后又点断开，强行中止那个正在进行的 HTTP 请求
        if (startAnalysisController) {
            startAnalysisController.abort();
        }

        // 【任务 5-1：联动停止】如果实时分析开着，断开连接时必须强行停止分析
        if (isBarrageActive) {
            await stopRealTimeAnalysis();
        }

        try {
            const response = await fetch(API_ROUTES.CONNECTION.DISCONNECT, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    reason: '用户从主页面手动断开连接'
                })
            });

            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();

            if (response.ok && data.success) {
                showAlert('success', data.message);
                forceCleanupConnectionState();
                setTimeout(async () => {
                    await refreshConnectionStatus();
                    loadSessionHistory();
                }, 1000);
            } else {
                showAlert('error', data.error || '断开连接失败');
                await attemptForceCleanup();
            }
        } catch (error) {
            console.error('断开连接请求失败:', error);
            showAlert('error', '网络错误，尝试强制清理状态');
            await attemptForceCleanup();
            forceCleanupConnectionState();
        } finally {
            // 【任务 5-2：防止回弹】不再手动强制改回启用，让 refreshConnectionStatus 根据后端真实情况来同步
        }
    }

// 尝试强制清理连接状态
    async function attemptForceCleanup() {
        try {
            const response = await fetch(API_ROUTES.CONNECTION.FORCE_CLEANUP, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    reason: '前端请求强制清理'
                })
            });

            if (response.ok) {
                const data = await response.json();
                if (data.success) {
                    showAlert('success', '连接状态已强制清理');
                    return true;
                }
            }
        } catch (error) {
            console.error('强制清理请求失败:', error);
        }
        return false;
    }

// 强制清理连接状态
    function forceCleanupConnectionState() {

        hidePortInfo();
        stopRealtimeStats();

        // 重置按钮状态
        const connectBtn = DOM_CACHE.connectBtn;
        const disconnectBtn = DOM_CACHE.disconnectBtn;

        if (connectBtn) {
            connectBtn.disabled = false;
            connectBtn.innerHTML = '🚀 请求连接';
        }

        if (disconnectBtn) {
            disconnectBtn.disabled = true;
            disconnectBtn.innerHTML = '🔌 断开连接';
        }

        // 重置连接状态显示
        updateConnectionStatusDisplay({
            hasActiveConnection: false,
            hasActiveSession: false,
            hasPortAllocation: false
        });

        resetStreamStatus();
    }

// 更新连接状态显示的统一函数
    function updateConnectionStatusDisplay(status) {
        const connectionStatus = DOM_CACHE.connectionStatus;
        const activeSession = DOM_CACHE.activeSession;
        const portAllocationStatus = DOM_CACHE.portAllocationStatus;

        // 更新全局状态变量
        window.isSystemConnected = status.hasActiveConnection;

        // 【任务 3：按钮状态同步】根据后端真实状态，反向控制 UI 按钮的可用性
        const connectBtn = DOM_CACHE.connectBtn;
        const disconnectBtn = DOM_CACHE.disconnectBtn;
        const stopSessionBtn = DOM_CACHE.stopSessionBtn;

        if (connectBtn) {
            // 如果已连接，则禁用“请求连接”按钮，并增加 active 样式区分
            connectBtn.disabled = status.hasActiveConnection;
            if (status.hasActiveConnection) {
                connectBtn.classList.add('active');
            } else {
                connectBtn.classList.remove('active');
            }
        }

        if (disconnectBtn) {
            // 如果未连接，则禁用“断开连接”按钮
            disconnectBtn.disabled = !status.hasActiveConnection;
        }

        if (stopSessionBtn) {
            // 如果有活跃会话，则开启“强制结束会话”按钮
            stopSessionBtn.disabled = !status.hasActiveSession;
        }

        if (connectionStatus) {
            connectionStatus.innerHTML = status.hasActiveConnection ?
                '<span class="status-indicator status-connected"></span>已连接' :
                '<span class="status-indicator status-inactive"></span>未连接';
        }

        if (activeSession) {
            activeSession.textContent = status.hasActiveSession && status.activeSession ?
                `会话 #${status.activeSession.id}` : '无';
        }

        if (portAllocationStatus) {
            portAllocationStatus.innerHTML = status.hasPortAllocation ?
                '<span class="status-indicator status-connected"></span>已分配' :
                '<span class="status-indicator status-inactive"></span>未分配';
        }
    }

// 强制结束会话
    async function forceEndSession() {
        if (!confirm('确定要强制结束当前会话吗？这将清理所有相关状态。')) {
            return;
        }

        try {
            const response = await fetch(API_ROUTES.SESSIONS.END, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    reason: '用户强制结束会话'
                })
            });

            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();

            if (response.ok && data.success) {
                showAlert('success', '会话已强制结束');
                forceCleanupConnectionState();
                refreshConnectionStatus();
                loadSessionHistory();
            } else {
                showAlert('error', data.error || '强制结束会话失败');
            }
        } catch (error) {
            console.error('强制结束会话失败:', error);
            showAlert('error', '网络错误');
        }
    }

// 刷新连接状态
    async function refreshConnectionStatus(retryCount = 0) {
        const maxRetries = 3;

        try {
            const response = await fetch(API_ROUTES.CONNECTION.STATUS);
            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();

            if (response.ok && data.success) {
                updateConnectionStatus(data);
            } else if (retryCount < maxRetries) {
                setTimeout(() => refreshConnectionStatus(retryCount + 1), 1000);
            }
        } catch (error) {
            console.error('刷新连接状态失败:', error);

            if (retryCount < maxRetries) {
                setTimeout(() => refreshConnectionStatus(retryCount + 1), 2000);
            }
        }
    }

// 更新连接状态显示
    function updateConnectionStatus(data) {
        const connectBtn = DOM_CACHE.connectBtn;
        const disconnectBtn = DOM_CACHE.disconnectBtn;
        const connectionStatus = DOM_CACHE.connectionStatus;
        const activeSession = DOM_CACHE.activeSession;
        const portAllocationStatus = DOM_CACHE.portAllocationStatus;

        const hasActiveConnection = data.hasActiveSession && data.hasPortAllocation;
        const hasPortAllocation = data.hasPortAllocation;

        if (hasActiveConnection) {
            connectionStatus.innerHTML = '<span class="status-indicator status-connected"></span>已连接';
            activeSession.textContent = `会话 #${data.activeSession.id}`;
            connectBtn.disabled = true;
            disconnectBtn.disabled = false;

            updateStreamStatus(data.activeSession);
        } else {
            connectionStatus.innerHTML = '<span class="status-indicator status-inactive"></span>未连接';
            activeSession.textContent = '无';
            connectBtn.disabled = false;
            disconnectBtn.disabled = true;

            resetStreamStatus();
        }

        if (hasPortAllocation) {
            portAllocationStatus.innerHTML = '<span class="status-indicator status-connected"></span>已分配';
            if (data.portAllocation) {
                displayPortInfo({
                    TimeSeriesRaw: data.portAllocation.rawPort,
                    TimeSeriesFilt: data.portAllocation.filtPort,
                    AvgBandPower: data.portAllocation.bandPort
                }, data.portAllocation.serverIP || '127.0.0.1');
            }
        } else {
            portAllocationStatus.innerHTML = '<span class="status-indicator status-inactive"></span>未分配';
            hidePortInfo();
        }

        // 【核心修复】同步全局连接标志位，确保弹幕分析校验通过
        window.isSystemConnected = hasActiveConnection;
    }

// 更新数据流状态
    function updateStreamStatus(session) {
        const statusMap = {
            'WAITING': { text: '等待中', class: 'status-waiting' },
            'ACTIVE': { text: '传输中', class: 'status-connected' },
            'PAUSED': { text: '已暂停', class: 'status-waiting' },
            'COMPLETED': { text: '已完成', class: 'status-connected' },
            'ERROR': { text: '错误', class: 'status-error' }
        };

        // Raw流状态
        const rawStatus = statusMap[session.rawStreamStatus] || { text: '未知', class: 'status-inactive' };
        DOM_CACHE.rawStreamStatus.innerHTML =
            `<span class="status-indicator ${rawStatus.class}"></span>${rawStatus.text}`;

        // Filt流状态
        const filtStatus = statusMap[session.filtStreamStatus] || { text: '未知', class: 'status-inactive' };
        DOM_CACHE.filtStreamStatus.innerHTML =
            `<span class="status-indicator ${filtStatus.class}"></span>${filtStatus.text}`;

        // Band流状态
        const bandStatus = statusMap[session.bandStreamStatus] || { text: '未知', class: 'status-inactive' };
        DOM_CACHE.bandStreamStatus.innerHTML =
            `<span class="status-indicator ${bandStatus.class}"></span>${bandStatus.text}`;

        // 数据包统计
        DOM_CACHE.rawPackets.textContent = (session.rawStreamTotalPackets || 0).toLocaleString();
        DOM_CACHE.filtPackets.textContent = (session.filtStreamTotalPackets || 0).toLocaleString();
        DOM_CACHE.bandPackets.textContent = (session.bandStreamTotalPackets || 0).toLocaleString();
    }

// 重置数据流状态
    function resetStreamStatus() {
        ['rawStreamStatus', 'filtStreamStatus', 'bandStreamStatus'].forEach(id => {
            document.getElementById(id).innerHTML = '<span class="status-indicator status-waiting"></span>等待中';
        });

        ['rawPackets', 'filtPackets', 'bandPackets'].forEach(id => {
            document.getElementById(id).textContent = '0';
        });

        DOM_CACHE.transmissionRate.textContent = '0/s';
    }

// 刷新数据流状态
    async function refreshStreamStatus() {
        try {
            const response = await fetch(API_ROUTES.CONNECTION.STREAM_STATUS);
            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();
            if (data.success && data.hasActiveSession && data.streamStatus) {
                updateStreamStatusFromDetail(data.streamStatus);
                showAlert('success', '数据流状态已刷新');
            } else {
                resetStreamStatus();
                showAlert('info', '当前无活跃会话');
            }
        } catch (error) {
            console.error('刷新数据流状态失败:', error);
            showAlert('error', '刷新失败');
        }
    }

// 从 stream-status 接口的详细数据结构更新流状态
    function updateStreamStatusFromDetail(streamStatus) {
        const statusMap = {
            'WAITING': { text: '等待中', class: 'status-waiting' },
            'ACTIVE': { text: '传输中', class: 'status-connected' },
            'PAUSED': { text: '已暂停', class: 'status-waiting' },
            'COMPLETED': { text: '已完成', class: 'status-connected' },
            'ERROR': { text: '错误', class: 'status-error' }
        };

        const streams = streamStatus.streams || {};

        // Raw 流
        const rawInfo = streams.raw || {};
        const rawStatus = statusMap[rawInfo.status] || { text: '未知', class: 'status-inactive' };
        DOM_CACHE.rawStreamStatus.innerHTML =
            `<span class="status-indicator ${rawStatus.class}"></span>${rawStatus.text}`;

        // Filt 流
        const filtInfo = streams.filt || {};
        const filtStatus = statusMap[filtInfo.status] || { text: '未知', class: 'status-inactive' };
        DOM_CACHE.filtStreamStatus.innerHTML =
            `<span class="status-indicator ${filtStatus.class}"></span>${filtStatus.text}`;

        // Band 流
        const bandInfo = streams.band || {};
        const bandStatus = statusMap[bandInfo.status] || { text: '未知', class: 'status-inactive' };
        DOM_CACHE.bandStreamStatus.innerHTML =
            `<span class="status-indicator ${bandStatus.class}"></span>${bandStatus.text}`;

        // 数据包统计
        DOM_CACHE.rawPackets.textContent = ((rawInfo.totalPackets) || 0).toLocaleString();
        DOM_CACHE.filtPackets.textContent = ((filtInfo.totalPackets) || 0).toLocaleString();
        DOM_CACHE.bandPackets.textContent = ((bandInfo.totalPackets) || 0).toLocaleString();
    }


// 开始实时统计更新
    function startRealtimeStats() {
        if (realtimeStatsInterval) {
            clearInterval(realtimeStatsInterval);
        }
        realtimeStatsInterval = setInterval(updateRealtimeStats, 2000);
    }

// 停止实时统计更新
    function stopRealtimeStats() {
        if (realtimeStatsInterval) {
            clearInterval(realtimeStatsInterval);
            realtimeStatsInterval = null;
        }
    }

// 更新实时统计
    async function updateRealtimeStats() {
        try {
            const response = await fetch(API_ROUTES.CONNECTION.REAL_TIME_STATS);
            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();

            if (response.ok && data.success && data.connected) {
                DOM_CACHE.transmissionRate.textContent =
                    parseFloat(data.transmissionRate.packetsPerSecond).toFixed(1) + '/s';
            }
        } catch (error) {
            console.error('更新实时统计失败:', error);
        }
    }

