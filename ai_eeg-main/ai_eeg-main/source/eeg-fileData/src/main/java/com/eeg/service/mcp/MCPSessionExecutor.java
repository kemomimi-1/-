package com.eeg.service.mcp;

import com.eeg.entity.EEGSession;
import com.eeg.service.EEGSessionService;
import com.eeg.service.InfluxDBService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.*;

import java.util.stream.Collectors;

/**
 * MCP 会话管理执行器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MCPSessionExecutor {

    private final EEGSessionService sessionService;
    private final InfluxDBService influxDBService;
    private final ObjectMapper objectMapper;
    private final MCPToolUtils toolUtils;

    /**
     * 执行获取活跃会话上下文
     */
    public Object executeGetActiveSessionContext(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        try {
            log.info("执行getActiveSessionContext - 用户ID: {}", userId);
            Boolean includeDataStats = toolUtils.getBooleanArgument(arguments, "includeDataStats", true);

            Map<String, Object> result = new HashMap<>();
            result.put("userId", userId);
            result.put("queryTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            // 获取活跃会话
            Optional<EEGSession> activeSession = sessionService.getActiveSession(userId);
            if (activeSession.isPresent()) {
                EEGSession session = activeSession.get();
                Map<String, Object> sessionContext = toolUtils.buildSessionContextInfo(session);

                // 添加实时状态信息
                sessionContext.put("isCurrentlyActive", true);
                sessionContext.put("realTimeDuration", session.calculateDurationSeconds());
                sessionContext.put("streamStatuses", Map.of(
                        "rawStream", session.getRawStreamStatus().toString(),
                        "filtStream", session.getFiltStreamStatus().toString(),
                        "bandStream", session.getBandStreamStatus().toString()
                ));

                result.put("hasActiveSession", true);
                result.put("activeSession", sessionContext);

                // 如果需要包含数据统计
                if (includeDataStats) {
                    result.put("realtimeDataStats", toolUtils.generateRealtimeDataStats(userId, session));
                }

            } else {
                result.put("hasActiveSession", false);
                // 获取最新已完成会话作为参考
                Optional<EEGSession> latestSession = sessionService.getUserLatestCompletedSession(userId);
                if (latestSession.isPresent()) {
                    result.put("latestCompletedSession", toolUtils.buildSessionContextInfo(latestSession.get()));
                }
            }

            log.info("getActiveSessionContext完成 - 有活跃会话: {}", activeSession.isPresent());
            return result;

        } catch (Exception e) {
            log.error("获取活跃会话上下文失败 - 用户ID: {}", userId, e);
            return Map.of(
                    "error", "获取活跃会话上下文失败: " + e.getMessage(),
                    "userId", userId
            );
        }
    }

    /**
     * 执行获取用户统计
     */
    public Object executeGetUserStatistics(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        try {
            log.info("执行getUserStatistics - 用户ID: {}", userId);

            EEGSessionService.SessionStatistics stats = sessionService.getUserSessionStatistics(userId);

            Map<String, Object> result = new HashMap<>();
            result.put("userId", userId);
            result.put("statistics", Map.of(
                    "totalSessions", stats.totalSessions,
                    "completedSessions", stats.completedSessions,
                    "activeSessions", stats.activeSessions,
                    "averageDurationSeconds", Math.round(stats.avgDurationSeconds * 100.0) / 100.0,
                    "averageDurationMinutes", Math.round(stats.avgDurationSeconds / 60.0 * 100.0) / 100.0,
                    "totalRawPackets", stats.totalRawPackets,
                    "totalFilteredPackets", stats.totalFiltPackets,
                    "totalBandPowerPackets", stats.totalBandPackets,
                    "totalDataPackets", stats.totalRawPackets + stats.totalFiltPackets + stats.totalBandPackets
            ));

            Map<String, Object> insights = new HashMap<>();
            if (stats.totalSessions > 0) {
                double completionRate = (double) stats.completedSessions / stats.totalSessions * 100.0;
                insights.put("completionRate", Math.round(completionRate * 100.0) / 100.0);
                insights.put("averagePacketsPerSession",
                        Math.round((stats.totalRawPackets + stats.totalFiltPackets + stats.totalBandPackets) / (double) stats.totalSessions));
            } else {
                insights.put("completionRate", 0.0);
                insights.put("averagePacketsPerSession", 0);
            }

            result.put("insights", insights);
            result.put("queryTime", new Date().toString());

            log.info("getUserStatistics完成 - 用户ID: {}, 总会话数: {}", userId, stats.totalSessions);
            return result;

        } catch (Exception e) {
            log.error("获取用户统计失败 - 用户ID: {}", userId, e);
            return Map.of(
                    "error", "获取用户统计失败: " + e.getMessage(),
                    "userId", userId
            );
        }
    }

    /**
     * 执行获取会话详情
     */
    public Object executeGetSessionDetails(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        try {
            Long sessionId = toolUtils.getLongArgument(arguments, "sessionId");

            log.info("执行getSessionDetails - 用户ID: {}, 会话ID: {}", userId, sessionId);

            List<EEGSession> userSessions = sessionService.getUserSessionHistory(userId, 1000);
            Optional<EEGSession> targetSession = userSessions.stream()
                    .filter(session -> session.getId().equals(sessionId))
                    .findFirst();

            if (targetSession.isEmpty()) {
                return Map.of(
                        "error", "会话ID " + sessionId + " 不存在或无权访问",
                        "userId", userId,
                        "sessionId", sessionId
                );
            }

            EEGSession session = targetSession.get();

            Map<String, Object> sessionDetails = new HashMap<>();
            sessionDetails.put("sessionId", session.getId());
            sessionDetails.put("userId", session.getUserId());
            sessionDetails.put("status", session.getSessionStatus().toString());
            sessionDetails.put("userTimezone", session.getUserTimezone());

            sessionDetails.put("startTimeUtc", session.getSessionStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            if (session.getSessionEndTimeUtc() != null) {
                sessionDetails.put("endTimeUtc", session.getSessionEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                sessionDetails.put("isCompleted", true);
            } else {
                sessionDetails.put("endTimeUtc", null);
                sessionDetails.put("isCompleted", false);
            }

            long durationSeconds = session.calculateDurationSeconds();
            sessionDetails.put("durationSeconds", durationSeconds);
            sessionDetails.put("durationMinutes", Math.round(durationSeconds / 60.0 * 100.0) / 100.0);
            sessionDetails.put("durationFormatted", toolUtils.formatDuration(durationSeconds));

            Map<String, Object> dataStreams = new HashMap<>();

            Map<String, Object> rawStream = new HashMap<>();
            rawStream.put("totalPackets", session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0);
            rawStream.put("status", session.getRawStreamStatus() != null ? session.getRawStreamStatus().toString() : "UNKNOWN");
            rawStream.put("hasData", (session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) > 0);
            dataStreams.put("raw", rawStream);

            Map<String, Object> filtStream = new HashMap<>();
            filtStream.put("totalPackets", session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0);
            filtStream.put("status", session.getFiltStreamStatus() != null ? session.getFiltStreamStatus().toString() : "UNKNOWN");
            filtStream.put("hasData", (session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0) > 0);
            dataStreams.put("filtered", filtStream);

            Map<String, Object> bandStream = new HashMap<>();
            bandStream.put("totalPackets", session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0);
            bandStream.put("status", session.getBandStreamStatus() != null ? session.getBandStreamStatus().toString() : "UNKNOWN");
            bandStream.put("hasData", (session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0) > 0);
            dataStreams.put("bandPower", bandStream);

            sessionDetails.put("dataStreams", dataStreams);

            long totalPackets = (session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) +
                    (session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0) +
                    (session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0);
            sessionDetails.put("totalDataPackets", totalPackets);
            sessionDetails.put("hasAnyData", totalPackets > 0);

            sessionDetails.put("queryTime", new Date().toString());

            log.info("getSessionDetails完成 - 会话ID: {}, 持续时间: {}秒", sessionId, durationSeconds);
            return sessionDetails;

        } catch (Exception e) {
            log.error("获取会话详情失败 - 用户ID: {}, 会话ID: {}", userId, arguments.get("sessionId"), e);
            return Map.of(
                    "error", "获取会话详情失败: " + e.getMessage(),
                    "userId", userId,
                    "sessionId", arguments.get("sessionId")
            );
        }
    }

    /**
     * 对比多个会话的数据质量
     */
    public Object executeCompareSessionDataQuality(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        try {
            @SuppressWarnings("unchecked")
            List<Integer> sessionIds = (List<Integer>) arguments.get("sessionIds");
            if (sessionIds == null || sessionIds.size() < 2) {
                return Map.of("error", "需要至少提供2个会话ID进行对比");
            }

            log.info("执行compareSessionDataQuality - 用户ID: {}, 会话IDs: {}", userId, sessionIds);

            List<EEGSession> userSessions = sessionService.getUserSessionHistory(userId, 1000);
            List<EEGSession> targetSessions = userSessions.stream()
                    .filter(session -> sessionIds.contains(session.getId().intValue()))
                    .collect(Collectors.toList());

            if (targetSessions.size() != sessionIds.size()) {
                return Map.of("error", "部分会话ID不存在或无权访问");
            }

            Map<String, Object> result = new HashMap<>();
            result.put("comparisonType", "session_data_quality");
            result.put("comparedSessions", sessionIds);

            List<Map<String, Object>> qualityResults = new ArrayList<>();

            for (EEGSession session : targetSessions) {
                Map<String, Object> sessionQuality = analyzeSessionDataQuality(userId, session);
                qualityResults.add(sessionQuality);
            }

            result.put("qualityAnalysis", qualityResults);
            result.put("comparison", generateQualityComparison(qualityResults));
            result.put("recommendation", generateQualityRecommendation(qualityResults));

            log.info("会话数据质量对比完成 - 对比了{}个会话", targetSessions.size());
            return result;

        } catch (Exception e) {
            log.error("对比会话数据质量失败 - 用户ID: {}", userId, e);
            return Map.of(
                    "error", "对比会话数据质量失败: " + e.getMessage(),
                    "userId", userId
            );
        }
    }

    private Map<String, Object> analyzeSessionDataQuality(Long userId, EEGSession session) {
        Map<String, Object> quality = new HashMap<>();
        quality.put("sessionId", session.getId());
        quality.put("duration", session.calculateDurationSeconds());

        try {
            String startTime = session.getSessionStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");
            String endTime = session.getSessionEndTimeUtc() != null ?
                    session.getSessionEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ") :
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");

            // 分析滤波数据质量
            String qualitySQL = String.format("""
            SELECT 
                COUNT(*) as total_samples,
                COUNT(DISTINCT channel) as active_channels,
                AVG(ABS(value)) as avg_signal_strength,
                STDDEV(value) as signal_stability,
                MIN(value) as min_value,
                MAX(value) as max_value,
                COUNT(CASE WHEN ABS(value) > 200 THEN 1 END) as potential_artifacts
            FROM timeseriesfilt 
            WHERE user_id = '%s' AND time >= '%s' AND time <= '%s'
            """, userId, startTime, endTime);

            String qualityResult = influxDBService.queryData(qualitySQL, "json").block();

            if (qualityResult != null && !qualityResult.trim().isEmpty() && !"[]".equals(qualityResult.trim())) {
                JsonNode qualityNode = objectMapper.readTree(qualityResult);
                if (qualityNode.isArray() && qualityNode.size() > 0) {
                    JsonNode data = qualityNode.get(0);

                    long totalSamples = data.get("total_samples").asLong();
                    int activeChannels = data.get("active_channels").asInt();
                    double signalStability = data.get("signal_stability").asDouble();
                    double avgSignalStrength = data.get("avg_signal_strength").asDouble();
                    long potentialArtifacts = data.get("potential_artifacts").asLong();

                    // 计算质量评分
                    double stabilityScore = Math.max(0, Math.min(100, 100 - signalStability * 5));
                    double channelScore = (activeChannels / 8.0) * 100;
                    double artifactScore = Math.max(0, 100 - (potentialArtifacts / (double)totalSamples) * 1000);

                    double overallQuality = (stabilityScore + channelScore + artifactScore) / 3.0;

                    quality.put("totalSamples", totalSamples);
                    quality.put("activeChannels", activeChannels);
                    quality.put("signalStability", Math.round(signalStability * 1000.0) / 1000.0);
                    quality.put("avgSignalStrength", Math.round(avgSignalStrength * 1000.0) / 1000.0);
                    quality.put("potentialArtifacts", potentialArtifacts);
                    quality.put("overallQualityScore", Math.round(overallQuality * 100.0) / 100.0);
                    quality.put("stabilityLevel", getStabilityLevel(signalStability));
                }
            }

            // 添加数据包统计
            quality.put("dataPackets", Map.of(
                    "raw", session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0,
                    "filtered", session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0,
                    "bandPower", session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0
            ));

        } catch (Exception e) {
            quality.put("analysisError", "数据质量分析失败: " + e.getMessage());
        }

        return quality;
    }

    private String getStabilityLevel(double stability) {
        if (stability < 5) return "非常稳定";
        else if (stability < 10) return "稳定";
        else if (stability < 20) return "一般";
        else if (stability < 40) return "不稳定";
        else return "非常不稳定";
    }

    public Map<String, Object> generateQualityComparison(List<Map<String, Object>> qualityResults) {
        Map<String, Object> comparison = new HashMap<>();

        if (qualityResults.size() >= 2) {
            // 找出最稳定的会话
            Map<String, Object> mostStable = qualityResults.stream()
                    .filter(q -> q.containsKey("overallQualityScore"))
                    .max((q1, q2) -> Double.compare(
                            (Double) q1.get("overallQualityScore"),
                            (Double) q2.get("overallQualityScore")))
                    .orElse(null);

            if (mostStable != null) {
                comparison.put("mostStableSession", mostStable.get("sessionId"));
                comparison.put("highestQualityScore", mostStable.get("overallQualityScore"));
                comparison.put("bestStabilityLevel", mostStable.get("stabilityLevel"));
            }

            // 数据完整性对比
            comparison.put("dataCompletenessComparison", qualityResults.stream()
                    .collect(Collectors.toMap(
                            q -> "session" + q.get("sessionId"),
                            q -> Map.of(
                                    "activeChannels", q.get("activeChannels"),
                                    "totalSamples", q.get("totalSamples")
                            )
                    )));
        }

        return comparison;
    }

    private String generateQualityRecommendation(List<Map<String, Object>> qualityResults) {
        StringBuilder recommendation = new StringBuilder();

        qualityResults.forEach(quality -> {
            Long sessionId = (Long) quality.get("sessionId");
            String stabilityLevel = (String) quality.get("stabilityLevel");
            Integer activeChannels = (Integer) quality.get("activeChannels");

            recommendation.append("会话").append(sessionId).append(": ");

            if ("非常稳定".equals(stabilityLevel) || "稳定".equals(stabilityLevel)) {
                recommendation.append("数据质量优秀，适合进行深度分析。");
            } else if ("一般".equals(stabilityLevel)) {
                recommendation.append("数据质量一般，建议检查采集环境。");
            } else {
                recommendation.append("数据质量较差，建议重新采集或检查设备连接。");
            }

            if (activeChannels < 8) {
                recommendation.append("检测到").append(8 - activeChannels).append("个通道数据缺失。");
            }

            recommendation.append(" ");
        });

        return recommendation.toString();
    }


    /**
     * 根据持续时间等条件筛选会话
     */

    public Object executeQuerySessionsByConditions(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        try {
            log.info("执行querySessionsByConditions - 用户ID: {}", userId);

            Integer minDurationSeconds = toolUtils.getIntegerArgument(arguments, "minDurationSeconds", null);
            Integer maxDurationSeconds = toolUtils.getIntegerArgument(arguments, "maxDurationSeconds", null);
            String status = toolUtils.getStringArgument(arguments, "status", null);
            Integer limit = toolUtils.getIntegerArgument(arguments, "limit", 100);

            List<EEGSession> allSessions = sessionService.getUserSessionHistory(userId, 1000);

            List<EEGSession> filteredSessions = allSessions.stream()
                    .filter(session -> {
                        boolean matches = true;

                        // 按持续时间筛选
                        if (minDurationSeconds != null) {
                            matches = matches && session.calculateDurationSeconds() >= minDurationSeconds;
                        }

                        if (maxDurationSeconds != null) {
                            matches = matches && session.calculateDurationSeconds() <= maxDurationSeconds;
                        }

                        // 按状态筛选
                        if (status != null && !status.isEmpty()) {
                            matches = matches && session.getSessionStatus().toString().equalsIgnoreCase(status);
                        }

                        return matches;
                    })
                    .limit(limit)
                    .collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("queryConditions", Map.of(
                    "minDurationSeconds", minDurationSeconds != null ? minDurationSeconds : "不限制",
                    "maxDurationSeconds", maxDurationSeconds != null ? maxDurationSeconds : "不限制",
                    "status", status != null ? status : "全部状态",
                    "limit", limit
            ));
            result.put("totalFound", filteredSessions.size());

            List<Map<String, Object>> sessionDetails = new ArrayList<>();
            for (EEGSession session : filteredSessions) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("sessionId", session.getId());
                detail.put("startTime", session.getSessionStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                detail.put("endTime", session.getSessionEndTimeUtc() != null ?
                        session.getSessionEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
                detail.put("durationSeconds", session.calculateDurationSeconds());
                detail.put("durationFormatted", toolUtils.formatDuration(session.calculateDurationSeconds()));
                detail.put("status", session.getSessionStatus().toString());
                detail.put("hasData", (session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) > 0);

                // 添加数据传输统计
                long totalPackets = (session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) +
                        (session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0) +
                        (session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0);
                detail.put("totalDataPackets", totalPackets);

                sessionDetails.add(detail);
            }

            result.put("sessions", sessionDetails);

            // 生成特点分析
            if (!filteredSessions.isEmpty()) {
                result.put("characteristicsAnalysis", analyzeSessionCharacteristics(filteredSessions));
            }

            log.info("按条件查询会话完成 - 找到{}个匹配会话", filteredSessions.size());
            return result;

        } catch (Exception e) {
            log.error("按条件查询会话失败 - 用户ID: {}", userId, e);
            return Map.of(
                    "error", "按条件查询会话失败: " + e.getMessage(),
                    "userId", userId
            );
        }
    }

    private Map<String, Object> analyzeSessionCharacteristics(List<EEGSession> sessions) {
        Map<String, Object> analysis = new HashMap<>();

        // 时长统计
        double avgDuration = sessions.stream()
                .mapToLong(EEGSession::calculateDurationSeconds)
                .average()
                .orElse(0.0);

        long maxDuration = sessions.stream()
                .mapToLong(EEGSession::calculateDurationSeconds)
                .max()
                .orElse(0L);

        long minDuration = sessions.stream()
                .mapToLong(EEGSession::calculateDurationSeconds)
                .min()
                .orElse(0L);

        analysis.put("durationAnalysis", Map.of(
                "averageDuration", Math.round(avgDuration * 100.0) / 100.0,
                "maxDuration", maxDuration,
                "minDuration", minDuration,
                "averageDurationFormatted", toolUtils.formatDuration((long)avgDuration),
                "maxDurationFormatted", toolUtils.formatDuration(maxDuration),
                "minDurationFormatted", toolUtils.formatDuration(minDuration)
        ));

        // 数据传输特点
        long totalDataPackets = sessions.stream()
                .mapToLong(session ->
                        (session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) +
                                (session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0) +
                                (session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0))
                .sum();

        analysis.put("dataTransmissionAnalysis", Map.of(
                "totalDataPackets", totalDataPackets,
                "averagePacketsPerSession", sessions.size() > 0 ? totalDataPackets / sessions.size() : 0,
                "sessionsWithData", sessions.stream()
                        .filter(session ->
                                (session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) > 0)
                        .count()
        ));

        // 时间分布分析
        Map<String, Long> timeDistribution = sessions.stream()
                .collect(Collectors.groupingBy(
                        session -> {
                            int hour = session.getSessionStartTimeUtc().getHour();
                            if (hour >= 6 && hour < 12) return "上午";
                            else if (hour >= 12 && hour < 18) return "下午";
                            else if (hour >= 18 && hour < 24) return "晚上";
                            else return "凌晨";
                        },
                        Collectors.counting()
                ));

        analysis.put("timeDistribution", timeDistribution);

        return analysis;
    }

    /**
     * 获取会话的详细技术规格参数
     */

    public Object executeGetSessionTechnicalSpecs(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        try {
            Long sessionId = toolUtils.getLongArgument(arguments, "sessionId");
            Boolean includeDataSamples = toolUtils.getBooleanArgument(arguments, "includeDataSamples", false);

            log.info("执行getSessionTechnicalSpecs - 用户ID: {}, 会话ID: {}", userId, sessionId);

            List<EEGSession> userSessions = sessionService.getUserSessionHistory(userId, 1000);
            Optional<EEGSession> targetSession = userSessions.stream()
                    .filter(session -> session.getId().equals(sessionId))
                    .findFirst();

            if (targetSession.isEmpty()) {
                return Map.of(
                        "error", "会话ID " + sessionId + " 不存在或无权访问",
                        "userId", userId,
                        "sessionId", sessionId
                );
            }

            EEGSession session = targetSession.get();
            Map<String, Object> specs = new HashMap<>();

            // 基础会话信息
            specs.put("sessionId", session.getId());
            specs.put("userId", session.getUserId());
            specs.put("status", session.getSessionStatus().toString());

            // 时间信息
            Map<String, Object> timeInfo = new HashMap<>();
            timeInfo.put("startTimeUtc", session.getSessionStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            timeInfo.put("endTimeUtc", session.getSessionEndTimeUtc() != null ?
                    session.getSessionEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
            timeInfo.put("durationSeconds", session.calculateDurationSeconds());
            timeInfo.put("durationFormatted", toolUtils.formatDuration(session.calculateDurationSeconds()));
            timeInfo.put("userTimezone", session.getUserTimezone());
            specs.put("timeInformation", timeInfo);

            // 技术规格
            Map<String, Object> technicalSpecs = new HashMap<>();
            technicalSpecs.put("dataSource", "OpenBCI GUI v6.0.0 beta1");
            technicalSpecs.put("boardMode", "SYNTHETIC (algorithmic) 8chan");
            technicalSpecs.put("networkingProtocol", "UDP");
            technicalSpecs.put("estimatedSamplingRate", "~250Hz");
            technicalSpecs.put("channelCount", 8);
            technicalSpecs.put("channelMapping", Map.of(
                    "channel1", "Fp1 (左前额)",
                    "channel2", "Fp2 (右前额)",
                    "channel3", "C3 (左中央)",
                    "channel4", "C4 (右中央)",
                    "channel5", "P7 (左顶叶)",
                    "channel6", "P8 (右顶叶)",
                    "channel7", "O1 (左枕叶)",
                    "channel8", "O2 (右枕叶)"
            ));
            technicalSpecs.put("dataUnit", "微伏特 (μV)");
            specs.put("technicalSpecifications", technicalSpecs);

            // 数据流配置
            Map<String, Object> dataStreamConfig = new HashMap<>();

            Map<String, Object> rawStream = new HashMap<>();
            rawStream.put("dataType", "TimeSeriesRaw");
            rawStream.put("description", "未处理的原始EEG信号");
            rawStream.put("port", session.getRawPort());
            rawStream.put("status", session.getRawStreamStatus() != null ? session.getRawStreamStatus().toString() : "UNKNOWN");
            rawStream.put("totalPackets", session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0);
            rawStream.put("startTime", session.getRawStreamStartTimeUtc() != null ?
                    session.getRawStreamStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
            rawStream.put("endTime", session.getRawStreamEndTimeUtc() != null ?
                    session.getRawStreamEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);

            Map<String, Object> filtStream = new HashMap<>();
            filtStream.put("dataType", "TimeSeriesFilt");
            filtStream.put("description", "经过滤波处理的EEG信号");
            filtStream.put("port", session.getFiltPort());
            filtStream.put("status", session.getFiltStreamStatus() != null ? session.getFiltStreamStatus().toString() : "UNKNOWN");
            filtStream.put("totalPackets", session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0);
            filtStream.put("startTime", session.getFiltStreamStartTimeUtc() != null ?
                    session.getFiltStreamStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
            filtStream.put("endTime", session.getFiltStreamEndTimeUtc() != null ?
                    session.getFiltStreamEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);

            Map<String, Object> bandStream = new HashMap<>();
            bandStream.put("dataType", "AvgBandPower");
            bandStream.put("description", "平均频段功率数据");
            bandStream.put("port", session.getBandPort());
            bandStream.put("status", session.getBandStreamStatus() != null ? session.getBandStreamStatus().toString() : "UNKNOWN");
            bandStream.put("totalPackets", session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0);
            bandStream.put("startTime", session.getBandStreamStartTimeUtc() != null ?
                    session.getBandStreamStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
            bandStream.put("endTime", session.getBandStreamEndTimeUtc() != null ?
                    session.getBandStreamEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
            bandStream.put("frequencyBands", Map.of(
                    "delta", "1-4Hz (深度睡眠)",
                    "theta", "4-8Hz (冥想、创造性思维)",
                    "alpha", "8-13Hz (放松清醒)",
                    "beta", "13-30Hz (专注思考)",
                    "gamma", "30-100Hz (高级认知功能)"
            ));

            dataStreamConfig.put("rawStream", rawStream);
            dataStreamConfig.put("filteredStream", filtStream);
            dataStreamConfig.put("bandPowerStream", bandStream);
            specs.put("dataStreamConfiguration", dataStreamConfig);

            // 如果需要包含数据样本
            if (includeDataSamples) {
                specs.put("dataSamples", getSessionDataSamples(userId, session));
            }

            // 数据质量摘要
            specs.put("dataQualitySummary", generateDataQualitySummary(session));

            log.info("获取会话技术规格完成 - 会话ID: {}", sessionId);
            return specs;

        } catch (Exception e) {
            log.error("获取会话技术规格失败 - 用户ID: {}, 会话ID: {}", userId, arguments.get("sessionId"), e);
            return Map.of(
                    "error", "获取会话技术规格失败: " + e.getMessage(),
                    "userId", userId,
                    "sessionId", arguments.get("sessionId")
            );
        }
    }

    private Map<String, Object> getSessionDataSamples(Long userId, EEGSession session) {
        Map<String, Object> samples = new HashMap<>();

        try {
            String startTime = session.getSessionStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");
            String endTime = session.getSessionEndTimeUtc() != null ?
                    session.getSessionEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ") :
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");

            // 获取原始数据样本
            String rawSampleSQL = String.format("""
            SELECT time, channel, value 
            FROM timeseriesraw 
            WHERE user_id = '%s' AND time >= '%s' AND time <= '%s' 
            ORDER BY time DESC 
            LIMIT 10
            """, userId, startTime, endTime);

            String rawSample = influxDBService.queryData(rawSampleSQL, "json").block();
            samples.put("rawDataSample", rawSample);

            // 获取频段数据样本
            String bandSampleSQL = String.format("""
            SELECT time, band, value 
            FROM avg_band_power 
            WHERE user_id = '%s' AND time >= '%s' AND time <= '%s' 
            ORDER BY time DESC 
            LIMIT 10
            """, userId, startTime, endTime);

            String bandSample = influxDBService.queryData(bandSampleSQL, "json").block();
            samples.put("bandPowerSample", bandSample);

        } catch (Exception e) {
            samples.put("error", "获取数据样本失败: " + e.getMessage());
        }

        return samples;
    }

    public Map<String, Object> generateDataQualitySummary(EEGSession session) {
        Map<String, Object> summary = new HashMap<>();

        long totalPackets = (session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) +
                (session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0) +
                (session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0);

        summary.put("totalDataPackets", totalPackets);
        summary.put("hasCompleteData", totalPackets > 0);

        int activeStreams = 0;
        if (session.getRawStreamStatus() == EEGSession.StreamStatus.COMPLETED) activeStreams++;
        if (session.getFiltStreamStatus() == EEGSession.StreamStatus.COMPLETED) activeStreams++;
        if (session.getBandStreamStatus() == EEGSession.StreamStatus.COMPLETED) activeStreams++;

        summary.put("activeStreams", activeStreams);
        summary.put("allStreamsComplete", activeStreams == 3);

        if (session.calculateDurationSeconds() > 0) {
            double packetsPerSecond = totalPackets / (double) session.calculateDurationSeconds();
            summary.put("averagePacketsPerSecond", Math.round(packetsPerSecond * 100.0) / 100.0);
        }

        return summary;
    }

    /**
     * 获取用户会话历史记录
     */

    public Object executeGetSessionHistory(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        try {
            log.info("执行getSessionHistory - 用户ID: {}", userId);

            Integer limit = toolUtils.getIntegerArgument(arguments, "limit", 20);
            String sortBy = toolUtils.getStringArgument(arguments, "sortBy", "startTime");
            String sortOrder = toolUtils.getStringArgument(arguments, "sortOrder", "DESC");
            Boolean includeStatistics = toolUtils.getBooleanArgument(arguments, "includeStatistics", true);

            List<EEGSession> sessions = sessionService.getUserSessionHistory(userId, Math.min(limit, 1000));

            // 排序
            if ("duration".equals(sortBy)) {
                sessions.sort((s1, s2) -> {
                    int result = Long.compare(s1.calculateDurationSeconds(), s2.calculateDurationSeconds());
                    return "ASC".equals(sortOrder) ? result : -result;
                });
            } else if ("startTime".equals(sortBy)) {
                sessions.sort((s1, s2) -> {
                    int result = s1.getSessionStartTimeUtc().compareTo(s2.getSessionStartTimeUtc());
                    return "ASC".equals(sortOrder) ? result : -result;
                });
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("queryParams", Map.of(
                    "limit", limit,
                    "sortBy", sortBy,
                    "sortOrder", sortOrder
            ));
            result.put("totalSessions", sessions.size());

            List<Map<String, Object>> sessionList = new ArrayList<>();
            for (EEGSession session : sessions) {
                Map<String, Object> sessionInfo = new HashMap<>();
                sessionInfo.put("sessionId", session.getId());
                sessionInfo.put("startTime", session.getSessionStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                sessionInfo.put("endTime", session.getSessionEndTimeUtc() != null ?
                        session.getSessionEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
                sessionInfo.put("duration", session.calculateDurationSeconds());
                sessionInfo.put("durationFormatted", toolUtils.formatDuration(session.calculateDurationSeconds()));
                sessionInfo.put("status", session.getSessionStatus().toString());

                long totalPackets = (session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) +
                        (session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0) +
                        (session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0);
                sessionInfo.put("totalDataPackets", totalPackets);
                sessionInfo.put("hasData", totalPackets > 0);

                sessionList.add(sessionInfo);
            }

            result.put("sessions", sessionList);

            // 添加统计信息
            if (includeStatistics) {
                result.put("statistics", generateSessionHistoryStatistics(sessions));
            }

            log.info("获取会话历史完成 - 返回{}个会话", sessions.size());
            return result;

        } catch (Exception e) {
            log.error("获取会话历史失败 - 用户ID: {}", userId, e);
            return Map.of(
                    "error", "获取会话历史失败: " + e.getMessage(),
                    "userId", userId
            );
        }
    }

    public Map<String, Object> generateSessionHistoryStatistics(List<EEGSession> sessions) {
        if (sessions.isEmpty()) {
            return Map.of("message", "无会话数据");
        }

        Map<String, Object> stats = new HashMap<>();

        // 基础统计
        long totalDuration = sessions.stream()
                .mapToLong(EEGSession::calculateDurationSeconds)
                .sum();

        double avgDuration = sessions.stream()
                .mapToLong(EEGSession::calculateDurationSeconds)
                .average()
                .orElse(0.0);

        stats.put("totalSessions", sessions.size());
        stats.put("totalDurationSeconds", totalDuration);
        stats.put("totalDurationFormatted", toolUtils.formatDuration(totalDuration));
        stats.put("averageDurationSeconds", Math.round(avgDuration * 100.0) / 100.0);
        stats.put("averageDurationFormatted", toolUtils.formatDuration((long)avgDuration));

        // 状态分布
        Map<String, Long> statusDistribution = sessions.stream()
                .collect(Collectors.groupingBy(
                        session -> session.getSessionStatus().toString(),
                        Collectors.counting()
                ));
        stats.put("statusDistribution", statusDistribution);

        // 数据量统计
        long totalDataPackets = sessions.stream()
                .mapToLong(session ->
                        (session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) +
                                (session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0) +
                                (session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0))
                .sum();

        stats.put("totalDataPackets", totalDataPackets);
        stats.put("averagePacketsPerSession", sessions.size() > 0 ? totalDataPackets / sessions.size() : 0);

        // 时间范围
        if (!sessions.isEmpty()) {
            LocalDateTime earliest = sessions.stream()
                    .map(EEGSession::getSessionStartTimeUtc)
                    .min(LocalDateTime::compareTo)
                    .orElse(null);

            LocalDateTime latest = sessions.stream()
                    .map(EEGSession::getSessionStartTimeUtc)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);

            stats.put("timeRange", Map.of(
                    "earliest", earliest != null ? earliest.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null,
                    "latest", latest != null ? latest.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null
            ));
        }

        return stats;
    }

}
