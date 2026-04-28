// === auth.js ===

// 检查认证状态
    async function checkAuthStatus() {
        try {
            const response = await fetch(API_ROUTES.AUTH.STATUS);
            const data = await response.json();

            if (!data.authenticated) {
                window.location.href = '/login.html';
                return;
            }

            currentUser = {
                userId: data.userId,
                username: data.username
            };

            DOM_CACHE.userName.textContent = data.username;
        } catch (error) {
            console.error('认证检查失败:', error);
            window.location.href = '/login.html';
        }
    }

// 处理未授权状态
    function handleUnauthorized() {
        showAlert('error', '登录已过期，请重新登录');
        if (connectionCheckInterval) {
            clearInterval(connectionCheckInterval);
        }
        if (realtimeStatsInterval) {
            clearInterval(realtimeStatsInterval);
        }
        setTimeout(() => {
            window.location.href = '/login.html';
        }, 2000);
    }

// 退出登录
    async function logout() {
        try {
            const response = await fetch(API_ROUTES.AUTH.LOGOUT, {
                method: 'POST'
            });

            if (response.ok) {
                // 清理所有活跃定时器
                if (connectionCheckInterval) clearInterval(connectionCheckInterval);
                if (realtimeStatsInterval) clearInterval(realtimeStatsInterval);
                if (window.barrageTimer) {
                    clearInterval(window.barrageTimer);
                    window.barrageTimer = null;
                }

                // 关闭并同步重置 WebSocket
                if (barrageWebSocket) {
                    barrageWebSocket.onclose = null;
                    barrageWebSocket.close();
                    barrageWebSocket = null;
                }

                // 重置弹幕状态锁
                window.isBarrageShowing = false;
                window.pendingBarrage = null;

                // 清除本地缓存并跳转
                localStorage.removeItem('currentUser');
                window.location.href = '/login.html';
            }
        } catch (error) {
            console.error('退出登录失败:', error);
        }
    }

    function updateUserDisplay() {
        if (!currentUser) return;
        const userNameElem = DOM_CACHE.userName;
        if (userNameElem) {
            userNameElem.textContent = currentUser.username || '用户';
        }
    }

