/**
 * live-dashboard.js
 * Controls the tabbed navigation and real-time Canvas/SVG rendering for Neuro Status Dashboard.
 */

/* 1. Tab Navigation Logic */
function switchRightPanel(panelId, btnElement) {
    // Close notif dropdown if open
    closeNotifDropdown();

    // Hide all panels
    const panels = document.querySelectorAll('.sidebar-panel');
    panels.forEach(p => p.style.display = 'none');
    
    // Show selected
    const target = document.getElementById(panelId);
    if(target) target.style.display = 'block';

    // Update active state on nav strip
    const navItems = document.querySelectorAll('.nav-item');
    navItems.forEach(n => n.classList.remove('active'));
    if(btnElement) btnElement.classList.add('active');
}

/* Notification Dropdown Toggle */
function toggleNotifDropdown() {
    const dropdown = document.getElementById('notifDropdown');
    if(!dropdown) return;

    const isOpen = dropdown.classList.contains('open');
    if(isOpen) {
        closeNotifDropdown();
    } else {
        dropdown.classList.add('open');
        clearNotifBadge();
        // Load history when opening
        if(typeof loadBarrageHistory === 'function') {
            loadBarrageHistory();
        }
    }
}

function closeNotifDropdown() {
    const dropdown = document.getElementById('notifDropdown');
    if(dropdown) dropdown.classList.remove('open');
}

/* Notification badge helpers */
function showNotifBadge() {
    const badge = document.getElementById('notifBadge');
    if(badge) badge.style.display = 'block';
}

function clearNotifBadge() {
    const badge = document.getElementById('notifBadge');
    if(badge) badge.style.display = 'none';
}

function clearAllNotifs() {
    clearNotifBadge();
    closeNotifDropdown();
    if(typeof showAlert === 'function') {
        showAlert('success', '已全部标为已读');
    }
}

/* 2. Real-Time Waveform Renderer (Mini Waves) */
const waveConfigs = {
    'alpha': { speed: 0.05, freq: Math.PI/10, color: '#8b5cf6', amp: 10 },
    'beta':  { speed: 0.08, freq: Math.PI/5,  color: '#3b82f6', amp: 8 },
    'theta': { speed: 0.02, freq: Math.PI/20, color: '#c084fc', amp: 12 },
    'delta': { speed: 0.01, freq: Math.PI/30, color: '#0ea5e9', amp: 14 },
    'gamma': { speed: 0.12, freq: Math.PI/3,  color: '#10b981', amp: 6 }
};
let frameCount = 0;

function drawMiniWaves() {
    frameCount++;
    ['alpha', 'beta', 'theta', 'delta', 'gamma'].forEach(band => {
        const canvas = document.getElementById('wave-' + band);
        if (!canvas) return;
        const ctx = canvas.getContext('2d');
        const W = canvas.width;
        const H = canvas.height;
        ctx.clearRect(0, 0, W, H);
        
        ctx.beginPath();
        ctx.strokeStyle = waveConfigs[band].color;
        ctx.lineWidth = 1.5;
        
        for(let x = 0; x < W; x++){
            const y = H/2 + Math.sin(x * waveConfigs[band].freq + frameCount * waveConfigs[band].speed) * waveConfigs[band].amp;
            if(x === 0) ctx.moveTo(x, y);
            else ctx.lineTo(x, y);
        }
        ctx.stroke();
    });
    requestAnimationFrame(drawMiniWaves);
}

/* 3. EEG Multi-channel Renderer (Main Oscilloscope) */
const eegCanvas = document.getElementById('mainEegCanvas');
const eegCtx = eegCanvas ? eegCanvas.getContext('2d') : null;
const channels = ['Fp1-F1', 'Fp2-F7', 'Fp1-F8', 'Fp2-F8'];
let eegBuffer = [[], [], [], []];
const MAX_SAMPLES = 200;

function initEegBuffer() {
    for(let i=0; i<4; i++){
        eegBuffer[i] = new Array(MAX_SAMPLES).fill(0);
    }
}
initEegBuffer();

function drawMainEeg() {
    if(!eegCtx) return;
    
    // Auto-fit canvas resolution to display size
    const rect = eegCanvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    const displayW = Math.round(rect.width);
    const displayH = Math.round(rect.height);
    
    if (eegCanvas.width !== displayW * dpr || eegCanvas.height !== displayH * dpr) {
        eegCanvas.width = displayW * dpr;
        eegCanvas.height = displayH * dpr;
        eegCtx.scale(dpr, dpr);
    }
    
    const W = displayW;
    const H = displayH;
    eegCtx.clearRect(0, 0, W, H);
    
    // Draw 4 rows
    const rowH = H / 4;
    const labelW = 4;
    
    for(let ch=0; ch<4; ch++) {
        const baseY = ch * rowH + rowH / 2;
        
        // draw baseline
        eegCtx.beginPath();
        eegCtx.strokeStyle = 'rgba(148,163,184,0.15)';
        eegCtx.lineWidth = 0.5;
        eegCtx.moveTo(labelW, baseY);
        eegCtx.lineTo(W - 4, baseY);
        eegCtx.stroke();
        
        // draw waveform
        eegCtx.beginPath();
        eegCtx.strokeStyle = '#475569';
        eegCtx.lineWidth = 1;
        
        const data = eegBuffer[ch];
        const drawW = W - labelW - 4;
        const step = drawW / MAX_SAMPLES;
        
        // Auto-scale: find max amplitude in buffer
        let maxAmp = 0;
        for (let i = 0; i < MAX_SAMPLES; i++) {
            maxAmp = Math.max(maxAmp, Math.abs(data[i]));
        }
        const scale = maxAmp > 0 ? (rowH * 0.35) / maxAmp : 1;
        
        for(let i=0; i<MAX_SAMPLES; i++) {
            const x = labelW + i * step;
            const y = baseY - (data[i] * scale);
            
            if(i === 0) eegCtx.moveTo(x, y);
            else eegCtx.lineTo(x, y);
        }
        eegCtx.stroke();
    }
}

// 4. WebSocket Payload Handlers (Intercept standard messages if you hooked into app.js)
// We will hook globally into window.handleCustomDashboardPayload if it hits our new events.
window.addEventListener('load', () => {
    drawMiniWaves();
    drawMainEeg();
    
    // Periodically update Main EEG for smoothness if data flows
    setInterval(drawMainEeg, 50);
});

// Assuming globals.js or barrage.js processes WS messages. 
// Let's hook into the global scope.
window.updateDashboardData = function(type, dataArray) {
    if(type === 'STREAM_BAND_POWER') {
        // 后端发送顺序: [delta, theta, alpha, beta, gamma]
        // 只处理正好5个频段的数据（来自分析服务），忽略8通道原始数据
        if (!Array.isArray(dataArray)) {
            console.warn('[Neuro] STREAM_BAND_POWER data is not array:', dataArray);
            return;
        }
        
        // 如果数组长度不是5，尝试取前5个
        const values = dataArray.length >= 5 ? dataArray.slice(0, 5) : dataArray;
        if (values.length < 5) return;
        
        const bandMap = {
            'delta': values[0],
            'theta': values[1],
            'alpha': values[2],
            'beta':  values[3],
            'gamma': values[4]
        };
        
        let total = 0;
        Object.values(bandMap).forEach(v => total += Math.abs(v));
        
        console.debug('[Neuro] Band power update - total:', total, bandMap);
        
        if (total > 0) {
            ['alpha', 'beta', 'theta', 'delta', 'gamma'].forEach(band => {
                const pct = Math.min(100, Math.round((Math.abs(bandMap[band]) / total) * 100));
                
                // Update text
                const textEl = document.getElementById('val-' + band);
                if(textEl) textEl.innerText = pct + '%';
                
                // Update SVG ring
                const ringEl = document.getElementById('gauge-' + band);
                if(ringEl) ringEl.setAttribute('stroke-dasharray', `${pct}, 100`);
            });
        }
    } 
    else if (type === 'STREAM_TIMESERIESFILT' || type === 'STREAM_TIMESERIESRAW') {
        // dataArray contains [ch1_array, ch2_array, ...]
        if(dataArray && dataArray.length >= 4) {
            for(let ch=0; ch<4; ch++) {
                const incoming = dataArray[ch];
                if(incoming && incoming.length > 0) {
                    for(let v of incoming) {
                        eegBuffer[ch].push(v);
                        eegBuffer[ch].shift();
                    }
                }
            }
        }
    }
};

// We need to patch barrage.js to call updateDashboardData.

/* ====== Top Bar Status Engine ====== */
(function initTopbar() {
    // 1. Real-time clock
    function updateClock() {
        const now = new Date();
        const h = String(now.getHours()).padStart(2, '0');
        const m = String(now.getMinutes()).padStart(2, '0');
        const s = String(now.getSeconds()).padStart(2, '0');
        const el = document.getElementById('topbarClock');
        if (el) el.textContent = `${h}:${m}:${s}`;
    }
    updateClock();
    setInterval(updateClock, 1000);

    // 2. Packet counter & rate
    let lastPacketCount = 0;
    let lastRateTime = Date.now();
    
    setInterval(function() {
        const totalEl = document.getElementById('topbarPacketCount');
        const rateEl = document.getElementById('topbarRate');
        
        // Read from global counters (set by barrage.js WS handler)
        const current = window._topbarPacketTotal || 0;
        const now = Date.now();
        const elapsed = (now - lastRateTime) / 1000;
        
        if (elapsed > 0) {
            const rate = Math.round((current - lastPacketCount) / elapsed);
            if (rateEl) rateEl.textContent = rate + '/s';
        }
        
        if (totalEl) {
            if (current >= 10000) {
                totalEl.textContent = (current / 1000).toFixed(1) + 'k 包';
            } else {
                totalEl.textContent = current + ' 包';
            }
        }
        
        lastPacketCount = current;
        lastRateTime = now;
    }, 2000);

    // 3. Connection status sync
    setInterval(function() {
        const connDot = document.querySelector('#topbarConnStat .topbar-dot');
        const connText = document.querySelector('#topbarConnStat span:last-child');
        if (!connDot || !connText) return;
        
        if (window.isSystemConnected) {
            connDot.className = 'topbar-dot online';
            connText.textContent = '已连接';
        } else {
            connDot.className = 'topbar-dot offline';
            connText.textContent = '未连接';
        }
    }, 1500);
})();

// Global: update analysis badge
window.updateTopbarAnalysis = function(active) {
    const badge = document.getElementById('topbarAnalysisBadge');
    if (!badge) return;
    if (active) {
        badge.textContent = '分析中';
        badge.classList.add('active');
    } else {
        badge.textContent = '待机';
        badge.classList.remove('active');
    }
};

/* ====== Settings Panel Functions ====== */

// 弹幕开关
window._barrageEnabled = true;
function toggleBarrageSetting(enabled) {
    window._barrageEnabled = enabled;
    if (typeof showAlert === 'function') {
        showAlert('success', enabled ? '弹幕飘动已开启' : '弹幕飘动已关闭');
    }
    // Save to localStorage
    localStorage.setItem('barrageEnabled', enabled ? '1' : '0');
}
// Restore from localStorage on load
(function() {
    const saved = localStorage.getItem('barrageEnabled');
    if (saved === '0') {
        window._barrageEnabled = false;
        const cb = document.getElementById('settingBarrageEnabled');
        if (cb) cb.checked = false;
    }
})();

// 滑块 → 输入框
function updateIntervalFromSlider(val) {
    const input = document.getElementById('intervalInput');
    if (input) input.value = val;
    updateSliderFill(val);
}

// 输入框 → 滑块
function updateIntervalFromInput(val) {
    let v = parseInt(val);
    if (isNaN(v)) v = 30;
    v = Math.max(5, Math.min(3600, v));
    const input = document.getElementById('intervalInput');
    const slider = document.getElementById('settingAnalysisInterval');
    if (input) input.value = v;
    // 滑块范围只到300，超出则钉在最大值
    if (slider) slider.value = Math.min(v, parseInt(slider.max));
    updateSliderFill(v);
}

// 更新滑块填充色
function updateSliderFill(val) {
    const slider = document.getElementById('settingAnalysisInterval');
    if (slider) {
        const pct = ((val - slider.min) / (slider.max - slider.min)) * 100;
        slider.style.background = `linear-gradient(90deg, #667eea ${pct}%, #e2e8f0 ${pct}%)`;
    }
}

// 应用分析间隔
async function applyAnalysisInterval() {
    const slider = document.getElementById('settingAnalysisInterval');
    const btn = document.querySelector('.settings-apply-btn');
    if (!slider || !btn) return;

    const seconds = parseInt(slider.value);
    btn.disabled = true;
    btn.textContent = '应用中...';

    try {
        const response = await fetch(
            API_ROUTES.EEG.CONFIG_INTERVAL + '?seconds=' + seconds,
            { method: 'POST' }
        );
        const data = await response.json();

        if (data.success) {
            btn.classList.add('success');
            btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg> 已应用';
            if (typeof showAlert === 'function') {
                showAlert('success', `分析间隔已设置为 ${seconds} 秒`);
            }
            setTimeout(() => {
                btn.classList.remove('success');
                btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg> 应用';
                btn.disabled = false;
            }, 2000);
        } else {
            if (typeof showAlert === 'function') showAlert('error', data.error || '设置失败');
            btn.disabled = false;
            btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg> 应用';
        }
    } catch (e) {
        console.error('设置分析间隔失败:', e);
        if (typeof showAlert === 'function') showAlert('error', '网络错误');
        btn.disabled = false;
        btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg> 应用';
    }
}

