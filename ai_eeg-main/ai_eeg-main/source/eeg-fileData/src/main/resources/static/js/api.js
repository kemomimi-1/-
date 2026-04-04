/**
 * 统一 API 路由配置 (API_ROUTES)
 * 将所有分散的接口调用集中管理，方便维护和后续可能的环境切换
 */
const API_ROUTES = {
    // 认证相关
    AUTH: {
        STATUS: '/api/auth/status',
        LOGOUT: '/api/auth/logout'
    },
    // 连接管理相关
    CONNECTION: {
        REQUEST: '/api/connection/request',
        DISCONNECT: '/api/connection/disconnect',
        FORCE_CLEANUP: '/api/connection/force-cleanup',
        STATUS: '/api/connection/status',
        STREAM_STATUS: '/api/connection/stream-status',
        REAL_TIME_STATS: '/api/connection/real-time-stats'
    },
    // EEG 分析和弹幕相关
    EEG: {
        START: '/api/realtime-analysis/start',
        STOP: '/api/realtime-analysis/stop',
        STATUS: '/api/realtime-analysis/status',
        BARRAGE: '/api/realtime-analysis/barrages', // 支持 ?limit=... 等
        BARRAGE_BY_ID: (id) => `/api/realtime-analysis/barrages/${id}`, // 动态路由
        CONFIG_INTERVAL: '/api/realtime-analysis/config/interval' // 修改分析间隔
    },
    // 会话流管理
    SESSIONS: {
        HISTORY: '/api/sessions/history', // 支持 ?limit=...
        LATEST_COMPLETED: '/api/sessions/latest-completed',
        STATISTICS: '/api/sessions/statistics',
        END: '/api/sessions/end',
        BY_ID: (id) => `/api/sessions/${id}`
    },
    // 对话与AI问答
    CONVERSATIONS: {
        NEW: '/api/conversations/new',
        BY_ID: (id) => `/api/conversations/${id}`,
        MESSAGES: (id) => `/api/conversations/${id}/messages`,
        BOOKMARK: (id) => `/api/conversations/${id}/bookmark`,
        AI_QUERY: '/api/ai/query',
        AI_QUERY_STREAM: '/api/ai/query-stream'
    }
};
