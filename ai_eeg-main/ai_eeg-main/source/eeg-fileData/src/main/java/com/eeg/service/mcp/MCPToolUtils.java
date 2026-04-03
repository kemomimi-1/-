package com.eeg.service.mcp;

import com.eeg.entity.EEGSession;
import com.eeg.service.EEGSessionService;
import com.eeg.service.InfluxDBService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.eeg.service.mcp.MCPToolModels.*;

/**
 * MCP 工具共享辅助方法
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MCPToolUtils {

    private final EEGSessionService sessionService;
    private final InfluxDBService influxDBService;
    private final ObjectMapper objectMapper;

    // SQL安全检查模式
    private static final List<String> ALLOWED_TABLES = List.of("timeseriesraw", "timeseriesfilt", "avg_band_power");
    private static final Pattern DANGEROUS_PATTERNS = Pattern.compile(
            "(?i)(drop|delete|insert|update|create|alter|truncate|exec|execute|script|;.*drop|;.*delete)",
            Pattern.CASE_INSENSITIVE
    );

    // ========== 辅助方法 ==========

    /**
     * 获取目标会话（活跃会话或指定会话）
     */
    public EEGSession getTargetSession(Long userId, Map<String, Object> arguments) {
        Object sessionIdObj = arguments.get("sessionId");
        if (sessionIdObj != null) {
            Long sessionId = sessionIdObj instanceof Number ?
                    ((Number) sessionIdObj).longValue() :
                    Long.parseLong(sessionIdObj.toString());

            List<EEGSession> userSessions = sessionService.getUserSessionHistory(userId, 1000);
            return userSessions.stream()
                    .filter(s -> s.getId().equals(sessionId))
                    .findFirst()
                    .orElse(null);
        } else {
            return sessionService.getActiveSession(userId).orElse(null);
        }
    }

    /**
     * 生成实时数据统计
     */
    public Map<String, Object> generateRealtimeDataStats(Long userId, EEGSession session) {
        try {
            String startTime = session.getSessionStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");
            String endTime = session.getSessionEndTimeUtc() != null ?
                    session.getSessionEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ") :
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");

            String statsSQL = String.format("""
                SELECT 
                    'raw' as data_type, COUNT(*) as count, 
                    MIN(time) as first_record, MAX(time) as last_record
                FROM timeseriesraw WHERE user_id = '%s' AND time >= '%s' AND time <= '%s'
                UNION ALL
                SELECT 
                    'filtered' as data_type, COUNT(*) as count,
                    MIN(time) as first_record, MAX(time) as last_record  
                FROM timeseriesfilt WHERE user_id = '%s' AND time >= '%s' AND time <= '%s'
                UNION ALL
                SELECT 
                    'band_power' as data_type, COUNT(*) as count,
                    MIN(time) as first_record, MAX(time) as last_record
                FROM avg_band_power WHERE user_id = '%s' AND time >= '%s' AND time <= '%s'
                """, userId, startTime, endTime, userId, startTime, endTime, userId, startTime, endTime);

            String result = influxDBService.queryData(statsSQL, "json").block();
            return Map.of("realtimeStats", result);

        } catch (Exception e) {
            log.warn("生成实时数据统计失败", e);
            return Map.of("error", "无法获取实时统计");
        }
    }


    /**
     * 生成数据处理建议
     */
    public Map<String, Object> generateDataProcessingRecommendations(String rawResult, String bandResult) {
        Map<String, Object> recommendations = new HashMap<>();

        try {
            JsonNode rawNode = objectMapper.readTree(rawResult);
            JsonNode bandNode = objectMapper.readTree(bandResult);

            if (rawNode.isArray() && rawNode.size() > 0) {
                JsonNode rawData = rawNode.get(0);
                long rawCount = rawData.get("record_count").asLong();

                if (rawCount > 50000) {
                    recommendations.put("processingStrategy", "AGGRESSIVE_SAMPLING");
                    recommendations.put("recommendation", "数据量较大，建议使用积极采样策略");
                    recommendations.put("suggestedSampleSize", 5000);
                } else if (rawCount > 10000) {
                    recommendations.put("processingStrategy", "MODERATE_SAMPLING");
                    recommendations.put("recommendation", "数据量适中，建议使用中等采样策略");
                    recommendations.put("suggestedSampleSize", 10000);
                } else {
                    recommendations.put("processingStrategy", "FULL_DATA");
                    recommendations.put("recommendation", "数据量较小，可以使用全数据处理");
                }

                recommendations.put("estimatedRawRecords", rawCount);
            }

            if (bandNode.isArray() && bandNode.size() > 0) {
                JsonNode bandData = bandNode.get(0);
                long bandCount = bandData.get("record_count").asLong();
                recommendations.put("estimatedBandRecords", bandCount);
            }

        } catch (Exception e) {
            recommendations.put("error", "生成建议时出错: " + e.getMessage());
        }

        return recommendations;
    }

    // 修复 parseTimeRange 方法
    public TimeRange parseTimeRange(Long userId, Map<String, Object> arguments) {
        TimeRange timeRange = new TimeRange();

        try {
            Object sessionIdObj = arguments.get("sessionId");
            if (sessionIdObj != null) {
                Long sessionId = sessionIdObj instanceof Number ?
                        ((Number) sessionIdObj).longValue() :
                        Long.parseLong(sessionIdObj.toString());

                List<EEGSession> userSessions = sessionService.getUserSessionHistory(userId, 1000);
                Optional<EEGSession> session = userSessions.stream()
                        .filter(s -> s.getId().equals(sessionId))
                        .findFirst();

                if (session.isPresent()) {
                    EEGSession s = session.get();
                    // 确保使用UTC时间字段
                    timeRange.startTime = s.getSessionStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");
                    timeRange.endTime = s.getSessionEndTimeUtc() != null ?
                            s.getSessionEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ") :
                            getCurrentUtcTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " "); // 修复：使用UTC时间
                    timeRange.sessionId = sessionId;

                    log.info("使用指定会话ID {} 的UTC时间范围: {} - {}", sessionId, timeRange.startTime, timeRange.endTime);
                    return timeRange;
                } else {
                    timeRange.hasError = true;
                    timeRange.errorMessage = "会话ID " + sessionId + " 不存在或无权访问";
                    return timeRange;
                }
            }

            // 修复：查找最新会话时确保时间一致性
            log.debug("未提供sessionId，查找用户 {} 的最新会话", userId);

            Optional<EEGSession> activeSession = sessionService.getActiveSession(userId);
            EEGSession targetSession = null;

            if (activeSession.isPresent()) {
                targetSession = activeSession.get();
                log.info("找到用户 {} 的活跃会话: ID={}", userId, targetSession.getId());
            } else {
                // 修复：使用真正最新的会话（按created_at排序，因为这反映了真实的创建顺序）
                Optional<EEGSession> mostRecentSession = sessionService.getUserMostRecentSession(userId);
                if (mostRecentSession.isPresent()) {
                    targetSession = mostRecentSession.get();
                    log.info("找到用户 {} 的最新会话: ID={}, 状态={}, 创建时间={}",
                            userId, targetSession.getId(), targetSession.getSessionStatus(),
                            targetSession.getCreatedAt());
                } else {
                    Optional<EEGSession> latestCompleted = sessionService.getUserLatestCompletedSession(userId);
                    if (latestCompleted.isPresent()) {
                        targetSession = latestCompleted.get();
                        log.info("找到用户 {} 的最新完成会话: ID={}", userId, targetSession.getId());
                    }
                }
            }

            if (targetSession != null) {
                // 确保统一使用UTC时间字段
                timeRange.startTime = targetSession.getSessionStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");
                timeRange.endTime = targetSession.getSessionEndTimeUtc() != null ?
                        targetSession.getSessionEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ") :
                        getCurrentUtcTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " "); // 修复：使用UTC时间
                timeRange.sessionId = targetSession.getId();

                log.info("最终选择会话ID {} 的UTC时间范围: {} - {}",
                        targetSession.getId(), timeRange.startTime, timeRange.endTime);
                return timeRange;
            }

            // 最后默认使用最近1小时的UTC时间
            log.warn("用户 {} 没有找到任何会话，使用默认UTC时间范围", userId);
            LocalDateTime nowUtc = getCurrentUtcTime(); // 修复：使用UTC时间
            LocalDateTime oneHourAgoUtc = nowUtc.minusHours(1);

            timeRange.startTime = oneHourAgoUtc.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");
            timeRange.endTime = nowUtc.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");

            return timeRange;

        } catch (Exception e) {
            log.error("解析用户 {} 的时间范围时出错", userId, e);
            timeRange.hasError = true;
            timeRange.errorMessage = "时间范围解析失败: " + e.getMessage();
            return timeRange;
        }
    }

    /**
     * 获取当前UTC时间
     */
    public LocalDateTime getCurrentUtcTime() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    public List<Integer> parseChannelsArgument(Object channelsObj) {
        if (channelsObj == null) return null;
        if (channelsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> channelsList = (List<Object>) channelsObj;
            return channelsList.stream()
                    .map(obj -> obj instanceof Number ? ((Number) obj).intValue() : Integer.parseInt(obj.toString()))
                    .toList();
        }
        return null;
    }

    public List<String> parseBandsArgument(Object bandsObj) {
        if (bandsObj == null) return null;
        if (bandsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> bandsList = (List<Object>) bandsObj;
            return bandsList.stream()
                    .map(Object::toString)
                    .toList();
        }
        return null;
    }

    public Long getLongArgument(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            return Long.parseLong((String) value);
        }
        throw new IllegalArgumentException("参数 " + key + " 必须是数字类型");
    }

    public String getStringArgument(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = arguments.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    public Integer getIntegerArgument(Map<String, Object> arguments, String key, Integer defaultValue) {
        Object value = arguments.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            return Integer.parseInt((String) value);
        }
        return defaultValue;
    }

    public Boolean getBooleanArgument(Map<String, Object> arguments, String key, Boolean defaultValue) {
        Object value = arguments.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    public String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            long remainingSeconds = seconds % 60;
            return minutes + "分钟" + (remainingSeconds > 0 ? remainingSeconds + "秒" : "");
        } else {
            long hours = seconds / 3600;
            long remainingMinutes = (seconds % 3600) / 60;
            return hours + "小时" + (remainingMinutes > 0 ? remainingMinutes + "分钟" : "");
        }
    }

    public Map<String, Object> buildSessionContextInfo(EEGSession session) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", session.getId());
        info.put("startTimeUtc", session.getSessionStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        info.put("endTimeUtc", session.getSessionEndTimeUtc() != null ?
                session.getSessionEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        info.put("durationSeconds", session.calculateDurationSeconds());
        info.put("status", session.getSessionStatus().toString());
        info.put("isCompleted", session.getSessionStatus() == EEGSession.SessionStatus.COMPLETED);

        // 数据统计
        long totalPackets = (session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) +
                (session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0) +
                (session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0);
        info.put("totalDataPackets", totalPackets);
        info.put("hasData", totalPackets > 0);

        return info;
    }

    /**
     * SQL安全检查
     */
    public String validateSQLSafety(String sql, Long userId) {
        String upperSQL = sql.toUpperCase().trim();

        if (!upperSQL.startsWith("SELECT")) {
            return "只允许SELECT查询语句";
        }

        if (DANGEROUS_PATTERNS.matcher(sql).find()) {
            return "检测到潜在危险的SQL操作";
        }

        boolean hasValidTable = ALLOWED_TABLES.stream()
                .anyMatch(table -> upperSQL.contains(table.toUpperCase()));

        if (!hasValidTable) {
            return "只允许查询以下表: " + String.join(", ", ALLOWED_TABLES);
        }

        return null;
    }

    /**
     * 确保SQL包含用户ID过滤
     */
    public String ensureUserIdFilter(String sql, Long userId) {
        String upperSQL = sql.toUpperCase();

        if (!upperSQL.contains("USER_ID")) {
            if (upperSQL.contains("WHERE")) {
                sql = sql.replaceFirst("(?i)WHERE", "WHERE user_id = '" + userId + "' AND");
            } else {
                int fromIndex = upperSQL.indexOf("FROM");
                if (fromIndex != -1) {
                    String[] parts = sql.substring(fromIndex).split("\\s+");
                    if (parts.length >= 2) {
                        String beforeFrom = sql.substring(0, fromIndex + 4);
                        String tableName = parts[1];
                        String afterTable = sql.substring(fromIndex + 4 + tableName.length());

                        sql = beforeFrom + " " + tableName + " WHERE user_id = '" + userId + "'" + afterTable;
                    }
                }
            }
        }

        return sql;
    }


    // ========== 数学工具方法 ==========
    // 保持原有的辅助方法不变
    public double round(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    public double calculateConsistencyScore(double mean, double median) {
        double difference = Math.abs(mean - median);
        double average = (Math.abs(mean) + Math.abs(median)) / 2.0;

        if (average < 1e-12) return 100.0; // 都接近零

        double relativeError = difference / average;
        return Math.max(0.0, 100.0 - relativeError * 100);
    }

    /**
     * 样本方差计算（贝塞尔校正）
     */
    public double calculateSampleVariance(List<Double> values, double mean) {
        if (values.size() <= 1) return 0.0;

        double sumSquaredDiff = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .sum();

        return sumSquaredDiff / (values.size() - 1); // 贝塞尔校正
    }

    /**
     * 自相关函数计算
     */
    public double[] calculateAutocorrelation(List<Double> values, int maxLag) {
        int n = values.size();
        double[] autocorr = new double[maxLag + 1];

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        for (int lag = 0; lag <= maxLag && lag < n; lag++) {
            double numerator = 0.0;
            double denominator = 0.0;

            for (int i = 0; i < n - lag; i++) {
                numerator += (values.get(i) - mean) * (values.get(i + lag) - mean);
            }

            for (int i = 0; i < n; i++) {
                denominator += Math.pow(values.get(i) - mean, 2);
            }

            autocorr[lag] = denominator > 0 ? numerator / denominator : 0.0;
        }

        return autocorr;
    }


    // ========== 高级统计方法 ==========
// ========== 核心算法实现 ==========

    /**
     * 中位数计算
     */
    public double calculateMedian(List<Double> values) {
        List<Double> sorted = values.stream().sorted().collect(Collectors.toList());
        int size = sorted.size();

        if (size % 2 == 0) {
            return (sorted.get(size/2 - 1) + sorted.get(size/2)) / 2.0;
        } else {
            return sorted.get(size/2);
        }
    }

    /**
     * 四分位数计算
     */
    public double[] calculateQuartiles(List<Double> values) {
        List<Double> sorted = values.stream().sorted().collect(Collectors.toList());
        int size = sorted.size();

        // 使用线性插值方法
        double q1Index = (size - 1) * 0.25;
        double q3Index = (size - 1) * 0.75;

        double q1 = interpolate(sorted, q1Index);
        double q3 = interpolate(sorted, q3Index);

        return new double[]{q1, q3};
    }

    /**
     * 线性插值
     */
    public double interpolate(List<Double> sorted, double index) {
        int lowerIndex = (int) Math.floor(index);
        int upperIndex = (int) Math.ceil(index);

        if (lowerIndex == upperIndex) {
            return sorted.get(lowerIndex);
        }

        double weight = index - lowerIndex;
        return sorted.get(lowerIndex) * (1 - weight) + sorted.get(upperIndex) * weight;
    }

    /**
     * 偏度计算（Pearson moment coefficient）
     */
    public double calculateSkewness(List<Double> values, double mean, double stdDev) {
        if (values.size() < 3 || stdDev == 0) return 0.0;

        double sumCubedDeviations = values.stream()
                .mapToDouble(v -> Math.pow((v - mean) / stdDev, 3))
                .sum();

        int n = values.size();
        return (n * sumCubedDeviations) / ((n - 1) * (n - 2));
    }

    /**
     * 峰度计算（excess kurtosis）
     */
    public double calculateKurtosis(List<Double> values, double mean, double stdDev) {
        if (values.size() < 4 || stdDev == 0) return 0.0;

        double sumFourthPowers = values.stream()
                .mapToDouble(v -> Math.pow((v - mean) / stdDev, 4))
                .sum();

        int n = values.size();
        double kurtosis = (n * (n + 1) * sumFourthPowers) / ((n - 1) * (n - 2) * (n - 3));
        return kurtosis - 3.0; // 减去3得到excess kurtosis
    }


    /**
     * 线性趋势斜率计算
     */
    public double calculateLinearTrendSlope(List<Double> values) {
        int n = values.size();
        if (n < 2) return 0.0;

        double sumX = 0.0, sumY = 0.0, sumXY = 0.0, sumX2 = 0.0;

        for (int i = 0; i < n; i++) {
            double x = i; // 样本索引作为x
            double y = values.get(i);

            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denominator = n * sumX2 - sumX * sumX;
        if (Math.abs(denominator) < 1e-12) return 0.0;

        return (n * sumXY - sumX * sumY) / denominator;
    }

    /**
     * 平稳性指数计算
     */
    public double calculateStationarityIndex(List<Double> values) {
        int n = values.size();
        if (n < 10) return 1.0; // 样本太小，假设平稳

        // 分割时间序列为两半，比较方差
        int midPoint = n / 2;
        List<Double> firstHalf = values.subList(0, midPoint);
        List<Double> secondHalf = values.subList(midPoint, n);

        double mean1 = firstHalf.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double mean2 = secondHalf.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        double var1 = calculateSampleVariance(firstHalf, mean1);
        double var2 = calculateSampleVariance(secondHalf, mean2);

        // 方差比率，越接近1越平稳
        if (var2 == 0.0) return var1 == 0.0 ? 1.0 : 0.0;
        double varianceRatio = Math.min(var1, var2) / Math.max(var1, var2);

        return varianceRatio;
    }

    /**
     * 科研级数据限制计算
     */
    public int calculateScientificDataLimit(int requestedSamples, int channelCount) {
        // 基于采样定理和内存优化的限制
        if (requestedSamples <= 1000) {
            return 50000;  // 小规模分析
        } else if (requestedSamples <= 10000) {
            return 200000; // 中规模分析
        } else {
            return 1000000; // 大规模科研分析
        }
    }

    /**
     * 幅值质量评分
     */
    public double calculateAmplitudeQualityScore(double rmsAmplitude) {
        // 基于典型EEG幅值范围评分 (10-100μV为最佳)
        if (rmsAmplitude >= 10 && rmsAmplitude <= 100) {
            return 100.0; // 最佳范围
        } else if (rmsAmplitude >= 5 && rmsAmplitude <= 200) {
            return 80.0;  // 可接受范围
        } else if (rmsAmplitude >= 1 && rmsAmplitude <= 500) {
            return 60.0;  // 边缘可用
        } else {
            return Math.max(0.0, 40.0 - Math.abs(Math.log10(rmsAmplitude / 50.0)) * 20); // 对数衰减
        }
    }


    // ========== 数据抽样 ==========

    /**
     * 提取数据样本
     */
    @SuppressWarnings("deprecation")
    public List<Map<String, Object>> extractDataSample(JsonNode dataNode, int sampleSize) {
        List<Map<String, Object>> samples = new ArrayList<>();

        for (int i = 0; i < Math.min(sampleSize, dataNode.size()); i++) {
            JsonNode record = dataNode.get(i);
            Map<String, Object> sample = new HashMap<>();

            record.fields().forEachRemaining(entry -> {
                sample.put(entry.getKey(), entry.getValue().isTextual() ?
                        entry.getValue().asText() : entry.getValue().asDouble());
            });

            samples.add(sample);
        }

        return samples;
    }


    // ========== 时间解析方法 ==========
    public TimeRange parseDirectTimeArguments(Map<String, Object> arguments, int defaultTimeWindow) {
        TimeRange timeRange = new TimeRange();

        try {
            String timePoint = getStringArgument(arguments, "timePoint", null);
            String startTime = getStringArgument(arguments, "startTime", null);
            String endTime = getStringArgument(arguments, "endTime", null);

            if (timePoint != null && !timePoint.trim().isEmpty()) {
                // 处理单个时间点查询
                LocalDateTime targetTime = parseTimeString(timePoint.trim());
                LocalDateTime windowStart = targetTime.minusSeconds(defaultTimeWindow / 2);
                LocalDateTime windowEnd = targetTime.plusSeconds(defaultTimeWindow / 2);

                timeRange.startTime = windowStart.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");
                timeRange.endTime = windowEnd.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");

                log.info("解析时间点查询 - 目标时间: {}, 查询窗口: {} 到 {}",
                        timePoint, timeRange.startTime, timeRange.endTime);

            } else if (startTime != null && endTime != null) {
                // 处理时间范围查询
                LocalDateTime start = parseTimeString(startTime.trim());
                LocalDateTime end = parseTimeString(endTime.trim());

                timeRange.startTime = start.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");
                timeRange.endTime = end.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");

                log.info("解析时间范围查询 - 开始: {}, 结束: {}", timeRange.startTime, timeRange.endTime);

            } else {
                timeRange.hasError = true;
                timeRange.errorMessage = "必须提供 timePoint 或者 startTime+endTime 参数";
            }

            return timeRange;

        } catch (Exception e) {
            timeRange.hasError = true;
            timeRange.errorMessage = "时间参数解析失败: " + e.getMessage();
            log.error("解析直接时间参数失败", e);
            return timeRange;
        }
    }

    /**
     * 解析时间字符串，支持多种格式
     */
    public LocalDateTime parseTimeString(String timeStr) {
        // 移除可能的时区信息
        timeStr = timeStr.replaceAll("Z$", "").replaceAll("\\+\\d{2}:\\d{2}$", "");

        // 尝试不同的时间格式
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        );

        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDateTime.parse(timeStr, formatter);
            } catch (Exception ignored) {
                // 继续尝试下一个格式
            }
        }

        throw new IllegalArgumentException("无法解析时间格式: " + timeStr +
                "。支持格式: 2025-09-10T07:58:12 或 2025-09-10 07:58:12");
    }

}
