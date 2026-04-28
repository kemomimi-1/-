// ========== 全局核心变量 [v2.0.0] ==========
    let currentUser = null;
    let userTimezone = '';
    let connectionCheckInterval = null;
    let realtimeStatsInterval = null;
    window.barrageTimer = null;
    let barrageWebSocket = null;
    let isBarrageActive = false;
    let isTyping = false;
    let currentFilter = 'all';
    let currentConversationSessionId = null;
    let selectedRating = 0;
    let barrageHistory = [];
    let isSidebarExpanded = false;
    let floatingBarrageQueue = [];
    let isBarrageShowing = false;
    let pendingBarrage = null;
    window.isSystemConnected = false;
    let startAnalysisController = null;

    // 新增：悬浮球未读计数
    let unreadBarrageCount = 0;
    // 新增：当前活动页面 tab
    let currentTab = 'chat';
    // 新增：实时频段数据轮询定时器
    let bandDataInterval = null;

// ========== DOM 节点缓存 ==========
const DOM_CACHE = {
    get activeSession() { return document.getElementById("activeSession"); },
    get alertContainer() { return document.getElementById("alertContainer"); },
    get bandPackets() { return document.getElementById("bandPackets"); },
    get bandPort() { return document.getElementById("bandPort"); },
    get bandStreamStatus() { return document.getElementById("bandStreamStatus"); },
    get barrageList() { return document.getElementById("barrageList"); },
    get barrageSidebar() { return document.getElementById("barrageSidebar"); },
    get barrageSidebarOverlay() { return document.getElementById("barrageSidebarOverlay"); },
    get chatInput() { return document.getElementById("chatInput"); },
    get chatMessages() { return document.getElementById("chatMessages"); },
    get chatTitle() { return document.getElementById("chatTitle"); },
    get connectBtn() { return document.getElementById("connectBtn"); },
    get connectionStatus() { return document.getElementById("connectionStatus"); },
    get conversationList() { return document.getElementById("conversationList"); },
    get disconnectBtn() { return document.getElementById("disconnectBtn"); },
    get filtPackets() { return document.getElementById("filtPackets"); },
    get filtPort() { return document.getElementById("filtPort"); },
    get filtStreamStatus() { return document.getElementById("filtStreamStatus"); },
    get leftSidebar() { return document.getElementById("leftSidebar"); },
    get portAllocationStatus() { return document.getElementById("portAllocationStatus"); },
    get portInfo() { return document.getElementById("portInfo"); },
    get rawPackets() { return document.getElementById("rawPackets"); },
    get rawPort() { return document.getElementById("rawPort"); },
    get rawStreamStatus() { return document.getElementById("rawStreamStatus"); },
    get rightSidebar() { return document.getElementById("rightSidebar"); },
    get sendButton() { return document.getElementById("sendButton"); },
    get serverIP() { return document.getElementById("serverIP"); },
    get sessionList() { return document.getElementById("sessionList"); },
    get stopSessionBtn() { return document.getElementById("stopSessionBtn"); },
    get transmissionRate() { return document.getElementById("transmissionRate"); },
    get userName() { return document.getElementById("userName"); },
    // 新增：导航栏按钮
    get startAnalysisNavBtn() { return document.getElementById("startAnalysisNavBtn"); },
    get stopAnalysisNavBtn() { return document.getElementById("stopAnalysisNavBtn"); },
    // 新增：悬浮球
    get floatingBall() { return document.getElementById("floatingBall"); },
    get floatingBallBadge() { return document.getElementById("floatingBallBadge"); },
    // 新增：监测页面元素
    get monitorStatusTitle() { return document.getElementById("monitorStatusTitle"); },
    get monitorStatusDesc() { return document.getElementById("monitorStatusDesc"); },
    get monitorStatusLabel() { return document.getElementById("monitorStatusLabel"); },
    get timelineList() { return document.getElementById("timelineList"); },
    get timelineCount() { return document.getElementById("timelineCount"); },
};
