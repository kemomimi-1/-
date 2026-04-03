    function resetAllUIComponents() {
        
        // 1. 弹幕按钮重置
        const startBtn = DOM_CACHE.startBarrageBtn;
        const stopBtn = DOM_CACHE.stopBarrageBtn;
        if (startBtn) startBtn.style.display = 'flex';
        if (stopBtn) stopBtn.style.display = 'none';
        isBarrageActive = false;

        // 2. 连接按钮重置 (恢复为初始可请求状态)
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
    document.addEventListener('DOMContentLoaded', function() {
        initPage(); // 统一入口
        setupInputHandlers();
    });

    // 初始化弹幕WebSocket连接
    let wsReconnectAttempts = 0;
    const WS_MAX_RECONNECT = 5;



    // 开始实时分析
    async function startRealTimeAnalysis() {
        if (!currentUser) {
            showAlert('error', '请先登录');
            return;
        }

        // 【任务 2：校验拦截】确保先连接才能开启分析
        if (!window.isSystemConnected) {
            showAlert('warning', '请先在右侧控制面板中“请求连接”并启动数据传输');
            // 顺便展开一下面板，免得用户找不到
            const connectionAccordion = document.querySelector('.accordion-header');
            if (connectionAccordion && !connectionAccordion.classList.contains('active')) {
                toggleAccordion(connectionAccordion);
            }
            return;
        }

        const startBtn = DOM_CACHE.startBarrageBtn;
        const stopBtn = DOM_CACHE.stopBarrageBtn;

        startBtn.disabled = true;

        // 【任务 5-3：熔断机制】创建一个新的中断器
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

            // 【任务 5-3：二次校验】如果在等待后端响应的请求中，连接已经断了，则立即停止启动
            if (!window.isSystemConnected) {
                console.warn('⚠️ 分析启动请求成功，但由于连接已断开，正在撤回开启流程');
                await fetch(API_ROUTES.EEG.STOP, { method: 'POST' }).catch(() => {});
                startBtn.disabled = false;
                return;
            }

            if (data.success) {
                isBarrageActive = true;
                startBtn.style.display = 'none';
                stopBtn.style.display = 'flex';

                // 初始化WebSocket连接
                initializeBarrageWebSocket();

                showAlert('success', '实时分析已启动');
            } else {
                showAlert('error', data.error || '启动实时分析失败');
            }
        } catch (error) {
            if (error.name === 'AbortError') {
                return;
            }
            console.error('启动实时分析失败:', error);
            showAlert('error', '网络错误，请检查服务器连接');
        } finally {
            startBtn.disabled = false;
            startAnalysisController = null;
        }
    }

    // 停止实时分析
    async function stopRealTimeAnalysis() {
        const startBtn = DOM_CACHE.startBarrageBtn;
        const stopBtn = DOM_CACHE.stopBarrageBtn;

        stopBtn.disabled = true;

        try {
            const response = await fetch(API_ROUTES.EEG.STOP, {
                method: 'POST'
            });

            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();

            if (data.success) {
                isBarrageActive = false;
                startBtn.style.display = 'flex';
                stopBtn.style.display = 'none';

                // 关闭WebSocket连接
                if (barrageWebSocket) {
                    barrageWebSocket.close();
                    barrageWebSocket = null;
                }

                showAlert('success', '实时分析已停止');
            } else {
                showAlert('error', data.error || '停止实时分析失败');
            }
        } catch (error) {
            console.error('停止实时分析失败:', error);
            showAlert('error', '网络错误');
        } finally {
            stopBtn.disabled = false;
        }
    }

    // 处理新弹幕
    // 处理新弹幕：串行模式 + 始终追新
    

    /**
     * 【串行接力棒】显示当前排队中最新的一条
     */


    /**
     * 创建并在页面上滑动显示浮动弹幕
     */


    

    



    

    

    

    

    

    

    

    

    // ========== 原有函数保持不变 ==========

    

    // 检测用户时区
    function detectTimezone() {
        try {
            userTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
        } catch (error) {
            console.error('时区检测失败:', error);
            userTimezone = 'UTC';
        }
    }

    // 设置输入处理
    function setupInputHandlers() {
        const chatInput = DOM_CACHE.chatInput;

        // 自动调整输入框高度
        chatInput.addEventListener('input', function() {
            this.style.height = 'auto';
            this.style.height = Math.min(this.scrollHeight, 120) + 'px';
        });

        // 键盘快捷键
        chatInput.addEventListener('keydown', function(e) {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                sendMessage();
            }
        });
    }

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    

    // 切换左侧边栏
    function toggleLeftSidebar() {
        const sidebar = DOM_CACHE.leftSidebar;
        sidebar.classList.toggle('collapsed');
    }

    // 切换右侧边栏
    function toggleRightSidebar() {
        const sidebar = DOM_CACHE.rightSidebar;
        sidebar.classList.toggle('collapsed');
    }

    // 切换折叠面板
    function toggleAccordion(element) {
        const content = element.nextElementSibling;
        const isActive = content.classList.contains('active');

        // 切换当前面板
        if (isActive) {
            content.classList.remove('active');
            element.classList.remove('active');
        } else {
            content.classList.add('active');
            element.classList.add('active');
        }
    }

    // 显示提示消息
    

    

    /**
     * 【关键】检查后端实时分析状态并同步 UI
     */
    async function checkAnalysisStatus() {
        if (!currentUser) return;
        
        try {
            const response = await fetch(API_ROUTES.EEG.STATUS);
            if (response.ok) {
                const data = await response.json();
                
                if (data.success && data.analysisActive) {
                    isBarrageActive = true;
                    
                    // 同步按钮 UI
                    const startBtn = DOM_CACHE.startBarrageBtn;
                    const stopBtn = DOM_CACHE.stopBarrageBtn;
                    if (startBtn) startBtn.style.display = 'none';
                    if (stopBtn) stopBtn.style.display = 'flex';
                    
                    // 立即建立 WebSocket 连接
                    if (!barrageWebSocket) {
                        initializeBarrageWebSocket();
                    }
                    
                    // 启动轮询兜底
                    if (barrageTimer) clearInterval(barrageTimer);
                    barrageTimer = setInterval(checkForNewBarrages, 5000);
                }
            }
        } catch (error) {
            console.warn('检查分析状态失败:', error);
        }
    }

    /**
     * 更新用户信息显示
     */


    /**
     * 初始化弹幕系统渲染容器
     */


    // 初始化页面
    async function initPage() {
        
        // 【任务 3：强制复位】开机先清空一切，不论后端状态，保证 UI 逻辑不粘连
        resetAllUIComponents();

        try {
            // 1. 首先尝试从后端获取最新登录状态 (Session 驱动)
            const response = await fetch(API_ROUTES.AUTH.STATUS);
            const data = await response.json();
            
            if (data.authenticated) {
                // 构造用户信息对象
                currentUser = {
                    userId: data.userId,
                    username: data.username
                };
                
                // 将状态存入 localStorage 供其他逻辑使用
                localStorage.setItem('currentUser', JSON.stringify(currentUser));
                updateUserDisplay();
                
                // 2. 同步状态清理 (任务 3：强力归零，确保单次刷新即生效)
                await Promise.all([
                    fetch(API_ROUTES.EEG.STOP, { method: 'POST' }).catch(() => {}),
                    fetch(API_ROUTES.CONNECTION.DISCONNECT, { method: 'POST' }).catch(() => {})
                ]);
                
                // 3. 重新同步分析状态
                await checkAnalysisStatus();
                
                // 4. 加载各种历史记录
                loadBarrageHistory();      // 弹幕历史
                loadConversationHistory(); // 【修复】补充加载对话历史，解决开机卡死问题
            } else {
                console.warn('❌ 用户未登录，正在跳转至登录页...');
                window.location.href = '/login.html';
                return;
            }
        } catch (error) {
            console.error('初始化页面失败:', error);
            // 备选计划：尝试使用 localStorage (如果是网络波动)
            const userInfo = localStorage.getItem('currentUser');
            if (userInfo) {
                currentUser = JSON.parse(userInfo);
                updateUserDisplay();
                
                // 同样进行清理
                await Promise.all([
                    fetch(API_ROUTES.EEG.STOP, { method: 'POST' }).catch(() => {}),
                    fetch(API_ROUTES.CONNECTION.DISCONNECT, { method: 'POST' }).catch(() => {})
                ]);
                
                await checkAnalysisStatus();
            } else {
                window.location.href = '/login.html';
            }
        }

        // 初始化弹幕渲染容器
        initBarrageSystem();

        // 5. 【任务 3：UI 状态最终对齐】
        refreshConnectionStatus();
    }

    // 页面卸载时清理资源
    window.addEventListener('beforeunload', function() {
        // 1. 发送“物理断开”和“停止分析”信号 (sendBeacon 保证在页面消失前发出)
        const disconnectUrl = API_ROUTES.CONNECTION.DISCONNECT;
        const stopUrl = API_ROUTES.EEG.STOP;
        
        const blob = new Blob([JSON.stringify({ reason: '浏览器重载/关闭自动清理' })], { type: 'application/json' });
        
        // 双管齐下，确保后端彻底归零
        navigator.sendBeacon(disconnectUrl, blob);
        navigator.sendBeacon(stopUrl, blob);

        // 2. 清理计时器
        if (connectionCheckInterval) clearInterval(connectionCheckInterval);
        if (realtimeStatsInterval) clearInterval(realtimeStatsInterval);
        if (barrageTimer) clearInterval(barrageTimer);
        
        // 3. 关闭 WebSocket
        if (barrageWebSocket) {
            barrageWebSocket.close();
        }
    });

    // 页面能见度改变时维护连接
    document.addEventListener('visibilitychange', function() {
        if (document.hidden) {
        } else {
            
            // 【任务 4：防洪拦截】切屏回来时，直接抛弃积压的过期弹幕，保护用户视线
            window.pendingBarrage = null; 
            
            checkAnalysisStatus(); // 每次回来都对齐一次状态
            if (!connectionCheckInterval) {
                connectionCheckInterval = setInterval(refreshConnectionStatus, 5000);
            }
        }
    });

