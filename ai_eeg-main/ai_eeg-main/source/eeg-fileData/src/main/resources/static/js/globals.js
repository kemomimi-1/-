// ========== 全局核心变量 [v1.0.6] ==========
    let currentUser = null;
    let userTimezone = '';
    let connectionCheckInterval = null;
    let realtimeStatsInterval = null;
    window.barrageTimer = null; // 【强制全局】绝不会再报未定义错误
    let barrageWebSocket = null;
    let isBarrageActive = false;
    let isTyping = false;
    let currentFilter = 'all';
    let currentConversationSessionId = null;
    let selectedRating = 0;
    let barrageHistory = [];
    let isSidebarExpanded = false;
    let floatingBarrageQueue = [];
    let isBarrageShowing = false; // 【关键】标识当前是否有弹幕正在屏幕上飞行
    let pendingBarrage = null; // 【关键】存储排队的最新一条弹幕对象
    window.isSystemConnected = false; // 【关键】记录系统是否已成功建立脑波连接 (TCP/UDP链路)
    let startAnalysisController = null; // 【任务 5-3：熔断器】用于阻止在断开连接时仍在排队的分析请求

    /**
     * 【任务 3：UI 全局重置】强制刷机级归零，不留任何残留状态
     */
    

// ========== DOM 节点缓存 ==========
const DOM_CACHE = {
    get activeSession() { return document.getElementById("activeSession"); },
    get alertContainer() { return document.getElementById("alertContainer"); },
    get bandPackets() { return document.getElementById("bandPackets"); },
    get bandPort() { return document.getElementById("bandPort"); },
    get bandStreamStatus() { return document.getElementById("bandStreamStatus"); },
    get barrageExpandBtn() { return document.getElementById("barrageExpandBtn"); },
    get barrageFloatArea() { return document.getElementById("barrageFloatArea"); },
    get barrageList() { return document.getElementById("barrageList"); },
    get barrageSidebar() { return document.getElementById("barrageSidebar"); },
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
    get startBarrageBtn() { return document.getElementById("startBarrageBtn"); },
    get stopBarrageBtn() { return document.getElementById("stopBarrageBtn"); },
    get stopSessionBtn() { return document.getElementById("stopSessionBtn"); },
    get transmissionRate() { return document.getElementById("transmissionRate"); },
    get userName() { return document.getElementById("userName"); },
};
