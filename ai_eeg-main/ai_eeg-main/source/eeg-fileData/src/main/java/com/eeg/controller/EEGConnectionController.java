// EEG连接控制器，集成增强端口管理
package com.eeg.controller;

import com.eeg.entity.EEGSession;
import com.eeg.service.EnhancedPortAllocationService;
import com.eeg.service.EEGDataReceiver;
import com.eeg.service.UserService;
import com.eeg.service.EEGSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/connection")
@RequiredArgsConstructor
public class EEGConnectionController {

    private final EnhancedPortAllocationService portService;
    private final EEGDataReceiver eegReceiver;
    private final UserService userService;
    private final EEGSessionService sessionService;

    @Value("${eeg.server.public-ip:127.0.0.1}")
    private String publicServerIp;

    /**
     * 请求EEG数据连接并创建新会话
     */
    @PostMapping("/request")
    public ResponseEntity<?> requestConnection(@RequestBody(required = false) ConnectionRequest request,
                                               HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            // 获取用户时区，如果前端没有提供则使用系统默认
            String userTimezone = (request != null && request.getTimezone() != null) ?
                    request.getTimezone() : ZoneId.systemDefault().getId();

            // 验证时区
            try {
                ZoneId.of(userTimezone);
            } catch (Exception e) {
                log.warn("无效的时区设置: {}, 使用系统默认时区", userTimezone);
                userTimezone = ZoneId.systemDefault().getId();
            }

            // 生成会话ID
            String sessionId = generateSessionId();

            // 分配端口组
            EnhancedPortAllocationService.PortAllocation allocation =
                    portService.allocatePortsForUser(userId, sessionId);

            // 更新用户的端口信息
            userService.updateUserPorts(userId, allocation.getRawPort(),
                    allocation.getFiltPort(), allocation.getBandPort());

            // 启动EEG数据监听并创建会话
            eegReceiver.startListeningForUser(userId, allocation.getRawPort(),
                    allocation.getFiltPort(), allocation.getBandPort(), userTimezone);

            // 构建响应数据
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("message", "连接请求成功，数据传输会话已创建");
            responseData.put("sessionId", sessionId);
            responseData.put("ip", publicServerIp);

            // 端口信息 - 按照OpenBCI GUI的要求顺序
            Map<String, Integer> ports = new HashMap<>();
            ports.put("TimeSeriesRaw", allocation.getRawPort());
            ports.put("TimeSeriesFilt", allocation.getFiltPort());
            ports.put("AvgBandPower", allocation.getBandPort());
            responseData.put("ports", ports);

            responseData.put("timezone", userTimezone);
            responseData.put("allocatedAt", allocation.getAllocatedAt());

            // OpenBCI GUI配置说明
            Map<String, String> instructions = new HashMap<>();
            instructions.put("step1", "在OpenBCI GUI中选择SYNTHETIC(algorithmic) 8chan模式");
            instructions.put("step2", "选择Networking，协议设置为UDP");
            instructions.put("step3", "在三个Stream中分别设置数据类型（必须按顺序）:");
            instructions.put("stream1", "Stream 1: TimeSeriesRaw -> 端口 " + allocation.getRawPort());
            instructions.put("stream2", "Stream 2: TimeSeriesFilt -> 端口 " + allocation.getFiltPort());
            instructions.put("stream3", "Stream 3: AvgBandPower -> 端口 " + allocation.getBandPort());
            instructions.put("step4", "将IP设置为 " + publicServerIp);
            instructions.put("step5", "点击START SESSION开始数据传输");
            instructions.put("important", "注意：三个数据流的顺序和类型必须严格按照上述要求配置！");
            responseData.put("instructions", instructions);

            // 端口分配详情
            Map<String, Object> allocationDetails = new HashMap<>();
            allocationDetails.put("userId", userId);
            allocationDetails.put("sessionId", sessionId);
            allocationDetails.put("allocatedAt", allocation.getAllocatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            allocationDetails.put("status", allocation.getStatus());
            allocationDetails.put("portGroup", allocation.getAllPorts());
            responseData.put("allocationDetails", allocationDetails);

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (RuntimeException e) {
            log.error("为用户 {} 请求连接时出错", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("处理连接请求时发生未知错误", e);
            return ResponseEntity.status(500).body(createErrorResponse("系统内部错误"));
        }
    }

    /**
     * 获取连接状态和会话信息
     */
    @GetMapping("/status")
    public ResponseEntity<?> getConnectionStatus(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            // 获取端口分配状态
            EnhancedPortAllocationService.PortAllocation portAllocation =
                    portService.getUserPortAllocation(userId);

            // 获取连接状态
            EEGDataReceiver.EEGConnectionStatus connectionStatus = eegReceiver.getConnectionStatus(userId);

            // 获取当前活跃会话
            var activeSession = sessionService.getActiveSession(userId);

            // 获取系统统计
            EEGDataReceiver.SystemStatistics systemStats = eegReceiver.getSystemStatistics();

            // 获取端口池统计
            EnhancedPortAllocationService.PortPoolStatistics portPoolStats =
                    portService.getPortPoolStatistics();

            // 构建响应
            Map<String, Object> response = new HashMap<>();

            // 基本连接信息
            response.put("hasActiveConnection", connectionStatus != null);
            response.put("hasActiveSession", activeSession.isPresent());
            response.put("hasPortAllocation", portAllocation != null && portAllocation.isActive());

            // 端口分配信息
            if (portAllocation != null) {
                Map<String, Object> portInfo = new HashMap<>();
                portInfo.put("status", portAllocation.getStatus());
                portInfo.put("rawPort", portAllocation.getRawPort());
                portInfo.put("filtPort", portAllocation.getFiltPort());
                portInfo.put("bandPort", portAllocation.getBandPort());
                portInfo.put("allocatedAt", portAllocation.getAllocatedAt());
                portInfo.put("lastUsedAt", portAllocation.getLastUsedAt());
                portInfo.put("sessionId", portAllocation.getSessionId());
                response.put("portAllocation", portInfo);
            }

            // 连接状态
            if (connectionStatus != null) {
                response.put("connectionStatus", connectionStatus);
            }

            // 活跃会话
            if (activeSession.isPresent()) {
                response.put("activeSession", activeSession.get());
            }

            // 数据包计数
            Map<String, Object> packetCounts = new HashMap<>();
            packetCounts.put("raw", eegReceiver.getPacketCount(userId, "TimeSeriesRaw"));
            packetCounts.put("filt", eegReceiver.getPacketCount(userId, "TimeSeriesFilt"));
            packetCounts.put("band", eegReceiver.getPacketCount(userId, "AvgBandPower"));
            response.put("packetCounts", packetCounts);

            // 系统统计
            response.put("systemStatistics", systemStats != null ? systemStats : new EEGDataReceiver.SystemStatistics());
            response.put("portPoolStatistics", portPoolStats);

            return ResponseEntity.ok(createSuccessResponse(response));

        } catch (Exception e) {
            log.error("获取用户 {} 连接状态时出错", userId, e);
            return ResponseEntity.status(500).body(createErrorResponse("获取连接状态失败: " + e.getMessage()));
        }
    }

    /**
     * 断开连接并结束会话
     */
    @PostMapping("/disconnect")
    public ResponseEntity<?> disconnect(@RequestBody(required = false) DisconnectRequest request,
                                        HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            String reason = (request != null && request.getReason() != null) ?
                    request.getReason() : "用户手动断开连接";

            // 停止数据监听并结束会话
            eegReceiver.stopListeningForUser(userId, reason);

            // 释放端口分配
            portService.releasePortsForUser(userId, reason);

            // 设置用户为非活跃状态
            userService.setUserInactive(userId);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("message", "连接已断开，会话已结束");
            responseData.put("reason", reason);
            responseData.put("disconnectedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            return ResponseEntity.ok(createSuccessResponse(responseData));
        } catch (Exception e) {
            log.error("用户 {} 断开连接时出错", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("断开连接失败: " + e.getMessage()));
        }
    }

    /**
     * 测试端口连通性
     */
    @GetMapping("/ping/{port}")
    public ResponseEntity<?> pingPort(@PathVariable int port, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            // 检查端口是否属于当前用户
            EnhancedPortAllocationService.PortAllocation allocation = portService.getUserPortAllocation(userId);
            if (allocation == null || !allocation.containsPort(port)) {
                return ResponseEntity.badRequest().body(createErrorResponse("端口不属于当前用户或未分配"));
            }

            // 确定数据流类型
            String dataType = "";
            String streamName = "";
            if (port == allocation.getRawPort()) {
                dataType = "TimeSeriesRaw";
                streamName = "原始数据流";
            } else if (port == allocation.getFiltPort()) {
                dataType = "TimeSeriesFilt";
                streamName = "滤波数据流";
            } else if (port == allocation.getBandPort()) {
                dataType = "AvgBandPower";
                streamName = "频谱数据流";
            }

            // 获取数据包计数
            long packetCount = eegReceiver.getPacketCount(userId, dataType);

            Map<String, Object> pingResult = new HashMap<>();
            pingResult.put("port", port);
            pingResult.put("dataType", dataType);
            pingResult.put("streamName", streamName);
            pingResult.put("packetCount", packetCount);
            pingResult.put("isReceivingData", packetCount > 0);

            if (packetCount > 0) {
                pingResult.put("status", "active");
                pingResult.put("message", String.format("%s端口正在接收数据，已收到 %d 个数据包", streamName, packetCount));
            } else {
                pingResult.put("status", "waiting");
                pingResult.put("message", String.format("%s端口等待OpenBCI GUI连接", streamName));
            }

            return ResponseEntity.ok(createSuccessResponse(pingResult));

        } catch (Exception e) {
            log.error("测试端口 {} 连通性时出错", port, e);
            return ResponseEntity.badRequest().body(createErrorResponse("端口测试失败: " + e.getMessage()));
        }
    }

    /**
     * 获取详细的数据流状态
     */
    @GetMapping("/stream-status")
    public ResponseEntity<?> getStreamStatus(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            // 获取当前活跃会话
            var activeSession = sessionService.getActiveSession(userId);
            if (activeSession.isEmpty()) {
                return ResponseEntity.ok(createSuccessResponse(Map.of(
                        "hasActiveSession", false,
                        "message", "当前没有活跃的数据传输会话"
                )));
            }

            var sessionData = activeSession.get();

            // 构建详细的数据流状态
            Map<String, Object> streamStatus = new HashMap<>();
            streamStatus.put("sessionId", sessionData.getId());
            streamStatus.put("sessionStartTime", sessionData.getSessionStartTimeUtc());
            streamStatus.put("sessionDuration", sessionData.calculateDurationSeconds());
            streamStatus.put("userTimezone", sessionData.getUserTimezone());

            // 各数据流状态
            Map<String, Object> streams = new HashMap<>();

            // TimeSeriesRaw流状态
            Map<String, Object> rawStream = new HashMap<>();
            rawStream.put("streamType", "TimeSeriesRaw");
            rawStream.put("description", "原始脑电数据流");
            rawStream.put("status", sessionData.getRawStreamStatus());
            rawStream.put("startTime", sessionData.getRawStreamStartTimeUtc());
            rawStream.put("lastPacketTime", sessionData.getRawStreamLastPacketTimeUtc());
            rawStream.put("totalPackets", sessionData.getRawStreamTotalPackets());
            rawStream.put("port", sessionData.getRawPort());
            rawStream.put("isActive", sessionData.getRawStreamStatus() == com.eeg.entity.EEGSession.StreamStatus.ACTIVE);
            streams.put("raw", rawStream);

            // TimeSeriesFilt流状态
            Map<String, Object> filtStream = new HashMap<>();
            filtStream.put("streamType", "TimeSeriesFilt");
            filtStream.put("description", "滤波脑电数据流");
            filtStream.put("status", sessionData.getFiltStreamStatus());
            filtStream.put("startTime", sessionData.getFiltStreamStartTimeUtc());
            filtStream.put("lastPacketTime", sessionData.getFiltStreamLastPacketTimeUtc());
            filtStream.put("totalPackets", sessionData.getFiltStreamTotalPackets());
            filtStream.put("port", sessionData.getFiltPort());
            filtStream.put("isActive", sessionData.getFiltStreamStatus() == com.eeg.entity.EEGSession.StreamStatus.ACTIVE);
            streams.put("filt", filtStream);

            // AvgBandPower流状态
            Map<String, Object> bandStream = new HashMap<>();
            bandStream.put("streamType", "AvgBandPower");
            bandStream.put("description", "频段功率数据流");
            bandStream.put("status", sessionData.getBandStreamStatus());
            bandStream.put("startTime", sessionData.getBandStreamStartTimeUtc());
            bandStream.put("lastPacketTime", sessionData.getBandStreamLastPacketTimeUtc());
            bandStream.put("totalPackets", sessionData.getBandStreamTotalPackets());
            bandStream.put("port", sessionData.getBandPort());
            bandStream.put("isActive", sessionData.getBandStreamStatus() == com.eeg.entity.EEGSession.StreamStatus.ACTIVE);
            streams.put("band", bandStream);

            streamStatus.put("streams", streams);
            streamStatus.put("activeStreamCount", sessionData.getActiveStreamCount());
            streamStatus.put("hasActiveStreams", sessionData.hasActiveStreams());

            return ResponseEntity.ok(createSuccessResponse(Map.of(
                    "hasActiveSession", true,
                    "streamStatus", streamStatus
            )));

        } catch (Exception e) {
            log.error("获取用户 {} 数据流状态时出错", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取数据流状态失败: " + e.getMessage()));
        }
    }

    /**
     * 获取实时数据传输统计
     */
    @GetMapping("/real-time-stats")
    public ResponseEntity<?> getRealTimeStats(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            // 获取连接状态
            EEGDataReceiver.EEGConnectionStatus connectionStatus = eegReceiver.getConnectionStatus(userId);
            if (connectionStatus == null) {
                return ResponseEntity.ok(createSuccessResponse(Map.of(
                        "connected", false,
                        "message", "当前没有活跃连接"
                )));
            }

            // 计算数据传输速率
            long totalPackets = connectionStatus.getRawPacketCount() +
                    connectionStatus.getFiltPacketCount() +
                    connectionStatus.getBandPacketCount();

            long connectionDurationSeconds = 0;
            if (connectionStatus.getConnectionStartTime() != null) {
                connectionDurationSeconds = java.time.Duration.between(
                        connectionStatus.getConnectionStartTime(),
                        LocalDateTime.now(java.time.ZoneOffset.UTC)
                ).getSeconds();
            }

            double packetsPerSecond = connectionDurationSeconds > 0 ?
                    (double) totalPackets / connectionDurationSeconds : 0;

            Map<String, Object> stats = new HashMap<>();
            stats.put("connected", true);
            stats.put("connectionDuration", connectionDurationSeconds);

            Map<String, Object> packetCounts = new HashMap<>();
            packetCounts.put("raw", connectionStatus.getRawPacketCount());
            packetCounts.put("filt", connectionStatus.getFiltPacketCount());
            packetCounts.put("band", connectionStatus.getBandPacketCount());
            packetCounts.put("total", totalPackets);
            stats.put("packetCounts", packetCounts);

            Map<String, Object> transmissionRate = new HashMap<>();
            transmissionRate.put("packetsPerSecond", Math.round(packetsPerSecond * 100.0) / 100.0);
            transmissionRate.put("description", String.format("平均每秒接收 %.2f 个数据包", packetsPerSecond));
            stats.put("transmissionRate", transmissionRate);

            Map<String, Object> lastDataReceived = new HashMap<>();
            lastDataReceived.put("raw", connectionStatus.getLastRawData());
            lastDataReceived.put("filt", connectionStatus.getLastFiltData());
            lastDataReceived.put("band", connectionStatus.getLastBandData());
            stats.put("lastDataReceived", lastDataReceived);

            Map<String, Object> streamConnections = new HashMap<>();
            streamConnections.put("raw", connectionStatus.isRawConnected());
            streamConnections.put("filt", connectionStatus.isFiltConnected());
            streamConnections.put("band", connectionStatus.isBandConnected());
            stats.put("streamConnections", streamConnections);

            return ResponseEntity.ok(createSuccessResponse(stats));

        } catch (Exception e) {
            log.error("获取用户 {} 实时统计时出错", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取实时统计失败: " + e.getMessage()));
        }
    }

    /**
     * 获取端口池基本状态
     */
    @GetMapping("/port-pool-status")
    public ResponseEntity<?> getPortPoolStatus(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            EnhancedPortAllocationService.PortPoolStatistics stats = portService.getPortPoolStatistics();

            Map<String, Object> poolStatus = new HashMap<>();
            poolStatus.put("utilizationRate", stats.getUtilizationRate());
            poolStatus.put("availablePorts", stats.getAvailablePorts());
            poolStatus.put("activeAllocations", stats.getActiveAllocations());
            poolStatus.put("currentUserHasAllocation", portService.hasActivePortAllocation(userId));

            return ResponseEntity.ok(createSuccessResponse(poolStatus));

        } catch (Exception e) {
            log.error("获取端口池状态时出错", e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取端口池状态失败: " + e.getMessage()));
        }
    }

    /**
     * 【调试功能】强制清理用户连接状态
     */
    @PostMapping("/force-cleanup")
    public ResponseEntity<?> forceCleanupUserConnection(@RequestBody(required = false) Map<String, Object> request,
                                                        HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            log.warn("用户 {} 请求强制清理连接状态", userId);

            // 强制停止数据监听
            eegReceiver.stopListeningForUser(userId, "用户请求强制清理");

            // 强制结束会话
            sessionService.forceEndUserSession(userId, "用户强制清理连接状态");

            // 释放端口分配
            portService.releasePortsForUser(userId, "强制清理");

            // 设置用户为非活跃状态
            userService.setUserInactive(userId);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("message", "连接状态已强制清理完成");
            responseData.put("userId", userId);
            responseData.put("cleanupTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (Exception e) {
            log.error("强制清理用户 {} 连接状态时出错", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("强制清理失败: " + e.getMessage()));
        }
    }

    /**
     * 【调试功能】检查缓存一致性
     */
    @GetMapping("/debug/cache-consistency")
    public ResponseEntity<?> debugCacheConsistency(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            // 检查会话缓存一致性
            sessionService.debugCacheConsistency();

            // 获取当前状态信息
            Map<String, Object> debugInfo = new HashMap<>();

            // 数据库中的活跃会话
            Optional<EEGSession> dbSession = sessionService.getActiveSession(userId);
            debugInfo.put("databaseActiveSession", dbSession.orElse(null));

            // 连接状态
            EEGDataReceiver.EEGConnectionStatus connectionStatus = eegReceiver.getConnectionStatus(userId);
            debugInfo.put("connectionStatus", connectionStatus);

            // 端口分配状态
            EnhancedPortAllocationService.PortAllocation portAllocation = portService.getUserPortAllocation(userId);
            debugInfo.put("portAllocation", portAllocation);

            // 系统统计
            EEGDataReceiver.SystemStatistics systemStats = eegReceiver.getSystemStatistics();
            debugInfo.put("systemStats", systemStats);

            return ResponseEntity.ok(createSuccessResponse(debugInfo));

        } catch (Exception e) {
            log.error("调试缓存一致性时出错", e);
            return ResponseEntity.badRequest().body(createErrorResponse("调试失败: " + e.getMessage()));
        }
    }

    /**
     * 【调试功能】查看用户的详细会话状态
     */
    @GetMapping("/debug/user-sessions")
    public ResponseEntity<?> debugUserSessions(@RequestParam(defaultValue = "5") int limit,
                                               HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            List<EEGSession> sessions = sessionService.getUserSessionHistory(userId, limit);

            Map<String, Object> debugInfo = new HashMap<>();
            debugInfo.put("userId", userId);
            debugInfo.put("sessionCount", sessions.size());
            debugInfo.put("sessions", sessions);

            // 统计信息
            long activeSessions = sessions.stream()
                    .mapToLong(s -> s.getSessionStatus() == com.eeg.entity.EEGSession.SessionStatus.ACTIVE ? 1 : 0)
                    .sum();
            debugInfo.put("activeSessionsCount", activeSessions);

            return ResponseEntity.ok(createSuccessResponse(debugInfo));

        } catch (Exception e) {
            log.error("调试用户会话时出错", e);
            return ResponseEntity.badRequest().body(createErrorResponse("调试失败: " + e.getMessage()));
        }
    }

    /**
     * 【调试功能】强制清理系统所有连接
     */
    @PostMapping("/debug/force-cleanup-all")
    public ResponseEntity<?> forceCleanupAllConnections(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            log.warn("用户 {} 请求强制清理所有系统连接", userId);

            // 强制清理所有连接
            eegReceiver.forceCleanupAllConnections();

            // 清理会话缓存
            sessionService.forceClearAllCache();

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("message", "系统所有连接已强制清理完成");
            responseData.put("requestedBy", userId);
            responseData.put("cleanupTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (Exception e) {
            log.error("强制清理所有连接时出错", e);
            return ResponseEntity.badRequest().body(createErrorResponse("强制清理失败: " + e.getMessage()));
        }
    }

    // 辅助方法

    /**
     * 生成唯一的会话ID
     */
    private String generateSessionId() {
        return "EEG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase() +
                "-" + System.currentTimeMillis();
    }

    /**
     * 创建成功响应
     */
    private Map<String, Object> createSuccessResponse(Map<String, Object> data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("timestamp", System.currentTimeMillis());
        response.putAll(data);
        return response;
    }

    /**
     * 创建错误响应
     */
    private Map<String, Object> createErrorResponse(String errorMessage) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", errorMessage);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }

    // 请求数据类
    @Data
    public static class ConnectionRequest {
        private String timezone; // 用户时区，例如 "Asia/Shanghai"
        private String notes;    // 可选的备注信息
    }

    @Data
    public static class DisconnectRequest {
        private String reason;   // 断开连接的原因
    }
}