// 工具类方法
// 获取状态描述
    function getStateDescription(state) {
        const stateMap = {
            'DEEP_RELAXATION': '深度放松',
            'RELAXED': '放松状态',
            'FOCUSED': '专注状态',
            'ALERT': '警觉状态',
            'STRESSED': '紧张状态',
            'DROWSY': '困倦状态',
            'MEDITATIVE': '冥想状态',
            'CREATIVE': '创造状态',
            'HYPERACTIVE': '过度活跃',
            'UNBALANCED': '状态失衡'
        };
        return stateMap[state] || state;
    }

// 获取状态对应的颜色代码
    function getStateColor(state) {
        const colorMap = {
            'DEEP_RELAXATION': '#06d6a0',
            'RELAXED': '#10b981',
            'FOCUSED': '#3b82f6',
            'ALERT': '#f59e0b',
            'STRESSED': '#ef4444',
            'DROWSY': '#8b5cf6',
            'MEDITATIVE': '#06b6d4',
            'CREATIVE': '#ec4899',
            'HYPERACTIVE': '#dc2626',
            'UNBALANCED': '#6b7280'
        };
        return colorMap[state] || '#6b7280';
    }

// 格式化日期时间
    function formatDateTime(dateTimeString) {
        if (!dateTimeString) return '-';

        try {
            // 后端已使用 Asia/Shanghai，时间无需 UTC 标记
            const date = new Date(dateTimeString.replace(' ', 'T'));
            return date.toLocaleString('zh-CN', {
                timeZone: userTimezone,
                month: '2-digit',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit'
            });
        } catch (error) {
            return dateTimeString;
        }
    }

// 格式化对话历史时间显示的函数
    function formatConversationTime(dateTimeString) {
        if (!dateTimeString) return '-';

        try {
            const date = new Date(dateTimeString);

            if (isNaN(date.getTime())) {
                console.warn('对话历史时间格式无效:', dateTimeString);
                return dateTimeString;
            }

            return date.toLocaleString('zh-CN', {
                year: 'numeric',
                month: '2-digit',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit',
                hour12: false
            });

        } catch (error) {
            console.error('格式化对话历史时间失败:', error, 'Input:', dateTimeString);
            return dateTimeString;
        }
    }

// 显示高级提示消息 [Premium UI]
    function showAlert(type, message) {
        let alertContainer = DOM_CACHE.alertContainer;
        if (!alertContainer) {
            alertContainer = document.createElement('div');
            alertContainer.id = 'alertContainer';
            document.body.appendChild(alertContainer);
        }

        const alert = document.createElement('div');
        alert.className = `alert ${type}`;
        
        // 分配图标
        let iconName = 'info';
        if (type === 'success') iconName = 'check-circle';
        if (type === 'error') iconName = 'x-circle';
        if (type === 'warning') iconName = 'alert-circle';

        alert.innerHTML = `
            <i data-lucide="${iconName}" class="alert-icon"></i>
            <div class="alert-message">${message}</div>
        `;

        alertContainer.appendChild(alert);
        
        // 渲染图标
        if (window.lucide) {
            lucide.createIcons({
                attrs: { class: 'alert-icon' },
                nameAttr: 'data-lucide'
            });
        }

        // 5秒后移除动画
        setTimeout(() => {
            alert.style.animation = 'alertFadeOut 0.5s ease forwards';
            setTimeout(() => alert.remove(), 500);
        }, 5000);
    }

