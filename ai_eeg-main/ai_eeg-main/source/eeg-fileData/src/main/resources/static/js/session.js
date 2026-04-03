// === session.js ===

// 加载对话会话内容
    async function loadConversationSession(sessionId) {
        try {
            const response = await fetch(API_ROUTES.CONVERSATIONS.MESSAGES(sessionId));

            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();

            if (data.success) {
                const messages = data.data.messages || [];
                currentConversationSessionId = sessionId;

                // 清空当前聊天
                const chatMessages = DOM_CACHE.chatMessages;
                chatMessages.innerHTML = '';

                // 更新标题
                const sessionInfo = data.data.sessionInfo;
                const title = sessionInfo ? sessionInfo.title : sessionId.substring(0, 8);
                DOM_CACHE.chatTitle.textContent = title;

                // 添加历史消息
                messages.forEach(msg => {
                    // 添加用户消息
                    addMessageToChat('user', msg.userQuery);

                    // 解析工具使用信息
                    let toolsUsed = [];
                    if (msg.toolsUsed) {
                        try {
                            toolsUsed = JSON.parse(msg.toolsUsed);
                        } catch (e) {
                            console.warn('解析工具信息失败:', e);
                        }
                    }

                    // 添加AI回复
                    addMessageToChat('assistant', msg.aiResponse, toolsUsed);
                });

                // 更新活跃状态
                document.querySelectorAll('.conversation-item').forEach(item => {
                    item.classList.remove('active');
                });
                document.querySelector(`[data-session-id="${sessionId}"]`).classList.add('active');

                showAlert('success', '对话已加载');
            } else {
                showAlert('error', data.error || '加载对话失败');
            }
        } catch (error) {
            console.error('加载对话会话失败:', error);
            showAlert('error', '网络错误');
        }
    }

// 加载会话历史
    async function loadSessionHistory() {
        const sessionList = DOM_CACHE.sessionList;

        try {
            const response = await fetch(API_ROUTES.SESSIONS.HISTORY + '?limit=5');
            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();

            if (data.success && data.sessions) {
                if (data.sessions.length === 0) {
                    sessionList.innerHTML = '<div style="padding: 12px; text-align: center; color: #666; font-size: 12px;">暂无会话历史</div>';
                    return;
                }

                let html = '';
                data.sessions.forEach(session => {
                    const time = formatDateTime(session.sessionStartTimeUtc);
                    const duration = Math.floor((session.totalDurationSeconds || 0) / 60);

                    html += `
                        <div class="session-item" onclick="showSessionDetail(${session.id})">
                            <div class="session-id">会话 #${session.id}</div>
                            <div class="session-time">${time} • ${duration}分钟</div>
                        </div>
                    `;
                });

                sessionList.innerHTML = html;
            } else {
                sessionList.innerHTML = '<div style="padding: 12px; text-align: center; color: #666;">加载失败</div>';
            }
        } catch (error) {
            console.error('加载会话历史失败:', error);
            sessionList.innerHTML = '<div style="padding: 12px; text-align: center; color: #666;">网络错误</div>';
        }
    }

// 显示会话详情
    async function showSessionDetail(sessionId) {
        try {
            const response = await fetch(API_ROUTES.SESSIONS.BY_ID(sessionId));
            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();
            if (data.success && data.session) {
                const session = data.session;
                const startTime = formatDateTime(session.sessionStartTimeUtc);
                const endTime = session.sessionEndTimeUtc ? formatDateTime(session.sessionEndTimeUtc) : '进行中';
                const duration = Math.floor((session.totalDurationSeconds || 0) / 60);

                showAlert('info', `会话 #${session.id}\n开始：${startTime}\n结束：${endTime}\n持续：${duration}分钟`);
            }
        } catch (error) {
            console.error('获取会话详情失败:', error);
        }
    }

// 加载会话统计
    async function loadSessionStatistics() {
        try {
            const response = await fetch(API_ROUTES.SESSIONS.STATISTICS);
            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();
            if (data.success && data.statistics) {
                const stats = data.statistics;
                const avgMinutes = Math.floor(stats.avgDurationSeconds / 60);

                showAlert('info',
                    `会话统计信息：\n` +
                    `总会话数：${stats.totalSessions}\n` +
                    `已完成：${stats.completedSessions}\n` +
                    `平均时长：${avgMinutes}分钟\n` +
                    `总数据包：${(stats.totalRawPackets + stats.totalFiltPackets + stats.totalBandPackets).toLocaleString()}`
                );
            }
        } catch (error) {
            console.error('加载会话统计失败:', error);
            showAlert('error', '加载统计信息失败');
        }
    }

// 加载最新会话
    async function loadLatestSession() {
        try {
            const response = await fetch(API_ROUTES.SESSIONS.LATEST_COMPLETED);
            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();
            if (data.success && data.hasLatestSession) {
                const session = data.session;
                const time = formatDateTime(session.sessionStartTimeUtc);
                const duration = Math.floor((session.totalDurationSeconds || 0) / 60);

                showAlert('info',
                    `最新完成会话：\n` +
                    `会话ID：${session.id}\n` +
                    `时间：${time}\n` +
                    `时长：${duration}分钟\n` +
                    `状态：${session.sessionStatus}`
                );
            } else {
                showAlert('info', '没有找到已完成的会话');
            }
        } catch (error) {
            console.error('加载最新会话失败:', error);
            showAlert('error', '加载最新会话失败');
        }
    }

