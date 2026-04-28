
package com.eeg.controller;

import com.eeg.entity.EEGSession;
import com.eeg.service.EEGSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionManagementController {

    private final EEGSessionService sessionService;

    /**
     * 获取用户当前活跃会话
     */
    @GetMapping("/active")
    public ResponseEntity<Object> getActiveSession(HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        Optional<EEGSession> activeSession = sessionService.getActiveSession(userId);
        if (activeSession.isPresent()) {
            return ResponseEntity.ok(createSuccessResponse(Map.of(
                    "hasActiveSession", true,
                    "session", formatSessionForResponse(activeSession.get())
            )));
        } else {
            return ResponseEntity.ok(createSuccessResponse(Map.of(
                    "hasActiveSession", false,
                    "message", "当前没有活跃的数据传输会话"
            )));
        }
    }

    /**
     * 手动结束当前活跃会话
     */
    @PostMapping("/end")
    public ResponseEntity<Object> endActiveSession(@RequestBody(required = false) EndSessionRequest request,
                                                   HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            String reason = (request != null && request.getReason() != null) ?
                    request.getReason() : "用户手动结束会话";

            sessionService.forceEndUserSession(userId, reason);

            return ResponseEntity.ok(createSuccessResponse(Map.of(
                    "message", "会话已成功结束",
                    "reason", reason
            )));
        } catch (Exception e) {
            log.error("结束用户 {} 的会话时出错", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("结束会话失败: " + e.getMessage()));
        }
    }

    /**
     * 获取用户会话历史
     */
    @GetMapping("/history")
    public ResponseEntity<Object> getSessionHistory(@RequestParam(defaultValue = "10") int limit,
                                                    HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            List<EEGSession> sessions = sessionService.getUserSessionHistory(userId, Math.min(limit, 100));

            // 格式化会话数据
            List<Map<String, Object>> formattedSessions = sessions.stream()
                    .map(this::formatSessionForResponse)
                    .toList();

            return ResponseEntity.ok(createSuccessResponse(Map.of(
                    "sessions", formattedSessions,
                    "count", formattedSessions.size(),
                    "limit", limit
            )));
        } catch (Exception e) {
            log.error("获取用户 {} 会话历史时出错", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取会话历史失败: " + e.getMessage()));
        }
    }

    /**
     * 获取用户最新完成的会话
     */
    @GetMapping("/latest-completed")
    public ResponseEntity<Object> getLatestCompletedSession(HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            Optional<EEGSession> latestSession = sessionService.getUserLatestCompletedSession(userId);

            if (latestSession.isPresent()) {
                return ResponseEntity.ok(createSuccessResponse(Map.of(
                        "hasLatestSession", true,
                        "session", formatSessionForResponse(latestSession.get())
                )));
            } else {
                return ResponseEntity.ok(createSuccessResponse(Map.of(
                        "hasLatestSession", false,
                        "message", "没有找到已完成的会话"
                )));
            }
        } catch (Exception e) {
            log.error("获取用户 {} 最新完成会话时出错", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取最新会话失败: " + e.getMessage()));
        }
    }

    /**
     * 根据时间范围查询会话
     */
    @GetMapping("/by-time-range")
    public ResponseEntity<Object> getSessionsByTimeRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) String timezone,
            HttpSession httpSession) {

        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            // 将用户本地时间转换为UTC时间进行查询
            LocalDateTime startTimeUtc = timezone != null ?
                    sessionService.convertUserTimeToUtc(startTime, timezone) : startTime;
            LocalDateTime endTimeUtc = timezone != null ?
                    sessionService.convertUserTimeToUtc(endTime, timezone) : endTime;

            List<EEGSession> sessions = sessionService.getUserSessionsInTimeRange(userId, startTimeUtc, endTimeUtc);

            // 格式化会话数据
            List<Map<String, Object>> formattedSessions = sessions.stream()
                    .map(this::formatSessionForResponse)
                    .toList();

            return ResponseEntity.ok(createSuccessResponse(Map.of(
                    "sessions", formattedSessions,
                    "count", formattedSessions.size(),
                    "queryParams", Map.of(
                            "startTime", startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            "endTime", endTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            "timezone", timezone != null ? timezone : "UTC"
                    )
            )));
        } catch (Exception e) {
            log.error("按时间范围查询用户 {} 会话时出错", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("查询会话失败: " + e.getMessage()));
        }
    }

    /**
     * 查询指定时间点的活跃会话
     */
    @GetMapping("/at-time")
    public ResponseEntity<Object> getSessionAtTime(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timePoint,
            @RequestParam(required = false) String timezone,
            HttpSession httpSession) {

        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            // 将用户本地时间转换为UTC时间
            LocalDateTime timePointUtc = timezone != null ?
                    sessionService.convertUserTimeToUtc(timePoint, timezone) : timePoint;

            Optional<EEGSession> session = sessionService.getUserSessionAtTime(userId, timePointUtc);

            if (session.isPresent()) {
                return ResponseEntity.ok(createSuccessResponse(Map.of(
                        "hasSession", true,
                        "session", formatSessionForResponse(session.get()),
                        "queryTime", timePoint.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        "timezone", timezone != null ? timezone : "UTC"
                )));
            } else {
                return ResponseEntity.ok(createSuccessResponse(Map.of(
                        "hasSession", false,
                        "message", "在指定时间点没有找到活跃会话",
                        "queryTime", timePoint.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                )));
            }
        } catch (Exception e) {
            log.error("查询用户 {} 指定时间点会话时出错", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("查询会话失败: " + e.getMessage()));
        }
    }

    /**
     * 获取会话详细信息 - 修复空指针异常
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<Object> getSessionDetails(@PathVariable Long sessionId,
                                                    HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            // 权限检查：确保用户只能查看自己的会话
            List<EEGSession> userSessions = sessionService.getUserSessionHistory(userId, 1000);
            Optional<EEGSession> targetSession = userSessions.stream()
                    .filter(session -> session.getId().equals(sessionId))
                    .findFirst();

            if (targetSession.isPresent()) {
                EEGSession session = targetSession.get();

                // 构建详细信息 - 安全处理可能的null值
                Map<String, Object> sessionDetails = new HashMap<>();
                sessionDetails.put("session", formatSessionForResponse(session));

                // 安全构建数据流摘要
                Map<String, Object> streamsSummary = buildStreamsSummary(session);
                sessionDetails.put("streamsSummary", streamsSummary);

                return ResponseEntity.ok(createSuccessResponse(sessionDetails));
            } else {
                return ResponseEntity.status(404).body(createErrorResponse("会话不存在或无权访问"));
            }
        } catch (Exception e) {
            log.error("获取会话 {} 详情时出错", sessionId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取会话详情失败: " + e.getMessage()));
        }
    }

    /**
     * 获取用户会话统计信息
     */
    @GetMapping("/statistics")
    public ResponseEntity<Object> getSessionStatistics(HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            EEGSessionService.SessionStatistics stats = sessionService.getUserSessionStatistics(userId);

            return ResponseEntity.ok(createSuccessResponse(Map.of(
                    "statistics", Map.of(
                            "totalSessions", stats.totalSessions,
                            "completedSessions", stats.completedSessions,
                            "activeSessions", stats.activeSessions,
                            "avgDurationSeconds", stats.avgDurationSeconds,
                            "totalRawPackets", stats.totalRawPackets,
                            "totalFiltPackets", stats.totalFiltPackets,
                            "totalBandPackets", stats.totalBandPackets
                    ),
                    "generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )));
        } catch (Exception e) {
            log.error("获取用户 {} 会话统计时出错", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取统计信息失败: " + e.getMessage()));
        }
    }

    /**
     * 为AI查询优化的会话数据接口
     */
    @GetMapping("/for-ai-analysis")
    public ResponseEntity<Object> getSessionsForAIAnalysis(
            @RequestParam(defaultValue = "latest") String analysisType,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) String timezone,
            HttpSession httpSession) {

        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            List<EEGSession> sessions;
            Map<String, Object> analysisContext = new HashMap<>();

            switch (analysisType.toLowerCase()) {
                case "latest":
                    Optional<EEGSession> latestSession = sessionService.getUserLatestCompletedSession(userId);
                    sessions = latestSession.map(List::of).orElse(List.of());
                    analysisContext.put("analysisType", "latest");
                    analysisContext.put("description", "最新完成的数据传输会话");
                    break;

                case "recent":
                    int recentLimit = limit != null ? Math.min(limit, 20) : 5;
                    sessions = sessionService.getUserSessionHistory(userId, recentLimit);
                    analysisContext.put("analysisType", "recent");
                    analysisContext.put("description", "最近的" + recentLimit + "个会话");
                    analysisContext.put("limit", recentLimit);
                    break;

                case "timerange":
                    if (startTime == null || endTime == null) {
                        return ResponseEntity.badRequest().body(createErrorResponse("时间范围分析需要指定开始和结束时间"));
                    }

                    LocalDateTime startTimeUtc = timezone != null ?
                            sessionService.convertUserTimeToUtc(startTime, timezone) : startTime;
                    LocalDateTime endTimeUtc = timezone != null ?
                            sessionService.convertUserTimeToUtc(endTime, timezone) : endTime;

                    sessions = sessionService.getUserSessionsInTimeRange(userId, startTimeUtc, endTimeUtc);
                    analysisContext.put("analysisType", "timerange");
                    analysisContext.put("description", "指定时间范围内的会话");
                    analysisContext.put("startTime", startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    analysisContext.put("endTime", endTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    analysisContext.put("timezone", timezone != null ? timezone : "UTC");
                    break;

                default:
                    return ResponseEntity.badRequest().body(createErrorResponse("不支持的分析类型: " + analysisType));
            }

            // 为每个会话构建AI分析友好的数据格式
            List<Map<String, Object>> aiOptimizedSessions = sessions.stream()
                    .map(this::buildAIOptimizedSessionData)
                    .toList();

            return ResponseEntity.ok(createSuccessResponse(Map.of(
                    "analysisContext", analysisContext,
                    "sessions", aiOptimizedSessions,
                    "sessionCount", aiOptimizedSessions.size(),
                    "queryTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )));

        } catch (Exception e) {
            log.error("为AI分析获取用户 {} 会话数据时出错", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取AI分析数据失败: " + e.getMessage()));
        }
    }

    /**
     * 格式化会话数据供前端使用 - 统一时区处理
     */
    private Map<String, Object> formatSessionForResponse(EEGSession session) {
        Map<String, Object> formattedSession = new HashMap<>();

        // 基础信息
        formattedSession.put("id", session.getId());
        formattedSession.put("userId", session.getUserId());
        formattedSession.put("sessionStatus", session.getSessionStatus());
        formattedSession.put("userTimezone", session.getUserTimezone());

        // UTC时间（原始时间）
        formattedSession.put("sessionStartTimeUtc", formatDateTimeOrNull(session.getSessionStartTimeUtc()));
        formattedSession.put("sessionEndTimeUtc", formatDateTimeOrNull(session.getSessionEndTimeUtc()));

        // 用户本地时间（用于显示）
        formattedSession.put("sessionStartTimeLocal", formatDateTimeOrNull(session.getSessionStartTime()));
        formattedSession.put("sessionEndTimeLocal", formatDateTimeOrNull(session.getSessionEndTime()));

        // 会话时长
        formattedSession.put("durationSeconds", session.calculateDurationSeconds());
        formattedSession.put("totalDurationSeconds", session.getTotalDurationSeconds());

        // 端口信息
        formattedSession.put("rawPort", session.getRawPort());
        formattedSession.put("filtPort", session.getFiltPort());
        formattedSession.put("bandPort", session.getBandPort());

        // 数据流状态
        formattedSession.put("rawStreamStatus", session.getRawStreamStatus());
        formattedSession.put("filtStreamStatus", session.getFiltStreamStatus());
        formattedSession.put("bandStreamStatus", session.getBandStreamStatus());

        // 数据包计数
        formattedSession.put("rawStreamTotalPackets", session.getRawStreamTotalPackets());
        formattedSession.put("filtStreamTotalPackets", session.getFiltStreamTotalPackets());
        formattedSession.put("bandStreamTotalPackets", session.getBandStreamTotalPackets());

        // 最后数据包时间（UTC）
        formattedSession.put("rawStreamLastPacketTimeUtc", formatDateTimeOrNull(session.getRawStreamLastPacketTimeUtc()));
        formattedSession.put("filtStreamLastPacketTimeUtc", formatDateTimeOrNull(session.getFiltStreamLastPacketTimeUtc()));
        formattedSession.put("bandStreamLastPacketTimeUtc", formatDateTimeOrNull(session.getBandStreamLastPacketTimeUtc()));

        // 备注
        formattedSession.put("notes", session.getNotes());

        return formattedSession;
    }

    /**
     * 安全构建数据流摘要 - 防止空指针异常
     */
    private Map<String, Object> buildStreamsSummary(EEGSession session) {
        Map<String, Object> streamsSummary = new HashMap<>();

        // Raw流信息
        Map<String, Object> rawStream = new HashMap<>();
        rawStream.put("status", session.getRawStreamStatus());
        rawStream.put("startTime", formatDateTimeOrNull(session.getRawStreamStartTimeUtc()));
        rawStream.put("endTime", formatDateTimeOrNull(session.getRawStreamEndTimeUtc()));
        rawStream.put("totalPackets", session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0);
        rawStream.put("port", session.getRawPort());
        streamsSummary.put("raw", rawStream);

        // Filt流信息
        Map<String, Object> filtStream = new HashMap<>();
        filtStream.put("status", session.getFiltStreamStatus());
        filtStream.put("startTime", formatDateTimeOrNull(session.getFiltStreamStartTimeUtc()));
        filtStream.put("endTime", formatDateTimeOrNull(session.getFiltStreamEndTimeUtc()));
        filtStream.put("totalPackets", session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0);
        filtStream.put("port", session.getFiltPort());
        streamsSummary.put("filt", filtStream);

        // Band流信息
        Map<String, Object> bandStream = new HashMap<>();
        bandStream.put("status", session.getBandStreamStatus());
        bandStream.put("startTime", formatDateTimeOrNull(session.getBandStreamStartTimeUtc()));
        bandStream.put("endTime", formatDateTimeOrNull(session.getBandStreamEndTimeUtc()));
        bandStream.put("totalPackets", session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0);
        bandStream.put("port", session.getBandPort());
        streamsSummary.put("band", bandStream);

        return streamsSummary;
    }

    /**
     * 构建AI分析友好的会话数据格式
     */
    private Map<String, Object> buildAIOptimizedSessionData(EEGSession session) {
        Map<String, Object> aiData = new HashMap<>();

        // 基础信息
        aiData.put("sessionId", session.getId());
        aiData.put("sessionStartTimeUtc", formatDateTimeOrNull(session.getSessionStartTimeUtc()));
        aiData.put("sessionEndTimeUtc", formatDateTimeOrNull(session.getSessionEndTimeUtc()));
        aiData.put("durationSeconds", session.calculateDurationSeconds());
        aiData.put("status", session.getSessionStatus());
        aiData.put("userTimezone", session.getUserTimezone());

        // 数据流信息
        Map<String, Object> dataStreams = new HashMap<>();

        // 原始数据流
        dataStreams.put("raw", Map.of(
                "status", session.getRawStreamStatus(),
                "startTimeUtc", formatDateTimeOrNull(session.getRawStreamStartTimeUtc()),
                "endTimeUtc", formatDateTimeOrNull(session.getRawStreamEndTimeUtc()),
                "totalPackets", session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0,
                "hasData", (session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) > 0
        ));

        // 滤波数据流
        dataStreams.put("filtered", Map.of(
                "status", session.getFiltStreamStatus(),
                "startTimeUtc", formatDateTimeOrNull(session.getFiltStreamStartTimeUtc()),
                "endTimeUtc", formatDateTimeOrNull(session.getFiltStreamEndTimeUtc()),
                "totalPackets", session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0,
                "hasData", (session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0) > 0
        ));

        // 频谱数据流
        dataStreams.put("bandPower", Map.of(
                "status", session.getBandStreamStatus(),
                "startTimeUtc", formatDateTimeOrNull(session.getBandStreamStartTimeUtc()),
                "endTimeUtc", formatDateTimeOrNull(session.getBandStreamEndTimeUtc()),
                "totalPackets", session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0,
                "hasData", (session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0) > 0
        ));

        aiData.put("dataStreams", dataStreams);

        // 分析提示
        List<String> analysisHints = new ArrayList<>();
        if ((session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) > 0) {
            analysisHints.add("包含原始EEG数据，适合时域分析");
        }
        if ((session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0) > 0) {
            analysisHints.add("包含滤波EEG数据，适合降噪分析");
        }
        if ((session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0) > 0) {
            analysisHints.add("包含频谱功率数据，适合频域分析");
        }

        // 检查数据完整性
        long totalPackets = (session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) +
                (session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0) +
                (session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0);

        analysisHints.add(totalPackets > 0 ? "数据完整可用于分析" : "数据不完整，分析结果可能受限");

        aiData.put("analysisHints", analysisHints);

        return aiData;
    }

    /**
     * 安全格式化日期时间 - 防止空指针异常
     */
    private String formatDateTimeOrNull(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        try {
            return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.warn("日期格式化失败: {}", dateTime, e);
            return dateTime.toString();
        }
    }

    // 请求数据类
    @Data
    public static class EndSessionRequest {
        private String reason;
    }

    // 辅助方法：创建成功响应
    private Object createSuccessResponse(Map<String, Object> data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("timestamp", System.currentTimeMillis());
        response.putAll(data);
        return response;
    }

    // 辅助方法：创建错误响应
    private Object createErrorResponse(String errorMessage) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", errorMessage);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
}