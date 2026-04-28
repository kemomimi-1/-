// === app.js === [v2.0 导航栏 + 实时监测页面]

function resetAllUIComponents() {
    // 1. 分析按钮重置
    const startBtn = DOM_CACHE.startAnalysisNavBtn;
    const stopBtn = DOM_CACHE.stopAnalysisNavBtn;
    if (startBtn) startBtn.style.display = 'flex';
    if (stopBtn) stopBtn.style.display = 'none';
    isBarrageActive = false;

    // 2. 连接按钮重置
    const connectBtn = DOM_CACHE.connectBtn;
    const disconnectBtn = DOM_CACHE.disconnectBtn;
    if (connectBtn) {
        connectBtn.disabled = false;
        connectBtn.classList.remove('active');
    }
    if (disconnectBtn) disconnectBtn.disabled = true;

    // 3. 全局标志位清空
    window.isSystemConnected = false;
    window.isBarrageShowing = false;
    window.pendingBarrage = null;
}

// ========== 基础初始化逻辑 ==========
document.addEventListener('DOMContentLoaded', function () {
    initPage();
    setupInputHandlers();
});

// WebSocket重连参数
let wsReconnectAttempts = 0;
const WS_MAX_RECONNECT = 5;

// ========== 页面切换 ==========
function switchTab(tabName) {
    currentTab = tabName;

    // 更新标签按钮样式
    document.getElementById('tabChat').classList.toggle('active', tabName === 'chat');
    document.getElementById('tabMonitor').classList.toggle('active', tabName === 'monitor');

    // 切换页面内容
    document.getElementById('pageChatContent').style.display = tabName === 'chat' ? 'flex' : 'none';
    document.getElementById('pageMonitorContent').style.display = tabName === 'monitor' ? 'flex' : 'none';

    // 进入监测页面时刷新数据
    if (tabName === 'monitor') {
        refreshMonitorData();
        startBandDataPolling();
    } else {
        stopBandDataPolling();
    }
}

// ========== 实时分析控制（导航栏版本） ==========
async function startRealTimeAnalysis() {
    if (!currentUser) {
        showAlert('error', '请先登录');
        return;
    }

    const startBtn = DOM_CACHE.startAnalysisNavBtn;
    const stopBtn = DOM_CACHE.stopAnalysisNavBtn;

    // 乐观更新 UI：立即响应点击
    if (startBtn) {
        startBtn.disabled = true;
        startBtn.style.display = 'none';
    }
    if (stopBtn) {
        stopBtn.style.display = 'flex';
        stopBtn.disabled = false;
    }
    isBarrageActive = true;
    updateMonitorStatus('analyzing');
    if (!barrageWebSocket) {
        initializeBarrageWebSocket();
    }

    startAnalysisController = new AbortController();

    try {
        const response = await fetch(API_ROUTES.EEG.START, {
            method: 'POST',
            signal: startAnalysisController.signal
        });

        if (response.status === 401) {
            handleUnauthorized();
            return;
        }

        const data = await response.json();

        if (data.success) {
            showAlert('success', '实时分析已启动');
        } else {
            showAlert('error', data.error || '启动实时分析失败');
            revertAnalysisUI();
        }
    } catch (error) {
        if (error.name === 'AbortError') return;
        console.error('启动实时分析失败:', error);
        showAlert('error', '网络错误，请检查服务器连接');
        revertAnalysisUI();
    } finally {
        startAnalysisController = null;
    }
}

// 辅助函数：操作失败时回退UI状态
function revertAnalysisUI() {
    isBarrageActive = false;
    const startBtn = DOM_CACHE.startAnalysisNavBtn;
    const stopBtn = DOM_CACHE.stopAnalysisNavBtn;
    if (startBtn) {
        startBtn.style.display = 'flex';
        startBtn.disabled = false;
    }
    if (stopBtn) stopBtn.style.display = 'none';
    if (barrageWebSocket) {
        barrageWebSocket.close();
        barrageWebSocket = null;
    }
    updateMonitorStatus('idle');
}

async function stopRealTimeAnalysis() {
    const startBtn = DOM_CACHE.startAnalysisNavBtn;
    const stopBtn = DOM_CACHE.stopAnalysisNavBtn;

    // 乐观更新 UI
    if (stopBtn) {
        stopBtn.disabled = true;
        stopBtn.style.display = 'none';
    }
    if (startBtn) {
        startBtn.style.display = 'flex';
        startBtn.disabled = false;
    }
    isBarrageActive = false;
    if (barrageWebSocket) {
        barrageWebSocket.close();
        barrageWebSocket = null;
    }
    updateMonitorStatus('stopped');

    try {
        const response = await fetch(API_ROUTES.EEG.STOP, { method: 'POST' });
        if (response.status === 401) { handleUnauthorized(); return; }

        const data = await response.json();

        if (data.success) {
            showAlert('success', '实时分析已停止');
        } else {
            showAlert('error', data.error || '停止实时分析失败');
            // 失败可考虑回滚
        }
    } catch (error) {
        console.error('停止实时分析失败:', error);
        showAlert('error', '网络错误');
    } finally {
        // 控制按钮状态以防死锁
    }
}

// ========== 监测页面状态更新 ==========
function updateMonitorStatus(status) {
    const title = DOM_CACHE.monitorStatusTitle;
    const desc = DOM_CACHE.monitorStatusDesc;
    const label = DOM_CACHE.monitorStatusLabel;

    switch (status) {
        case 'analyzing':
            if (title) title.textContent = '正在实时分析中...';
            if (desc) desc.textContent = '脑电数据正在被分析，频段数据将实时更新';
            if (label) { label.textContent = '分析中'; label.style.background = '#dcfce7'; label.style.color = '#16a34a'; }
            break;
        case 'stopped':
            if (title) title.textContent = '分析已停止';
            if (desc) desc.textContent = '点击"开始分析"重新启动实时分析';
            if (label) { label.textContent = '已停止'; label.style.background = '#fef3c7'; label.style.color = '#d97706'; }
            break;
        default:
            if (title) title.textContent = '尚未开始分析';
            if (desc) desc.textContent = '点击"开始分析"启动实时脑电数据分析';
            if (label) { label.textContent = '等待开启'; label.style.background = '#f0f4ff'; label.style.color = '#667eea'; }
    }
}

// ========== 频段数据相关 ==========
const BAND_INFO = {
    delta: {
        name: 'Delta',
        freq: '0.5 - 4 Hz',
        icon: '🌙',
        color: '#8b5cf6',
        defaultInterpret: '深度睡眠 / 身体修复',
        detail: {
            title: 'Delta 频段详解',
            sections: [
                { title: '频率范围', content: '0.5 - 4 Hz' },
                { title: '关联状态', items: ['深度睡眠（第3、4阶段）', '无意识状态', '身体自我修复和再生', '免疫系统活跃期'] },
                { title: '正常表现', items: ['在清醒状态下较低', '入睡时逐渐增强', '深睡期达到峰值'] },
                { title: '异常信号', items: ['清醒时过高可能提示脑损伤', '过高伴随疲倦可能为睡眠不足', '完全缺失可能为睡眠障碍'] }
            ]
        }
    },
    theta: {
        name: 'Theta',
        freq: '4 - 8 Hz',
        icon: '🧘',
        color: '#ec4899',
        defaultInterpret: '冥想 / 创造力 / 浅睡',
        detail: {
            title: 'Theta 频段详解',
            sections: [
                { title: '频率范围', content: '4 - 8 Hz' },
                { title: '关联状态', items: ['冥想和深度放松', '创造性思维和灵感', '工作记忆处理', 'REM快速眼动睡眠', '浅睡入睡过渡期'] },
                { title: '正常表现', items: ['冥想时显著增强', '创造性任务时局部活跃', '入睡过渡期占主导'] },
                { title: '异常信号', items: ['清醒时过高可能提示注意力缺陷', '与过度疲劳相关'] }
            ]
        }
    },
    alpha: {
        name: 'Alpha',
        freq: '8 - 13 Hz',
        icon: '😌',
        color: '#f59e0b',
        defaultInterpret: '放松 / 平静 / 静息状态',
        detail: {
            title: 'Alpha 频段详解',
            sections: [
                { title: '频率范围', content: '8 - 13 Hz' },
                { title: '关联状态', items: ['放松但清醒的状态', '闭眼静息时最明显', '默认模式网络活动', '内在思考和反省', '平静的感知状态'] },
                { title: '正常表现', items: ['闭眼时显著增强', '睁眼或集中注意力时减弱', '后脑区域（枕叶）最显著'] },
                { title: '异常信号', items: ['过度抑制可能提示焦虑', '过度增强可能为注意力分散', '不对称可能提示情绪异常'] }
            ]
        }
    },
    beta: {
        name: 'Beta',
        freq: '13 - 30 Hz',
        icon: '🎯',
        color: '#10b981',
        defaultInterpret: '专注 / 逻辑思维 / 警觉',
        detail: {
            title: 'Beta 频段详解',
            sections: [
                { title: '频率范围', content: '13 - 30 Hz' },
                { title: '关联状态', items: ['主动思考和问题解决', '专注和注意力集中', '逻辑推理和决策', '言语和阅读活动', '警觉和感知处理'] },
                { title: '正常表现', items: ['认知任务时显著增强', '前额叶区域最活跃', '运动皮层参与运动时抑制'] },
                { title: '异常信号', items: ['持续过高可能为焦虑或紧张', '过低可能为注意力障碍ADHD', '高Beta与压力和过度思考相关'] }
            ]
        }
    },
    gamma: {
        name: 'Gamma',
        freq: '30 - 100 Hz',
        icon: '⚡',
        color: '#3b82f6',
        defaultInterpret: '高度认知 / 学习 / 记忆',
        detail: {
            title: 'Gamma 频段详解',
            sections: [
                { title: '频率范围', content: '30 - 100 Hz' },
                { title: '关联状态', items: ['高级认知功能', '意识整合和特征绑定', '学习和记忆巩固', '感知觉整合', '跨脑区信息同步'] },
                { title: '正常表现', items: ['学习新内容时增强', '顿悟时刻短暂爆发', '经验丰富的冥想者中增强'] },
                { title: '异常信号', items: ['过高可能与癫痫活动相关', '过低可能为认知功能下降', '不规律可能提示神经发育异常'] }
            ]
        }
    }
};

function showBandDetail(bandKey) {
    const info = BAND_INFO[bandKey];
    if (!info) return;

    const modal = document.getElementById('bandDetailModal');
    const header = document.getElementById('bandDetailHeader');
    const body = document.getElementById('bandDetailBody');

    header.innerHTML = `
            <h3><span style="margin-right:8px;">${info.icon}</span>${info.detail.title}</h3>
            <div class="band-detail-freq" style="margin-top:4px; color: ${info.color}; font-weight:600;">${info.freq}</div>
        `;

    let bodyHtml = '';
    info.detail.sections.forEach(section => {
        bodyHtml += `<div class="detail-section">`;
        bodyHtml += `<div class="detail-section-title">${section.title}</div>`;
        if (section.content) {
            bodyHtml += `<p>${section.content}</p>`;
        }
        if (section.items) {
            bodyHtml += `<ul>`;
            section.items.forEach(item => {
                bodyHtml += `<li>${item}</li>`;
            });
            bodyHtml += `</ul>`;
        }
        bodyHtml += `</div>`;
    });

    body.innerHTML = bodyHtml;
    modal.classList.add('visible');
}

function closeBandDetail() {
    document.getElementById('bandDetailModal').classList.remove('visible');
}

// 根据弹幕数据更新频段
function updateBandDataFromBarrage(barrage) {
    if (!barrage) return;

    // 【核心修复】使用弹幕中的真实频段数值更新UI卡片
    if (barrage.alphaValue !== undefined && barrage.alphaValue !== null) {
        const sum = (barrage.alphaValue || 0) + (barrage.betaValue || 0) + (barrage.thetaValue || 0) + (barrage.deltaValue || 0) + (barrage.gammaValue || 0);
        const total = sum > 0 ? sum : 1;

        const profile = {
            alpha: (barrage.alphaValue || 0) / total,
            beta: (barrage.betaValue || 0) / total,
            theta: (barrage.thetaValue || 0) / total,
            delta: (barrage.deltaValue || 0) / total,
            gamma: (barrage.gammaValue || 0) / total
        };

        Object.keys(profile).forEach(band => {
            const value = profile[band];
            const displayValue = (value * 100).toFixed(1) + '%';
            const valueEl = document.getElementById('bandValue' + band.charAt(0).toUpperCase() + band.slice(1));
            const barEl = document.getElementById('bandBar' + band.charAt(0).toUpperCase() + band.slice(1));

            if (valueEl) valueEl.textContent = displayValue;
            if (barEl) barEl.style.width = Math.min(value * 100 * 1.5, 100) + '%';
        });
    }

    // 从弹幕状态推断显性频段（更新解释文字）
    const stateInterpretMap = {
        'DEEP_RELAXATION': { primary: 'alpha', text: '你正处于深度放松状态' },
        'RELAXED': { primary: 'alpha', text: '身心平静，内在放松' },
        'FOCUSED': { primary: 'beta', text: '注意力高度集中' },
        'ALERT': { primary: 'beta', text: '警觉性增强，感知活跃' },
        'STRESSED': { primary: 'beta', text: '精神紧张，建议调整' },
        'DROWSY': { primary: 'delta', text: '困倦感增强，注意休息' },
        'MEDITATIVE': { primary: 'theta', text: '冥想状态，内在平和' },
        'CREATIVE': { primary: 'theta', text: '创造力活跃，灵感涌动' },
        'HYPERACTIVE': { primary: 'gamma', text: '大脑过度活跃' },
        'UNBALANCED': { primary: 'alpha', text: '频段失衡，建议放松' }
    };

    const interpretation = stateInterpretMap[barrage.primaryState];
    if (interpretation) {
        const el = document.getElementById('bandInterpret' + interpretation.primary.charAt(0).toUpperCase() + interpretation.primary.slice(1));
        if (el) {
            el.textContent = interpretation.text;
        }
    }

    // 更新监测页面状态标题
    const stateDesc = getStateDescription(barrage.primaryState);
    const statusTitle = DOM_CACHE.monitorStatusTitle;
    if (statusTitle && isBarrageActive) {
        statusTitle.textContent = `当前状态：${stateDesc}`;
    }
}

// 频段数据轮询
function startBandDataPolling() {
    if (bandDataInterval) clearInterval(bandDataInterval);
    bandDataInterval = setInterval(fetchLatestBandData, 5000);
    fetchLatestBandData();
}

function stopBandDataPolling() {
    if (bandDataInterval) {
        clearInterval(bandDataInterval);
        bandDataInterval = null;
    }
}

async function fetchLatestBandData() {
    if (!currentUser || !isBarrageActive) return;

    try {
        const response = await fetch(API_ROUTES.EEG.BARRAGE + '?limit=1');
        if (!response.ok) return;

        const data = await response.json();
        if (data.success && data.barrages && data.barrages.length > 0) {
            const latestBarrage = data.barrages[0];

            // 如果弹幕携带了频段数值数据，优先计算实际百分比
            if (latestBarrage.alphaValue !== undefined && latestBarrage.alphaValue !== null) {
                const sum = latestBarrage.alphaValue + latestBarrage.betaValue + latestBarrage.thetaValue + latestBarrage.deltaValue + latestBarrage.gammaValue;
                const total = sum > 0 ? sum : 1;
                
                const profile = {
                    alpha: latestBarrage.alphaValue / total,
                    beta: latestBarrage.betaValue / total,
                    theta: latestBarrage.thetaValue / total,
                    delta: latestBarrage.deltaValue / total,
                    gamma: latestBarrage.gammaValue / total
                };
                
                Object.keys(profile).forEach(band => {
                    const value = profile[band];
                    const displayValue = (value * 100).toFixed(1) + '%';
                    const valueEl = document.getElementById('bandValue' + band.charAt(0).toUpperCase() + band.slice(1));
                    const barEl = document.getElementById('bandBar' + band.charAt(0).toUpperCase() + band.slice(1));

                    if (valueEl) valueEl.textContent = displayValue;
                    if (barEl) barEl.style.width = Math.min(value * 100 * 1.5, 100) + '%';
                });
            } else if (latestBarrage.bandPowers) {
                updateBandCardsWithData(latestBarrage.bandPowers);
            } else if (latestBarrage.confidenceScore) {
                // 用置信度模拟显示
                simulateBandDisplay(latestBarrage);
            }
        }
    } catch (error) {
        console.debug('获取频段数据失败:', error);
    }
}

function simulateBandDisplay(barrage) {
    // 从弹幕状态推断频段活跃度（当没有真实频段数值时的友好展示）
    const stateProfiles = {
        'DEEP_RELAXATION': { delta: 0.15, theta: 0.2, alpha: 0.55, beta: 0.08, gamma: 0.02 },
        'RELAXED': { delta: 0.1, theta: 0.15, alpha: 0.5, beta: 0.2, gamma: 0.05 },
        'FOCUSED': { delta: 0.05, theta: 0.08, alpha: 0.15, beta: 0.55, gamma: 0.17 },
        'ALERT': { delta: 0.05, theta: 0.1, alpha: 0.1, beta: 0.5, gamma: 0.25 },
        'STRESSED': { delta: 0.05, theta: 0.05, alpha: 0.05, beta: 0.6, gamma: 0.25 },
        'DROWSY': { delta: 0.5, theta: 0.3, alpha: 0.1, beta: 0.07, gamma: 0.03 },
        'MEDITATIVE': { delta: 0.1, theta: 0.5, alpha: 0.3, beta: 0.07, gamma: 0.03 },
        'CREATIVE': { delta: 0.08, theta: 0.45, alpha: 0.25, beta: 0.15, gamma: 0.07 },
        'HYPERACTIVE': { delta: 0.02, theta: 0.05, alpha: 0.05, beta: 0.3, gamma: 0.58 },
        'UNBALANCED': { delta: 0.2, theta: 0.2, alpha: 0.2, beta: 0.2, gamma: 0.2 }
    };

    const profile = stateProfiles[barrage.primaryState] || stateProfiles['RELAXED'];
    const confidence = barrage.confidenceScore || 0.5;

    Object.keys(profile).forEach(band => {
        const value = profile[band];
        const displayValue = (value * 100).toFixed(1) + '%';
        const valueEl = document.getElementById('bandValue' + band.charAt(0).toUpperCase() + band.slice(1));
        const barEl = document.getElementById('bandBar' + band.charAt(0).toUpperCase() + band.slice(1));

        if (valueEl) valueEl.textContent = displayValue;
        if (barEl) barEl.style.width = Math.min(value * 100 * 1.5, 100) + '%';
    });
}

function updateBandCardsWithData(bandPowers) {
    Object.keys(bandPowers).forEach(band => {
        const bandLower = band.toLowerCase();
        const value = bandPowers[band];
        const displayValue = typeof value === 'number' ? value.toFixed(4) + ' μV²' : '--';

        const valueEl = document.getElementById('bandValue' + bandLower.charAt(0).toUpperCase() + bandLower.slice(1));
        const barEl = document.getElementById('bandBar' + bandLower.charAt(0).toUpperCase() + bandLower.slice(1));

        if (valueEl) valueEl.textContent = displayValue;
        if (barEl) {
            const normalizedWidth = Math.min(Math.abs(value) * 1000, 100);
            barEl.style.width = normalizedWidth + '%';
        }
    });
}

// 刷新监测页面全部数据
function refreshMonitorData() {
    updateMonitorTimeline();
    if (isBarrageActive) {
        updateMonitorStatus('analyzing');
        fetchLatestBandData();
    } else {
        updateMonitorStatus('idle');
    }
}

// 更新监测页面时间线
function updateMonitorTimeline() {
    const timelineList = DOM_CACHE.timelineList;
    const timelineCount = DOM_CACHE.timelineCount;

    if (!barrageHistory || barrageHistory.length === 0) {
        if (timelineCount) timelineCount.textContent = '0 条记录';
        if (timelineList) {
            timelineList.innerHTML = `
                    <div class="timeline-empty">
                        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round" style="opacity:0.3;">
                            <polyline points="22,12 18,12 15,21 9,3 6,12 2,12"></polyline>
                        </svg>
                        <p>暂无分析记录</p>
                        <small>启动实时分析后，数据将在此显示</small>
                    </div>
                `;
        }
        return;
    }

    if (timelineCount) timelineCount.textContent = `${barrageHistory.length} 条记录`;

    let html = '';
    barrageHistory.slice(0, 20).forEach(barrage => {
        const stateColor = getStateColor(barrage.primaryState);
        const stateDesc = getStateDescription(barrage.primaryState);
        const rawContent = barrage.content || barrage.recommendation || '无分析内容';
        const pureContent = rawContent.replace(/\s*\[\d{2}:\d{2}:\d{2}.*?\]/g, '').trim();

        let timeSource = barrage.createdAt;
        if (typeof timeSource === 'string') timeSource = timeSource.replace(' ', 'T');
        const time = new Date(timeSource).toLocaleTimeString('zh-CN', {
            hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
        });

        html += `
                <div class="timeline-item">
                    <div class="timeline-dot" style="background: ${stateColor};"></div>
                    <div class="timeline-item-content">
                        <div class="timeline-item-text">【${stateDesc}】${pureContent}</div>
                        <div class="timeline-item-time">${time}</div>
                    </div>
                </div>
            `;
    });

    if (timelineList) timelineList.innerHTML = html;
}

// ========== 原有函数保持不变 ==========
function detectTimezone() {
    try {
        userTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    } catch (error) {
        console.error('时区检测失败:', error);
        userTimezone = 'UTC';
    }
}

function setupInputHandlers() {
    const chatInput = DOM_CACHE.chatInput;
    if (!chatInput) return;

    chatInput.addEventListener('input', function () {
        this.style.height = 'auto';
        this.style.height = Math.min(this.scrollHeight, 120) + 'px';
    });

    chatInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });
}

function toggleLeftSidebar() {
    const sidebar = DOM_CACHE.leftSidebar;
    sidebar.classList.toggle('collapsed');
}

function toggleRightSidebar() {
    const sidebar = DOM_CACHE.rightSidebar;
    sidebar.classList.toggle('collapsed');
}

function toggleAccordion(element) {
    const content = element.nextElementSibling;
    const isActive = content.classList.contains('active');

    if (isActive) {
        content.classList.remove('active');
        element.classList.remove('active');
    } else {
        content.classList.add('active');
        element.classList.add('active');
    }
}

async function checkAnalysisStatus() {
    if (!currentUser) return;

    try {
        const response = await fetch(API_ROUTES.EEG.STATUS);
        if (response.ok) {
            const data = await response.json();

            if (data.success && data.analysisActive) {
                isBarrageActive = true;

                const startBtn = DOM_CACHE.startAnalysisNavBtn;
                const stopBtn = DOM_CACHE.stopAnalysisNavBtn;
                if (startBtn) startBtn.style.display = 'none';
                if (stopBtn) stopBtn.style.display = 'flex';

                if (!barrageWebSocket) {
                    initializeBarrageWebSocket();
                }

                if (barrageTimer) clearInterval(barrageTimer);
                barrageTimer = setInterval(checkForNewBarrages, 5000);

                updateMonitorStatus('analyzing');
            }
        }
    } catch (error) {
        console.warn('检查分析状态失败:', error);
    }
}

async function initPage() {
    resetAllUIComponents();

    try {
        const response = await fetch(API_ROUTES.AUTH.STATUS);
        const data = await response.json();

        if (data.authenticated) {
            currentUser = {
                userId: data.userId,
                username: data.username
            };

            localStorage.setItem('currentUser', JSON.stringify(currentUser));
            updateUserDisplay();

            await Promise.all([
                fetch(API_ROUTES.EEG.STOP, { method: 'POST' }).catch(() => { }),
                fetch(API_ROUTES.CONNECTION.DISCONNECT, { method: 'POST' }).catch(() => { })
            ]);

            await checkAnalysisStatus();

            loadBarrageHistory();
            loadConversationHistory();
        } else {
            console.warn('❌ 用户未登录，正在跳转至登录页...');
            window.location.href = '/login.html';
            return;
        }
    } catch (error) {
        console.error('初始化页面失败:', error);
        const userInfo = localStorage.getItem('currentUser');
        if (userInfo) {
            currentUser = JSON.parse(userInfo);
            updateUserDisplay();

            await Promise.all([
                fetch(API_ROUTES.EEG.STOP, { method: 'POST' }).catch(() => { }),
                fetch(API_ROUTES.CONNECTION.DISCONNECT, { method: 'POST' }).catch(() => { })
            ]);

            await checkAnalysisStatus();
        } else {
            window.location.href = '/login.html';
        }
    }

    initBarrageSystem();
    refreshConnectionStatus();
}

// 页面卸载时清理资源
window.addEventListener('beforeunload', function () {
    const disconnectUrl = API_ROUTES.CONNECTION.DISCONNECT;
    const stopUrl = API_ROUTES.EEG.STOP;

    const blob = new Blob([JSON.stringify({ reason: '浏览器重载/关闭自动清理' })], { type: 'application/json' });

    navigator.sendBeacon(disconnectUrl, blob);
    navigator.sendBeacon(stopUrl, blob);

    if (connectionCheckInterval) clearInterval(connectionCheckInterval);
    if (realtimeStatsInterval) clearInterval(realtimeStatsInterval);
    if (barrageTimer) clearInterval(barrageTimer);
    if (bandDataInterval) clearInterval(bandDataInterval);

    if (barrageWebSocket) {
        barrageWebSocket.close();
    }
});

document.addEventListener('visibilitychange', function () {
    if (document.hidden) {
    } else {
        window.pendingBarrage = null;
        checkAnalysisStatus();
        if (!connectionCheckInterval) {
            connectionCheckInterval = setInterval(refreshConnectionStatus, 5000);
        }
    }
});

// 点击弹窗背景关闭频段详情
document.addEventListener('click', function (e) {
    if (e.target.id === 'bandDetailModal') {
        closeBandDetail();
    }
});

// ========== Kaggle 导入控制 ==========
let kagglePollingTimer = null;

function startKaggleImport() {
    const csvPath = document.getElementById('kaggleCsvPath').value.trim();
    if (!csvPath) {
        showAlert('warning', '请输入 CSV 文件路径');
        return;
    }

    const btn = document.getElementById('kaggleImportBtn');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner" style="width: 14px; height: 14px; border-width: 2px; display: inline-block; margin-right: 6px;"></span>导入启动中...';
    
    document.getElementById('kaggleImportStatus').style.display = 'block';
    updateKaggleUI(0, '正在连接服务器...');

    fetch('/api/kaggle/import', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ filePath: csvPath })
    })
    .then(r => r.json())
    .then(data => {
        if (data.success) {
            showAlert('success', '导入任务已启动');
            btn.innerHTML = '正在后台导入...';
            // 开始轮询进度
            if (kagglePollingTimer) clearInterval(kagglePollingTimer);
            kagglePollingTimer = setInterval(pollKaggleStatus, 2000);
            pollKaggleStatus(); // 立即查一次
        } else {
            showAlert('error', data.error || '启动导入失败');
            resetKaggleBtn();
        }
    })
    .catch(err => {
        console.error('Kaggle import error:', err);
        showAlert('error', '请求失败');
        resetKaggleBtn();
    });
}

function pollKaggleStatus() {
    fetch('/api/kaggle/status')
        .then(r => r.json())
        .then(data => {
            if (data.error && !data.success && data.error === '未登录') {
                return;
            }
            
            // 如果后端结束导入，没有发送 progress，我们需要通过 importedRows 判断是否达到了 100%
            let p = data.progress || 0;
            if (!data.importing && data.totalRows > 0 && data.importedRows === data.totalRows) {
                p = 100;
            }
            
            const msg = data.status || '';

            if (data.importing) {
                updateKaggleUI(p, msg || `正在导入 (${data.importedRows}/${data.totalRows})`);
            } else {
                // 停止轮询
                if (kagglePollingTimer) {
                    clearInterval(kagglePollingTimer);
                    kagglePollingTimer = null;
                }
                
                if (data.importedRows > 0 && data.importedRows === data.totalRows) {
                    updateKaggleUI(100, `✅ 导入完成！共 ${data.totalRows} 行`);
                    const btn = document.getElementById('kaggleImportBtn');
                    btn.innerHTML = '✅ 导入成功';
                    btn.classList.add('btn-outline');
                    setTimeout(() => resetKaggleBtn(), 5000);
                } else if (data.totalRows === 0 && !data.lastError) {
                    resetKaggleBtn();
                } else if (data.lastError) {
                    updateKaggleUI(p, `❌ 错误: ${data.lastError}`);
                    showAlert('error', data.lastError);
                    resetKaggleBtn();
                } else {
                    updateKaggleUI(p, msg || `导入已结束`);
                    resetKaggleBtn();
                }
            }
        })
        .catch(err => {
            console.error('Poll status error', err);
        });
}

function updateKaggleUI(progress, text) {
    const p = parseFloat(progress) || 0;
    document.getElementById('kaggleProgressBar').style.width = p + '%';
    document.getElementById('kaggleProgressPercent').textContent = p.toFixed(1) + '%';
    document.getElementById('kaggleStatusText').textContent = text;
}

function resetKaggleBtn() {
    const btn = document.getElementById('kaggleImportBtn');
    btn.disabled = false;
    btn.innerHTML = '📥 开始导入';
    btn.classList.remove('btn-outline');
    if (kagglePollingTimer) {
        clearInterval(kagglePollingTimer);
        kagglePollingTimer = null;
    }
}

// ========== 悬浮球拖拽逻辑 ==========
document.addEventListener('DOMContentLoaded', function() {
    const ball = document.getElementById('floatingBall');
    if (!ball) return;
    
    let isDragging = false;
    let startX, startY, initialX, initialY;
    let hasMoved = false;

    // 禁用默认拖拽以防干扰定制逻辑
    ball.ondragstart = function() { return false; };

    ball.addEventListener('mousedown', dragStart);
    document.addEventListener('mousemove', drag);
    document.addEventListener('mouseup', dragEnd);

    // 移动端触摸支持
    ball.addEventListener('touchstart', dragStart, {passive: false});
    document.addEventListener('touchmove', drag, {passive: false});
    document.addEventListener('touchend', dragEnd);

    function dragStart(e) {
        if (e.type === 'touchstart') {
            startX = e.touches[0].clientX;
            startY = e.touches[0].clientY;
        } else {
            startX = e.clientX;
            startY = e.clientY;
        }

        const rect = ball.getBoundingClientRect();
        initialX = rect.left;
        initialY = rect.top;
        
        isDragging = true;
        hasMoved = false;
        
        // 拖拽时消除过渡动画和变换动画造成的吸滞感
        ball.style.transition = 'none';
        ball.style.animation = 'none';
        ball.style.cursor = 'grabbing';
    }

    function drag(e) {
        if (!isDragging) return;
        
        let clientX, clientY;
        if (e.type === 'touchmove') {
            clientX = e.touches[0].clientX;
            clientY = e.touches[0].clientY;
            if (e.cancelable) e.preventDefault();
        } else {
            clientX = e.clientX;
            clientY = e.clientY;
        }

        const deltaX = clientX - startX;
        const deltaY = clientY - startY;

        // 设置移动阈值区分点击和拖放
        if (Math.abs(deltaX) > 3 || Math.abs(deltaY) > 3) {
            hasMoved = true;
        }

        if (hasMoved) {
            let newX = initialX + deltaX;
            let newY = initialY + deltaY;
            
            const maxX = window.innerWidth - ball.offsetWidth;
            const maxY = window.innerHeight - ball.offsetHeight;
            
            newX = Math.max(0, Math.min(newX, maxX));
            newY = Math.max(0, Math.min(newY, maxY));

            ball.style.left = newX + 'px';
            ball.style.top = newY + 'px';
            ball.style.right = 'auto'; // 清除原来的right定位
            ball.style.bottom = 'auto'; // 清除原来的bottom定位
        }
    }

    function dragEnd(e) {
        if (!isDragging) return;
        isDragging = false;
        
        ball.style.cursor = 'pointer';
        ball.style.transition = 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)';
        
        // 如果悬浮球是拖放，就恢复动画效果; 
        if (hasMoved) {
            // 用一个小小的timeout恢复呼吸动画以保证不冲突
            setTimeout(() => {
                ball.style.animation = 'floatingBallIdle 3s ease-in-out infinite';
            }, 300);
        } else {
            // 未移动（或移动极小），则是点击事件，触发侧边栏打开
            ball.style.animation = 'floatingBallIdle 3s ease-in-out infinite';
            if (typeof toggleBarrageSidebar === 'function') {
                toggleBarrageSidebar();
            }
        }
    }
});
