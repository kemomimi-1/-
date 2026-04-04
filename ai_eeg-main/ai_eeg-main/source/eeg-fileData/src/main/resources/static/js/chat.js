// === chat.js ===

// 创建新对话
    async function createNewConversation() {
        try {
            const response = await fetch(API_ROUTES.CONVERSATIONS.NEW, {
                method: 'POST'
            });

            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();

            if (data.success) {
                const newSession = data.data.session;
                currentConversationSessionId = newSession.sessionId;

                // 清空当前对话
                clearChat();

                // 更新聊天标题
                DOM_CACHE.chatTitle.textContent = '新对话 - ' + newSession.sessionId.substring(0, 8);

                // 刷新对话历史列表
                await loadConversationHistory();

                showAlert('success', '新对话已创建');
            } else {
                showAlert('error', data.error || '创建新对话失败');
            }
        } catch (error) {
            console.error('创建新对话失败:', error);
            showAlert('error', '网络错误');
        }
    }

// 发送消息 - 使用SSE流式输出
    async function sendMessage() {
        const chatInput = DOM_CACHE.chatInput;
        const sendButton = DOM_CACHE.sendButton;
        const message = chatInput.value.trim();

        if (!message || isTyping) return;

        // 添加用户消息到界面
        addMessageToChat('user', message);
        chatInput.value = '';
        chatInput.style.height = 'auto';

        // 禁用发送按钮
        sendButton.disabled = true;
        isTyping = true;

        // 创建AI消息容器（带思考指示器）
        const chatMessages = DOM_CACHE.chatMessages;
        const messageDiv = document.createElement('div');
        messageDiv.className = 'message assistant';
        const time = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
        messageDiv.innerHTML = `
            <div class="message-avatar">🧠</div>
            <div class="message-content">
                <div class="thinking-indicator" id="thinking-status">
                    <span class="thinking-dots"><span>.</span><span>.</span><span>.</span></span>
                    AI 思考中
                </div>
                <div class="message-bubble" id="stream-content" style="display:none;"></div>
                <div class="message-meta">
                    <span class="message-time">${time}</span>
                    <span class="stream-duration" id="stream-duration" style="display:none;"></span>
                </div>
            </div>
        `;
        chatMessages.appendChild(messageDiv);
        chatMessages.scrollTop = chatMessages.scrollHeight;

        try {
            const requestBody = { query: message };

            // 如果有当前对话会话ID，则传递给后端
            if (currentConversationSessionId) {
                requestBody.sessionId = currentConversationSessionId;
            }

            // 使用 fetch 发起SSE流式请求
            const response = await fetch(API_ROUTES.CONVERSATIONS.AI_QUERY_STREAM, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(requestBody)
            });

            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            // 用 ReadableStream 消费SSE事件流
            const reader = response.body.getReader();
            const decoder = new TextDecoder('utf-8');
            let fullContent = '';
            let sseBuffer = '';
            const contentEl = messageDiv.querySelector('#stream-content');
            const thinkingEl = messageDiv.querySelector('#thinking-status');
            const durationEl = messageDiv.querySelector('#stream-duration');

            // 打字机引擎：后端推送的文本积累到 targetText，定时器逐字追赶
            let targetText = '';   // 后端已推送的完整文本
            let displayedLen = 0;  // 前端已显示到第几个字符
            let typewriterTimer = null;
            let streamDone = false;

            function startTypewriter() {
                if (typewriterTimer) return;
                typewriterTimer = setInterval(() => {
                    if (displayedLen < targetText.length) {
                        // 每次打 1~3 个字符，长文本时略快
                        const speed = targetText.length - displayedLen > 50 ? 3 : 1;
                        displayedLen = Math.min(displayedLen + speed, targetText.length);
                        const visibleText = targetText.substring(0, displayedLen);
                        contentEl.style.display = 'block';
                        contentEl.innerHTML = formatAIResponse(visibleText);
                        chatMessages.scrollTop = chatMessages.scrollHeight;
                    } else if (streamDone) {
                        // 流结束且全部打完
                        clearInterval(typewriterTimer);
                        typewriterTimer = null;
                        // 最终渲染确保完整
                        contentEl.innerHTML = formatAIResponse(targetText);
                        chatMessages.scrollTop = chatMessages.scrollHeight;
                    }
                    // 如果追平了但流还没结束，保持 timer 等待新字符
                }, 20);
            }

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;

                sseBuffer += decoder.decode(value, { stream: true });

                // 按行解析SSE事件
                const lines = sseBuffer.split('\n');
                sseBuffer = lines.pop(); // 保留未完成的最后一行

                let currentEvent = '';
                for (const line of lines) {
                    const trimmed = line.trim();
                    if (trimmed.startsWith('event:')) {
                        currentEvent = trimmed.substring(6).trim();
                    } else if (trimmed.startsWith('data:')) {
                        const dataStr = trimmed.substring(5).trim();
                        if (!dataStr) continue;

                        try {
                            const data = JSON.parse(dataStr);

                            if (currentEvent === 'chunk' && data.text) {
                                fullContent += data.text;
                                targetText = fullContent;
                                startTypewriter();
                            } else {
                                // 非 chunk 事件立即处理
                                handleStreamEvent(currentEvent, data, contentEl, thinkingEl, durationEl);
                            }

                            if (currentEvent === 'done' && data.sessionId) {
                                if (!currentConversationSessionId) {
                                    currentConversationSessionId = data.sessionId;
                                    DOM_CACHE.chatTitle.textContent = '对话 - ' + data.sessionId.substring(0, 8);
                                }
                            }
                        } catch (e) {
                            // JSON解析失败，跳过
                        }
                    }
                }
            }

            // 流结束，标记完成，等打字机追完
            streamDone = true;
            // 如果打字机已停但还有未显示的，立即显示
            if (displayedLen < targetText.length) {
                startTypewriter();
            } else if (fullContent) {
                contentEl.style.display = 'block';
                contentEl.innerHTML = formatAIResponse(fullContent);
                chatMessages.scrollTop = chatMessages.scrollHeight;
            }

        } catch (error) {
            console.error('流式消息发送失败:', error);
            const thinkingEl = messageDiv.querySelector('#thinking-status');
            if (thinkingEl) thinkingEl.style.display = 'none';
            const contentEl = messageDiv.querySelector('#stream-content');
            if (contentEl) {
                contentEl.style.display = 'block';
                contentEl.innerHTML = '抱歉，网络连接出现问题，请稍后再试。';
            }
        } finally {
            sendButton.disabled = false;
            isTyping = false;
            loadConversationHistory();
        }
    }

    // 处理SSE流事件（非chunk事件）
    function handleStreamEvent(eventType, data, contentEl, thinkingEl, durationEl) {
        switch (eventType) {
            case 'thinking': {
                if (data.status === 'started') {
                    thinkingEl.style.display = 'flex';
                    if (data.message) {
                        thinkingEl.innerHTML = `
                            <span class="thinking-dots"><span>.</span><span>.</span><span>.</span></span>
                            ${data.message}
                        `;
                    }
                } else if (data.status === 'done') {
                    const dur = typeof data.duration === 'number' ? data.duration.toFixed(1) : '0';
                    thinkingEl.innerHTML = `✅ 思考完成 (${dur}s)`;
                    thinkingEl.classList.add('thinking-done');
                    contentEl.style.display = 'block';
                }
                break;
            }
            case 'done': {
                const total = typeof data.totalTime === 'number' ? data.totalTime.toFixed(1) : '?';
                durationEl.textContent = `耗时 ${total}s`;
                durationEl.style.display = 'inline';
                thinkingEl.style.display = 'none';
                break;
            }
            case 'error': {
                thinkingEl.style.display = 'none';
                contentEl.style.display = 'block';
                contentEl.innerHTML = `<span style="color: #ef4444;">⚠️ ${data.message || '请求处理失败'}</span>`;
                break;
            }
        }
    }

// 添加消息到聊天界面
    function addMessageToChat(role, content, toolsUsed = [], collaborationInfo = null) {
        const chatMessages = DOM_CACHE.chatMessages;
        const messageDiv = document.createElement('div');
        messageDiv.className = `message ${role}`;

        const avatar = role === 'user' ? '👤' : '🧠';
        const time = new Date().toLocaleTimeString('zh-CN', {
            hour: '2-digit',
            minute: '2-digit'
        });

        // 构建工具标签
        let toolsHtml = '';
        if (toolsUsed && toolsUsed.length > 0) {
            const toolTags = toolsUsed.map(tool => `<span class="tool-tag">${tool}</span>`).join('');
            toolsHtml = `<div class="tools-used">${toolTags}</div>`;
        }

        // 构建协作信息
        let collaborationHtml = '';
        if (collaborationInfo) {
            const collabType = collaborationInfo.collaborationType || '单工具执行';
            const efficiency = collaborationInfo.collaborationEfficiency || 0;
            collaborationHtml = `<span style="font-size: 11px; color: #3b82f6;">协作: ${collabType} (${(efficiency * 100).toFixed(0)}%)</span>`;
        }

        // 优化AI回答的格式显示
        let formattedContent = content;
        if (role === 'assistant') {
            formattedContent = formatAIResponse(content);
        } else {
            formattedContent = content.replace(/\n/g, '<br>');
        }

        messageDiv.innerHTML = `
            <div class="message-avatar">${avatar}</div>
            <div class="message-content">
                <div class="message-bubble">${formattedContent}</div>
                <div class="message-meta">
                    <span class="message-time">${time}</span>
                    ${toolsHtml}
                    ${collaborationHtml}
                </div>
            </div>
        `;

        chatMessages.appendChild(messageDiv);
        chatMessages.scrollTop = chatMessages.scrollHeight;
    }

// 格式化AI回答显示
    function formatAIResponse(content) {
        // 将markdown格式转换为HTML
        let formatted = content;

        // 处理标题（### 标题）
        formatted = formatted.replace(/### ([^\n]+)/g, '<h3>$1</h3>');

        // 处理粗体文本（**文本**）
        formatted = formatted.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');

        // 处理列表项（- 项目）
        formatted = formatted.replace(/^- (.+)$/gm, '• $1');

        // 处理换行
        formatted = formatted.replace(/\n\n/g, '<br><br>');
        formatted = formatted.replace(/\n/g, '<br>');

        // 处理代码块（```代码```）
        formatted = formatted.replace(/```([^`]+)```/g, '<pre><code>$1</code></pre>');

        // 处理行内代码（`代码`）
        formatted = formatted.replace(/`([^`]+)`/g, '<code>$1</code>');

        return formatted;
    }

// 清除对话
    function clearChat() {
        currentConversationSessionId = null;
        DOM_CACHE.chatTitle.textContent = 'EEG AI Assistant';

        const chatMessages = DOM_CACHE.chatMessages;
        chatMessages.innerHTML = `
            <div class="message assistant">
                <div class="message-avatar">🧠</div>
                <div class="message-content">
                    <div class="message-bubble">
                        您好！我是EEG AI Assistant，专业的脑电数据分析助手。我现在配备了智能工具协作系统v3.0，可以帮助您：
                        <br><br>
                        • 深度分析您的脑电数据<br>
                        • 生成综合性数据报告和洞察<br>
                        • 智能选择最适合的工具组合<br>
                        • 协助数据传输和会话管理<br>
                        • 提供实时脑电状态分析弹幕<br>
                        <br>
                        如需开始数据传输，请点击右侧的连接控制面板。如需启动实时分析弹幕，请点击顶部的播放按钮。有什么我可以帮助您的吗？
                    </div>
                    <div class="message-meta">
                        <span class="message-time">刚刚</span>
                    </div>
                </div>
            </div>
        `;

        // 更新活跃状态
        document.querySelectorAll('.conversation-item').forEach(item => {
            item.classList.remove('active');
        });
    }

// 加载对话历史
    async function loadConversationHistory(filter = 'all') {
        const conversationList = DOM_CACHE.conversationList;
        conversationList.innerHTML = '<div class="loading"><div class="spinner"></div>加载对话历史...</div>';

        try {
            let url = '/api/conversations?page=0&size=50';

            // 根据筛选条件构建URL
            if (filter === 'bookmarked') {
                url = '/api/conversations/bookmarked?page=0&size=50';
            }

            const response = await fetch(url);
            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();

            if (data.success) {
                const sessions = data.data.sessions || [];

                if (sessions.length === 0) {
                    conversationList.innerHTML = '<div style="padding: 20px; text-align: center; color: #666; font-size: 14px;">暂无对话历史</div>';
                    return;
                }

                let html = '';
                sessions.forEach(session => {
                    const title = session.title || session.lastMessage || '新对话';
                    const displayTitle = title.length > 30 ? title.substring(0, 30) + '...' : title;

                    const time = formatConversationTime(session.updatedAt);
                    const isActive = session.sessionId === currentConversationSessionId;

                    html += `
                    <div class="conversation-item ${isActive ? 'active' : ''}" onclick="loadConversationSession('${session.sessionId}')" data-session-id="${session.sessionId}">
                        <div class="conversation-header">
                            <div class="conversation-title">${displayTitle}</div>
                            <div class="conversation-actions">
                                <svg class="action-icon bookmark-icon ${session.bookmarked ? 'bookmarked' : ''}" onclick="event.stopPropagation(); toggleSessionBookmark('${session.sessionId}')" width="16" height="16" viewBox="0 0 24 24" fill="${session.bookmarked ? 'currentColor' : 'none'}" stroke="currentColor" stroke-width="2">
                                    <path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path>
                                </svg>
                                <svg class="action-icon delete-icon" onclick="event.stopPropagation(); deleteConversationSession('${session.sessionId}')" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                    <polyline points="3,6 5,6 21,6"></polyline>
                                    <path d="M19,6 L19,20 C19,21 18,22 17,22 L7,22 C6,22 5,21 5,20 L5,6"></path>
                                    <path d="M8,6 L8,4 C8,3 9,2 10,2 L14,2 C15,2 16,3 16,4 L16,6"></path>
                                </svg>
                            </div>
                        </div>
                        <div class="conversation-meta">
                            <span class="conversation-time">${time}</span>
                            <span style="font-size: 11px; color: #999;">${session.messageCount || 0}条消息</span>
                        </div>
                    </div>
                `;
                });

                conversationList.innerHTML = html;
            } else {
                conversationList.innerHTML = '<div style="padding: 20px; text-align: center; color: #666;">加载失败</div>';
            }
        } catch (error) {
            console.error('加载对话历史失败:', error);
            conversationList.innerHTML = '<div style="padding: 20px; text-align: center; color: #666;">网络错误</div>';
        }
    }

// 过滤对话
    function filterConversations(filter) {
        currentFilter = filter;

        // 更新按钮状态
        document.querySelectorAll('.filter-btn').forEach(btn => {
            btn.classList.remove('active');
        });
        document.querySelector(`[data-filter="${filter}"]`).classList.add('active');

        loadConversationHistory(filter);
    }

// 切换会话收藏状态
    async function toggleSessionBookmark(sessionId) {
        try {
            const response = await fetch(API_ROUTES.CONVERSATIONS.BOOKMARK(sessionId), {
                method: 'POST'
            });

            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();
            if (data.success) {
                // 更新界面中的收藏状态
                const bookmarkIcon = document.querySelector(`.conversation-item[data-session-id="${sessionId}"] .bookmark-icon`);
                if (bookmarkIcon) {
                    if (data.data.isBookmarked) {
                        bookmarkIcon.classList.add('bookmarked');
                        bookmarkIcon.setAttribute('fill', 'currentColor');
                    } else {
                        bookmarkIcon.classList.remove('bookmarked');
                        bookmarkIcon.setAttribute('fill', 'none');
                    }
                }
                showAlert('success', data.data.isBookmarked ? '已收藏' : '已取消收藏');
            }
        } catch (error) {
            console.error('切换收藏状态失败:', error);
            showAlert('error', '操作失败');
        }
    }

// 删除对话会话
    async function deleteConversationSession(sessionId) {
        if (!confirm('确定要删除这个对话会话吗？删除后无法恢复。')) {
            return;
        }

        try {
            const response = await fetch(API_ROUTES.CONVERSATIONS.BY_ID(sessionId), {
                method: 'DELETE'
            });

            if (response.status === 401) {
                handleUnauthorized();
                return;
            }

            const data = await response.json();
            if (data.success) {
                showAlert('success', '对话已删除');

                // 如果删除的是当前会话，清空聊天界面
                if (currentConversationSessionId === sessionId) {
                    clearChat();
                }

                loadConversationHistory(currentFilter); // 重新加载对话列表
            } else {
                showAlert('error', data.error || '删除失败');
            }
        } catch (error) {
            console.error('删除对话会话失败:', error);
            showAlert('error', '网络错误');
        }
    }

