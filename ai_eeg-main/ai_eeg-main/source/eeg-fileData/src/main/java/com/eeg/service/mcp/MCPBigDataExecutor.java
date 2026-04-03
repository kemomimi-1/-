package com.eeg.service.mcp;

import com.eeg.entity.EEGSession;
import com.eeg.service.EEGSessionService;
import com.eeg.service.InfluxDBService;
import com.eeg.service.EEGDataAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.*;

import java.util.concurrent.CompletableFuture;

/**
 * MCP 大数据处理执行器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MCPBigDataExecutor {

    private final EEGSessionService sessionService;
    private final EEGDataAnalysisService analysisService;
    private final InfluxDBService influxDBService;
    private final MCPToolUtils toolUtils;

    /**
     * 执行生成全面会话摘要
     */
    public Object executeGenerateComprehensiveSessionSummary(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        try {
            Long sessionId = toolUtils.getLongArgument(arguments, "sessionId");
            String analysisLevel = toolUtils.getStringArgument(arguments, "analysisLevel", "comprehensive");
            Boolean includeTemporalAnalysis = toolUtils.getBooleanArgument(arguments, "includeTemporalAnalysis", true);
            Boolean includeFrequencyAnalysis = toolUtils.getBooleanArgument(arguments, "includeFrequencyAnalysis", true);
            Boolean includeSpatialAnalysis = toolUtils.getBooleanArgument(arguments, "includeSpatialAnalysis", true);
            Boolean includeQualityAssessment = toolUtils.getBooleanArgument(arguments, "includeQualityAssessment", true);

            log.info("执行generateComprehensiveSessionSummary - 用户ID: {}, 会话ID: {}, 分析级别: {}",
                    userId, sessionId, analysisLevel);

            // 构建分析配置
            EEGDataAnalysisService.SummaryConfig config = new EEGDataAnalysisService.SummaryConfig();
            config.setAnalysisLevel(analysisLevel);
            config.setIncludeArtifactDetection(includeQualityAssessment);
            config.setIncludeSpatialAnalysis(includeSpatialAnalysis);

            // 根据参数设置目标特征
            List<String> targetFeatures = new ArrayList<>();
            if (includeTemporalAnalysis) targetFeatures.add("temporal_patterns");
            if (includeFrequencyAnalysis) targetFeatures.add("frequency_features");
            if (includeSpatialAnalysis) targetFeatures.add("spatial_correlations");
            if (includeQualityAssessment) targetFeatures.add("quality_metrics");
            config.setTargetFeatures(targetFeatures);

            // 异步生成摘要
            CompletableFuture<EEGDataAnalysisService.SessionDataSummary> summaryFuture =
                    analysisService.generateSessionSummary(userId, sessionId, config);

            // 等待结果并处理
            EEGDataAnalysisService.SessionDataSummary summary = summaryFuture.get();

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("sessionId", sessionId);
            result.put("analysisLevel", analysisLevel);
            result.put("summary", summary);
            result.put("analysisTimestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            result.put("optimization", "大数据量优化处理完成，采用分层采样和聚合算法");

            // 添加数据量信息
            if (summary.getAnalysisMetadata() != null) {
                result.put("dataVolumeInfo", summary.getAnalysisMetadata().get("optimization_strategy"));
            }

            log.info("全面会话摘要生成完成 - 会话ID: {}, 分析级别: {}", sessionId, analysisLevel);
            return result;

        } catch (Exception e) {
            log.error("生成全面会话摘要失败 - 用户ID: {}", userId, e);
            return Map.of(
                    "error", "生成全面会话摘要失败: " + e.getMessage(),
                    "userId", userId,
                    "toolName", "generateComprehensiveSessionSummary"
            );
        }
    }

    /**
     * 执行评估会话数据量
     */
    public Object executeAssessSessionDataVolume(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        try {
            Long sessionId = toolUtils.getLongArgument(arguments, "sessionId");
            Boolean includeRecommendations = toolUtils.getBooleanArgument(arguments, "includeRecommendations", true);
            Boolean analyzeDataStreams = toolUtils.getBooleanArgument(arguments, "analyzeDataStreams", true);

            log.info("执行assessSessionDataVolume - 用户ID: {}, 会话ID: {}", userId, sessionId);

            // 获取会话信息
            Optional<EEGSession> session = sessionService.getActiveSession(userId)
                    .or(() -> sessionService.getUserSessionHistory(userId, 100).stream()
                            .filter(s -> s.getId().equals(sessionId))
                            .findFirst());

            if (session.isEmpty()) {
                return Map.of("error", "会话ID " + sessionId + " 不存在或无权访问");
            }

            EEGSession eegSession = session.get();
            String startTime = eegSession.getSessionStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");
            String endTime = eegSession.getSessionEndTimeUtc() != null ?
                    eegSession.getSessionEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ") :
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("T", " ");

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("sessionId", sessionId);
            result.put("assessmentTimestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            // 评估数据量
            try {
                // 评估原始数据量
                String rawCountQuery = String.format("""
                SELECT COUNT(*) as record_count, 
                       MIN(time) as first_record,
                       MAX(time) as last_record,
                       COUNT(DISTINCT channel) as channel_count
                FROM timeseriesraw 
                WHERE user_id = '%s' AND time >= '%s' AND time <= '%s'
                """, userId, startTime, endTime);

                String rawResult = influxDBService.queryData(rawCountQuery, "json").block();

                // 评估频段数据量
                String bandCountQuery = String.format("""
                SELECT COUNT(*) as record_count,
                       MIN(time) as first_record, 
                       MAX(time) as last_record,
                       COUNT(DISTINCT band) as band_count
                FROM avg_band_power 
                WHERE user_id = '%s' AND time >= '%s' AND time <= '%s'
                """, userId, startTime, endTime);

                String bandResult = influxDBService.queryData(bandCountQuery, "json").block();

                result.put("rawDataAssessment", rawResult);
                result.put("bandDataAssessment", bandResult);

                // 分析数据流（如果需要）
                if (analyzeDataStreams) {
                    String filtCountQuery = String.format("""
                    SELECT COUNT(*) as record_count,
                           MIN(time) as first_record,
                           MAX(time) as last_record,
                           COUNT(DISTINCT channel) as channel_count
                    FROM timeseriesfilt
                    WHERE user_id = '%s' AND time >= '%s' AND time <= '%s'
                    """, userId, startTime, endTime);

                    String filtResult = influxDBService.queryData(filtCountQuery, "json").block();
                    result.put("filteredDataAssessment", filtResult);
                }

                // 生成处理建议（如果需要）
                if (includeRecommendations) {
                    Map<String, Object> recommendations = toolUtils.generateDataProcessingRecommendations(rawResult, bandResult);
                    result.put("processingRecommendations", recommendations);
                }

                result.put("sessionDuration", Map.of(
                        "startTime", startTime,
                        "endTime", endTime,
                        "durationSeconds", eegSession.calculateDurationSeconds()
                ));

            } catch (Exception e) {
                log.warn("数据量评估部分失败", e);
                result.put("assessmentWarning", "部分数据量评估失败: " + e.getMessage());
            }

            log.info("会话数据量评估完成 - 会话ID: {}", sessionId);
            return result;

        } catch (Exception e) {
            log.error("评估会话数据量失败 - 用户ID: {}", userId, e);
            return Map.of(
                    "error", "评估会话数据量失败: " + e.getMessage(),
                    "userId", userId,
                    "toolName", "assessSessionDataVolume"
            );
        }
    }


}
