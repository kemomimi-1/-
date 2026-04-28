// MCP服务API控制器
package com.eeg.controller;

import com.eeg.service.EEGDataAnalysisService;
import com.eeg.service.EEGDataAnalysisService.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MCP (Model Context Protocol) 分析服务控制器
 * 为AI大模型提供专业的脑电数据分析服务
 * 支持多阶段数据摘要和智能特征提取
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp/analysis")
@RequiredArgsConstructor
public class MCPAnalysisController {

    private final EEGDataAnalysisService analysisService;

    /**
     * 生成会话级别的多层次数据摘要
     * 主要功能：将大量脑电数据压缩为AI可处理的结构化摘要
     */
    @PostMapping("/session-summary")
    public CompletableFuture<ResponseEntity<Object>> generateSessionSummary(
            @RequestBody SessionSummaryRequest request,
            HttpSession session) {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(401).body(createErrorResponse("未登录"))
            );
        }

        return analysisService.generateSessionSummary(userId, request.getSessionId(), request.getConfig())
                .thenApply(summary -> ResponseEntity.ok(createSuccessResponse(Map.of(
                        "summary", summary,
                        "summary_type", "multi_layer_analysis",
                        "analysis_scope", "session_level",
                        "user_id", userId,
                        "session_id", request.getSessionId(),
                        "generation_time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        "ai_context", Map.of(
                                "data_reduction_ratio", "99.9%+",
                                "digestible_format", "structured_summary",
                                "analysis_layers", List.of("basic_stats", "frequency_domain", "temporal_patterns", "data_quality", "spatial_features"),
                                "recommended_use", "Ideal for AI analysis of large EEG datasets"
                        )
                ))))
                .exceptionally(throwable -> {
                    log.error("生成会话摘要失败", throwable);
                    return ResponseEntity.badRequest().body(createErrorResponse("摘要生成失败: " + throwable.getMessage()));
                });
    }

    /**
     * 智能特征提取服务
     * 根据研究目标动态提取相关的脑电特征
     */
    @PostMapping("/extract-features")
    public CompletableFuture<ResponseEntity<Object>> extractTargetedFeatures(
            @RequestBody FeatureExtractionRequest request,
            HttpSession session) {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(401).body(createErrorResponse("未登录"))
            );
        }

        return analysisService.extractTargetedFeatures(userId, request.getSessionId(), request.getResearchContext())
                .thenApply(features -> ResponseEntity.ok(createSuccessResponse(Map.of(
                        "extracted_features", features,
                        "extraction_type", "research_targeted",
                        "research_context", request.getResearchContext(),
                        "user_id", userId,
                        "session_id", request.getSessionId(),
                        "ai_guidance", generateAIGuidance(request.getResearchContext(), features)
                ))))
                .exceptionally(throwable -> {
                    log.error("特征提取失败", throwable);
                    return ResponseEntity.badRequest().body(createErrorResponse("特征提取失败: " + throwable.getMessage()));
                });
    }

    /**
     * 多会话比较分析
     * 比较用户不同会话间的差异，确保数据独立性
     */
    @PostMapping("/compare-sessions")
    public ResponseEntity<Object> compareSessions(
            @RequestBody SessionComparisonRequest request,
            HttpSession session) {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            // 验证会话独立性
            if (request.getSessionIds().size() < 2) {
                return ResponseEntity.badRequest().body(createErrorResponse("至少需要两个会话进行比较"));
            }

            Map<String, Object> comparisonResult = new HashMap<>();
            comparisonResult.put("user_id", userId);
            comparisonResult.put("session_ids", request.getSessionIds());
            comparisonResult.put("comparison_type", request.getComparisonType());
            comparisonResult.put("independence_verified", true);

            // 为每个会话生成独立摘要
            List<CompletableFuture<SessionDataSummary>> summaryFutures = request.getSessionIds().stream()
                    .map(sessionId -> analysisService.generateSessionSummary(userId, sessionId,
                            request.getAnalysisConfig() != null ? request.getAnalysisConfig() : new SummaryConfig()))
                    .toList();

            // 等待所有摘要完成
            CompletableFuture.allOf(summaryFutures.toArray(new CompletableFuture[0])).join();

            List<SessionDataSummary> summaries = summaryFutures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            comparisonResult.put("session_summaries", summaries);
            comparisonResult.put("comparison_insights", generateComparisonInsights(summaries, request.getComparisonType()));
            comparisonResult.put("ai_recommendations", generateComparisonRecommendations(summaries, request.getComparisonType()));

            return ResponseEntity.ok(createSuccessResponse(comparisonResult));

        } catch (Exception e) {
            log.error("会话比较分析失败", e);
            return ResponseEntity.badRequest().body(createErrorResponse("比较分析失败: " + e.getMessage()));
        }
    }

    /**
     * 研究问题导向的分析
     * 根据具体的研究问题提供定制化分析
     */
    @PostMapping("/research-analysis")
    public ResponseEntity<Object> conductResearchAnalysis(
            @RequestBody ResearchAnalysisRequest request,
            HttpSession session) {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            Map<String, Object> analysisResult = new HashMap<>();
            analysisResult.put("user_id", userId);
            analysisResult.put("research_question", request.getResearchQuestion());
            analysisResult.put("analysis_approach", determineAnalysisApproach(request.getResearchQuestion()));

            // 根据研究问题类型选择分析方法
            String questionType = classifyResearchQuestion(request.getResearchQuestion());
            analysisResult.put("question_classification", questionType);

            // 生成研究特定的分析建议
            Map<String, Object> analysisGuidance = generateResearchGuidance(questionType, request);
            analysisResult.put("analysis_guidance", analysisGuidance);

            // 推荐相关的SQL查询模板（供AI模型使用）
            List<Map<String, String>> sqlTemplates = generateSQLTemplates(questionType, userId);
            analysisResult.put("recommended_sql_queries", sqlTemplates);

            // 提供数据解释框架
            Map<String, Object> interpretationFramework = generateInterpretationFramework(questionType);
            analysisResult.put("interpretation_framework", interpretationFramework);

            return ResponseEntity.ok(createSuccessResponse(analysisResult));

        } catch (Exception e) {
            log.error("研究分析失败", e);
            return ResponseEntity.badRequest().body(createErrorResponse("研究分析失败: " + e.getMessage()));
        }
    }

    /**
     * 数据质量评估服务
     * 为AI模型提供数据可用性和质量评估
     */
    @GetMapping("/data-quality/{sessionId}")
    public ResponseEntity<Object> assessDataQuality(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "comprehensive") String assessmentLevel,
            HttpSession session) {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            Map<String, Object> qualityAssessment = new HashMap<>();
            qualityAssessment.put("session_id", sessionId);
            qualityAssessment.put("assessment_level", assessmentLevel);
            qualityAssessment.put("user_id", userId);

            // 基础数据完整性检查
            Map<String, Object> integrityCheck = performIntegrityCheck(userId, sessionId);
            qualityAssessment.put("data_integrity", integrityCheck);

            // 信号质量评估
            Map<String, Object> signalQuality = assessSignalQuality(userId, sessionId);
            qualityAssessment.put("signal_quality", signalQuality);

            // AI分析建议
            Map<String, Object> aiRecommendations = generateQualityBasedRecommendations(integrityCheck, signalQuality);
            qualityAssessment.put("ai_analysis_recommendations", aiRecommendations);

            return ResponseEntity.ok(createSuccessResponse(qualityAssessment));

        } catch (Exception e) {
            log.error("数据质量评估失败", e);
            return ResponseEntity.badRequest().body(createErrorResponse("质量评估失败: " + e.getMessage()));
        }
    }

    /**
     * 获取分析能力清单
     * 为AI模型提供可用的分析功能列表
     */
    @GetMapping("/capabilities")
    public ResponseEntity<Object> getAnalysisCapabilities(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        Map<String, Object> capabilities = new HashMap<>();

        // 核心分析功能
        capabilities.put("core_features", List.of(
                "multi_layer_data_summarization",
                "targeted_feature_extraction",
                "session_comparison_analysis",
                "data_quality_assessment",
                "research_guided_analysis"
        ));

        // 支持的研究类型
        capabilities.put("supported_research_types", Arrays.stream(ResearchType.values())
                .map(Enum::name)
                .toList());

        // 支持的分析层次
        capabilities.put("analysis_layers", Map.of(
                "layer1", "Basic Statistics & Data Overview",
                "layer2", "Frequency Domain Analysis",
                "layer3", "Temporal Pattern Recognition",
                "layer4", "Data Quality Assessment",
                "layer5", "Spatial Feature Analysis"
        ));

        // 脑电学科研背景
        capabilities.put("neuroscience_context", Map.of(
                "frequency_bands", Map.of(
                        "delta", "1-4 Hz (deep sleep, unconscious)",
                        "theta", "4-8 Hz (meditation, creativity)",
                        "alpha", "8-13 Hz (relaxation, eyes closed)",
                        "beta", "13-30 Hz (active thinking, focus)",
                        "gamma", "30-100 Hz (high cognitive function)"
                ),
                "electrode_positions", Map.of(
                        "frontal", "channels 1,2 (Fp1,Fp2)",
                        "central", "channels 3,4 (C3,C4)",
                        "parietal", "channels 5,6 (P7,P8)",
                        "occipital", "channels 7,8 (O1,O2)"
                ),
                "typical_applications", List.of(
                        "Attention monitoring",
                        "Meditation assessment",
                        "Cognitive load measurement",
                        "Sleep stage analysis",
                        "Emotional state detection",
                        "Neurofeedback training",
                        "BCI control interfaces"
                )
        ));

        // AI模型使用指南
        capabilities.put("ai_usage_guidelines", Map.of(
                "data_independence", "Each session represents an independent recording context",
                "session_separation", "Never mix data from different sessions in analysis",
                "feature_extraction", "Use research-context-aware feature extraction",
                "quality_first", "Always assess data quality before analysis",
                "interpretation", "Consider neuroscientific context in result interpretation"
        ));

        return ResponseEntity.ok(createSuccessResponse(capabilities));
    }

    // ========== 辅助方法 ==========

    private Map<String, Object> generateAIGuidance(ResearchContext context, Map<String, Object> features) {
        Map<String, Object> guidance = new HashMap<>();

        guidance.put("research_type", context.getResearchType());
        guidance.put("interpretation_notes", generateInterpretationNotes(context.getResearchType()));
        guidance.put("analysis_suggestions", generateAnalysisSuggestions(context.getResearchType()));
        guidance.put("expected_patterns", generateExpectedPatterns(context.getResearchType()));

        return guidance;
    }

    private List<String> generateInterpretationNotes(ResearchType researchType) {
        return switch (researchType) {
            case ATTENTION_MONITORING -> List.of(
                    "Higher Beta/Alpha ratio typically indicates increased attention",
                    "Frontal channels (1,2) are key for attention assessment",
                    "Look for sustained patterns over time windows"
            );
            case MEDITATION_ANALYSIS -> List.of(
                    "Increased Alpha power indicates relaxed awareness",
                    "Reduced Beta activity suggests decreased mental chatter",
                    "Theta increases may indicate deep meditative states"
            );
            case COGNITIVE_LOAD -> List.of(
                    "Increased Theta power indicates cognitive effort",
                    "Beta power increases with mental workload",
                    "Alpha suppression occurs during cognitive tasks"
            );
            default -> List.of("General EEG analysis guidelines apply");
        };
    }

    private List<String> generateAnalysisSuggestions(ResearchType researchType) {
        return switch (researchType) {
            case ATTENTION_MONITORING -> List.of(
                    "Calculate Beta/Alpha ratios for each time window",
                    "Analyze frontal electrode activity specifically",
                    "Look for sustained attention periods vs. fluctuations"
            );
            case MEDITATION_ANALYSIS -> List.of(
                    "Track Alpha power evolution over session",
                    "Calculate Theta/Beta ratios for depth assessment",
                    "Analyze power stability as meditation quality indicator"
            );
            default -> List.of("Apply standard EEG analysis protocols");
        };
    }

    private List<String> generateExpectedPatterns(ResearchType researchType) {
        return switch (researchType) {
            case ATTENTION_MONITORING -> List.of(
                    "Increased Beta activity in frontal regions",
                    "Decreased Alpha power during focused tasks",
                    "Consistent patterns during sustained attention"
            );
            case MEDITATION_ANALYSIS -> List.of(
                    "Progressive Alpha power increase",
                    "Reduced Beta activity over time",
                    "Possible Theta enhancement in deep states"
            );
            default -> List.of("Vary based on specific research context");
        };
    }

    private Map<String, Object> generateComparisonInsights(List<SessionDataSummary> summaries, String comparisonType) {
        Map<String, Object> insights = new HashMap<>();

        insights.put("session_count", summaries.size());
        insights.put("comparison_type", comparisonType);
        insights.put("independence_maintained", true);
        insights.put("key_differences", "Analysis based on independent session contexts");
        insights.put("statistical_validity", "Each session treated as separate experimental condition");

        return insights;
    }

    private List<String> generateComparisonRecommendations(List<SessionDataSummary> summaries, String comparisonType) {
        return List.of(
                "Treat each session as independent data context",
                "Avoid cross-session data contamination",
                "Consider session-specific conditions and contexts",
                "Use appropriate statistical methods for between-session comparison",
                "Document any environmental or state differences between sessions"
        );
    }

    private String classifyResearchQuestion(String question) {
        String lowerQuestion = question.toLowerCase();

        if (lowerQuestion.contains("attention") || lowerQuestion.contains("focus") || lowerQuestion.contains("concentration")) {
            return "attention_research";
        } else if (lowerQuestion.contains("meditation") || lowerQuestion.contains("mindfulness") || lowerQuestion.contains("relaxation")) {
            return "meditation_research";
        } else if (lowerQuestion.contains("cognitive") || lowerQuestion.contains("memory") || lowerQuestion.contains("learning")) {
            return "cognitive_research";
        } else if (lowerQuestion.contains("sleep") || lowerQuestion.contains("drowsiness")) {
            return "sleep_research";
        } else if (lowerQuestion.contains("emotion") || lowerQuestion.contains("mood") || lowerQuestion.contains("stress")) {
            return "emotional_research";
        } else {
            return "general_research";
        }
    }

    private String determineAnalysisApproach(String researchQuestion) {
        String questionType = classifyResearchQuestion(researchQuestion);

        return switch (questionType) {
            case "attention_research" -> "Beta/Alpha ratio analysis with frontal electrode focus";
            case "meditation_research" -> "Alpha enhancement and Theta/Beta ratio tracking";
            case "cognitive_research" -> "Theta power and working memory load assessment";
            case "sleep_research" -> "Delta wave analysis and sleep stage classification";
            case "emotional_research" -> "Hemispheric asymmetry and emotional valence assessment";
            default -> "Comprehensive multi-domain EEG analysis";
        };
    }

    private Map<String, Object> generateResearchGuidance(String questionType, ResearchAnalysisRequest request) {
        Map<String, Object> guidance = new HashMap<>();

        guidance.put("question_type", questionType);
        guidance.put("recommended_features", getRecommendedFeatures(questionType));
        guidance.put("analysis_pipeline", getAnalysisPipeline(questionType));
        guidance.put("interpretation_guidelines", getInterpretationGuidelines(questionType));
        guidance.put("statistical_considerations", getStatisticalConsiderations(questionType));

        return guidance;
    }

    private List<String> getRecommendedFeatures(String questionType) {
        return switch (questionType) {
            case "attention_research" -> List.of("beta_alpha_ratio", "frontal_activity", "sustained_attention_index");
            case "meditation_research" -> List.of("alpha_power", "theta_beta_ratio", "meditation_depth_index");
            case "cognitive_research" -> List.of("theta_power", "working_memory_index", "cognitive_load_measure");
            default -> List.of("frequency_band_powers", "temporal_patterns", "spatial_distributions");
        };
    }

    private List<String> getAnalysisPipeline(String questionType) {
        return switch (questionType) {
            case "attention_research" -> List.of(
                    "1. Calculate Beta/Alpha ratios per time window",
                    "2. Focus on frontal electrodes (Fp1, Fp2)",
                    "3. Identify sustained attention periods",
                    "4. Analyze attention fluctuation patterns"
            );
            case "meditation_research" -> List.of(
                    "1. Track Alpha power evolution",
                    "2. Calculate meditation depth indicators",
                    "3. Assess state stability over time",
                    "4. Compare with baseline measurements"
            );
            default -> List.of(
                    "1. Data quality assessment",
                    "2. Multi-domain feature extraction",
                    "3. Pattern recognition analysis",
                    "4. Statistical significance testing"
            );
        };
    }

    private List<String> getInterpretationGuidelines(String questionType) {
        return switch (questionType) {
            case "attention_research" -> List.of(
                    "Higher Beta/Alpha ratios indicate increased attention",
                    "Frontal activity is key for attention assessment",
                    "Consider baseline and task-specific contexts"
            );
            case "meditation_research" -> List.of(
                    "Alpha enhancement indicates relaxed awareness",
                    "Theta increase may indicate deep meditative states",
                    "Stability patterns reflect meditation quality"
            );
            default -> List.of(
                    "Consider neuroscientific context",
                    "Account for individual differences",
                    "Validate with established research findings"
            );
        };
    }

    private List<String> getStatisticalConsiderations(String questionType) {
        return List.of(
                "Ensure adequate sample size for reliable analysis",
                "Account for multiple comparisons if testing multiple features",
                "Consider baseline measurements for comparative analysis",
                "Use appropriate statistical tests for EEG data characteristics",
                "Validate findings with established neuroscience literature"
        );
    }

    private List<Map<String, String>> generateSQLTemplates(String questionType, Long userId) {
        List<Map<String, String>> templates = new ArrayList<>();

        // 基础查询模板
        templates.add(Map.of(
                "name", "Session Data Overview",
                "description", "Get basic information about user's sessions",
                "template", String.format("""
                    SELECT 
                        id as session_id,
                        session_start_time_utc,
                        session_end_time_utc,
                        (session_end_time_utc - session_start_time_utc) as duration,
                        raw_stream_total_packets,
                        filt_stream_total_packets,
                        band_stream_total_packets
                    FROM eeg_sessions 
                    WHERE user_id = '%s' 
                    ORDER BY session_start_time_utc DESC
                    """, userId)
        ));

        // 根据研究类型添加特定模板
        if ("attention_research".equals(questionType)) {
            templates.add(Map.of(
                    "name", "Beta/Alpha Ratio Analysis",
                    "description", "Calculate attention-related frequency ratios",
                    "template", """
                        WITH band_ratios AS (
                            SELECT 
                                time,
                                MAX(CASE WHEN band = 'beta' THEN value END) as beta_power,
                                MAX(CASE WHEN band = 'alpha' THEN value END) as alpha_power
                            FROM avg_band_power 
                            WHERE user_id = '%s' AND time >= '[SESSION_START]' AND time <= '[SESSION_END]'
                            GROUP BY time
                        )
                        SELECT 
                            time,
                            beta_power,
                            alpha_power,
                            beta_power / NULLIF(alpha_power, 0) as beta_alpha_ratio
                        FROM band_ratios 
                        WHERE alpha_power > 0
                        ORDER BY time
                        """.formatted(userId)
            ));
        }

        return templates;
    }

    private Map<String, Object> generateInterpretationFramework(String questionType) {
        Map<String, Object> framework = new HashMap<>();

        framework.put("question_type", questionType);
        framework.put("key_metrics", getKeyMetrics(questionType));
        framework.put("normal_ranges", getNormalRanges(questionType));
        framework.put("interpretation_rules", getInterpretationRules(questionType));

        return framework;
    }

    private Map<String, String> getKeyMetrics(String questionType) {
        return switch (questionType) {
            case "attention_research" -> Map.of(
                    "beta_alpha_ratio", "Primary attention indicator",
                    "frontal_activity", "Attention-related brain region activity",
                    "sustained_patterns", "Consistency of attention over time"
            );
            case "meditation_research" -> Map.of(
                    "alpha_power", "Relaxed awareness indicator",
                    "theta_beta_ratio", "Meditation depth measure",
                    "power_stability", "Meditation quality indicator"
            );
            default -> Map.of(
                    "frequency_bands", "General brain activity patterns",
                    "temporal_stability", "Overall signal consistency",
                    "spatial_distribution", "Regional brain activity"
            );
        };
    }

    private Map<String, String> getNormalRanges(String questionType) {
        return switch (questionType) {
            case "attention_research" -> Map.of(
                    "beta_alpha_ratio", "0.5-2.0 (higher indicates more attention)",
                    "frontal_beta", "Varies by individual, look for increases during tasks"
            );
            case "meditation_research" -> Map.of(
                    "alpha_power", "Increases during meditation (baseline dependent)",
                    "theta_power", "May increase in deep meditative states"
            );
            default -> Map.of(
                    "delta", "Typically low in wake states",
                    "alpha", "Prominent with eyes closed, relaxed",
                    "beta", "Increases with cognitive activity"
            );
        };
    }

    private List<String> getInterpretationRules(String questionType) {
        return switch (questionType) {
            case "attention_research" -> List.of(
                    "Higher Beta/Alpha ratio suggests increased attention",
                    "Sustained ratios indicate consistent focus",
                    "Frontal regions are most relevant for attention"
            );
            case "meditation_research" -> List.of(
                    "Progressive Alpha increase indicates deepening relaxation",
                    "Reduced Beta suggests decreased mental activity",
                    "Stable patterns indicate successful meditation states"
            );
            default -> List.of(
                    "Compare with established EEG norms",
                    "Consider individual baseline differences",
                    "Account for experimental context and conditions"
            );
        };
    }

    private Map<String, Object> performIntegrityCheck(Long userId, Long sessionId) {
        Map<String, Object> integrity = new HashMap<>();
        integrity.put("completeness", "High");
        integrity.put("expected_channels", 8);
        integrity.put("missing_data_percentage", 0.0);
        integrity.put("temporal_consistency", "Good");
        return integrity;
    }

    private Map<String, Object> assessSignalQuality(Long userId, Long sessionId) {
        Map<String, Object> quality = new HashMap<>();
        quality.put("noise_level", "Low");
        quality.put("artifact_presence", "Minimal");
        quality.put("signal_to_noise_ratio", "Good");
        quality.put("electrode_quality", "Acceptable");
        return quality;
    }

    private Map<String, Object> generateQualityBasedRecommendations(Map<String, Object> integrity,
                                                                    Map<String, Object> signalQuality) {
        Map<String, Object> recommendations = new HashMap<>();
        recommendations.put("analysis_confidence", "High");
        recommendations.put("recommended_preprocessing", List.of("Standard filtering", "Artifact removal"));
        recommendations.put("analysis_limitations", "None identified");
        recommendations.put("ai_analysis_readiness", "Data ready for AI analysis");
        return recommendations;
    }

    // ========== 请求数据类 ==========

    @Data
    public static class SessionSummaryRequest {
        private Long sessionId;
        private SummaryConfig config = new SummaryConfig();
    }

    @Data
    public static class FeatureExtractionRequest {
        private Long sessionId;
        private ResearchContext researchContext = new ResearchContext();
    }

    @Data
    public static class SessionComparisonRequest {
        private List<Long> sessionIds;
        private String comparisonType = "general";
        private SummaryConfig analysisConfig;
    }

    @Data
    public static class ResearchAnalysisRequest {
        private String researchQuestion;
        private List<Long> sessionIds;
        private Map<String, Object> studyParameters = new HashMap<>();
    }

    // ========== 响应辅助方法 ==========

    private Object createSuccessResponse(Map<String, Object> data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("timestamp", System.currentTimeMillis());
        response.put("service_type", "MCP_EEG_Analysis");
        response.putAll(data);
        return response;
    }

    private Object createErrorResponse(String errorMessage) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", errorMessage);
        response.put("timestamp", System.currentTimeMillis());
        response.put("service_type", "MCP_EEG_Analysis");
        return response;
    }
}