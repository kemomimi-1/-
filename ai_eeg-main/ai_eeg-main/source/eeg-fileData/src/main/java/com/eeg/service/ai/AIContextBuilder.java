package com.eeg.service.ai;

import com.eeg.entity.*;
import com.eeg.entity.ai.*;
import com.eeg.service.*;
import com.eeg.config.AIModelConfig;
import com.eeg.service.AIModelService.AIResponse;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@Slf4j
@RequiredArgsConstructor
public class AIContextBuilder {

    private final AIQueryStrategyService aiQueryStrategyService;
    private final AIModelConfig aiModelConfig;
    private final EEGSessionService sessionService;
    private final MCPToolRegistry mcpToolRegistry;

public Map<String, Object> buildEnhancedIntelligentContext(Long userId, AIQueryRequest request, String userQuery) {
        Map<String, Object> context = new HashMap<>();

        try {
            // 基础信息
            context.put("userId", userId);
            context.put("currentTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            context.put("timezone", "UTC");

            // 查询复杂度分析
            QueryComplexityAnalysis complexity = aiQueryStrategyService.analyzeQueryComplexity(userQuery);
            context.put("queryComplexityAnalysis", complexity);

            // 智能协作策略
            CollaborationStrategy collaborationStrategy = aiQueryStrategyService.determineEnhancedCollaborationStrategy(userQuery, complexity);
            context.put("collaborationStrategy", collaborationStrategy);

            // 场景相关的系统提示词
            String scenario = determineQueryScenario(userQuery);
            context.put("scenarioContext", scenario);
            context.put("contextualSystemPrompt", aiModelConfig.getContextualPrompt(scenario));

            // MCP工具集成信息 - 从MCPToolRegistry动态获取
            context.put("mcpToolsReady", true);
            context.put("mcpToolsIntegrated", buildMCPToolsIntegrationInfo());

            // 构建会话上下文
            buildEnhancedSessionContext(userId, request, context, userQuery);

            // 工具选择指导 - 基于实际可用工具
            buildToolSelectionGuidance(context, collaborationStrategy, userQuery);

            // 数据处理策略
            buildDataProcessingStrategy(userId, context, complexity);

            // 性能优化配置
            buildPerformanceOptimizationConfig(context, collaborationStrategy);

            // 请求特定上下文
            if (request != null && request.getContext() != null) {
                context.putAll(request.getContext());
            }

            log.debug("简化版智能协作上下文构建完成 - 字段数: {}, 协作类型: {}, 预期工具数: {}, MCP集成: {}",
                    context.size(), collaborationStrategy.getType(), collaborationStrategy.getExpectedToolCount(),
                    context.get("mcpToolsReady"));

        } catch (Exception e) {
            log.warn("构建智能协作上下文时出现错误", e);
            // 确保至少有基础上下文
            context.put("userId", userId);
            context.put("currentTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            context.put("contextError", "构建上下文时出现部分错误: " + e.getMessage());
            context.put("mcpToolsReady", false);
        }

        return context;
    }

public String determineQueryScenario(String userQuery) {
        return aiModelConfig.recommendScenario(userQuery);
    }

public Map<String, Object> buildMCPToolsIntegrationInfo() {
        Map<String, Object> toolsInfo = new HashMap<>();

        try {
            // 从MCPToolRegistry获取所有可用工具
            List<Map<String, Object>> availableTools = mcpToolRegistry.getAllToolsForAI();
            toolsInfo.put("totalToolsCount", availableTools.size());
            toolsInfo.put("toolsReady", true);

            // 提取工具名称和描述
            Map<String, String> toolCapabilities = new HashMap<>();
            for (Map<String, Object> tool : availableTools) {
                if (tool.containsKey("function")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> function = (Map<String, Object>) tool.get("function");
                    String name = (String) function.get("name");
                    String description = (String) function.get("description");
                    toolCapabilities.put(name, description);
                }
            }
            toolsInfo.put("toolCapabilities", toolCapabilities);

            // 工具使用统计（可选）
            toolsInfo.put("integrationTimestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        } catch (Exception e) {
            log.warn("构建MCP工具集成信息失败", e);
            toolsInfo.put("toolsReady", false);
            toolsInfo.put("error", e.getMessage());
        }

        return toolsInfo;
    }

public void buildEnhancedSessionContext(Long userId, AIQueryRequest request, Map<String, Object> context, String userQuery) {
        try {
            // 获取活跃会话信息
            try {
                Optional<EEGSession> activeSession = sessionService.getActiveSession(userId);
                if (activeSession.isPresent()) {
                    EEGSession session = activeSession.get();
                    Map<String, Object> sessionInfo = buildSessionContextInfo(session);
                    sessionInfo.put("isCurrentlyActive", true);
                    sessionInfo.put("realTimeDuration", session.calculateDurationSeconds());
                    sessionInfo.put("selectionReason", "当前活跃会话");

                    context.put("activeSession", sessionInfo);
                    context.put("hasActiveSession", true);

                    log.info("找到活跃会话 - ID: {}, 开始时间: {}",
                            session.getId(), session.getSessionStartTimeUtc());
                } else {
                    context.put("hasActiveSession", false);
                    log.debug("用户 {} 没有活跃会话", userId);
                }
            } catch (Exception e) {
                log.debug("获取活跃会话失败", e);
                context.put("hasActiveSession", false);
            }

            // 修复：获取最近会话历史，确保按正确顺序排列
            try {
                // 先尝试获取真正最新的会话
                Optional<EEGSession> mostRecentSession = sessionService.getUserMostRecentSession(userId);
                if (mostRecentSession.isPresent()) {
                    EEGSession latestSession = mostRecentSession.get();
                    Map<String, Object> latestSessionInfo = buildSessionContextInfo(latestSession);
                    latestSessionInfo.put("isLatestSession", true);
                    latestSessionInfo.put("selectionReason", "按创建时间最新的会话");

                    context.put("latestSession", latestSessionInfo);
                    context.put("hasLatestSession", true);

                    log.info("找到最新会话 - ID: {}, 创建时间: {}, 状态: {}",
                            latestSession.getId(), latestSession.getCreatedAt(), latestSession.getSessionStatus());
                }

                // 获取最近会话列表
                List<EEGSession> recentSessions = sessionService.getUserSessionHistory(userId, 5);
                if (!recentSessions.isEmpty()) {
                    List<Map<String, Object>> sessionSummary = recentSessions.stream()
                            .map(session -> {
                                Map<String, Object> info = buildSessionContextInfo(session);
                                // 标记最新的会话
                                info.put("isNewest", session.getId().equals(recentSessions.get(0).getId()));
                                return info;
                            })
                            .limit(5)
                            .toList();

                    context.put("recentSessions", sessionSummary);
                    context.put("recentSessionsCount", sessionSummary.size());

                    // 明确指出哪个是最新会话
                    EEGSession newestSession = recentSessions.get(0);
                    context.put("newestSessionId", newestSession.getId());
                    context.put("newestSessionInfo", Map.of(
                            "id", newestSession.getId(),
                            "createdAt", newestSession.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            "status", newestSession.getSessionStatus().toString(),
                            "duration", newestSession.calculateDurationSeconds()
                    ));

                    log.info("最近会话列表构建完成 - 最新会话ID: {}, 共{}个会话",
                            newestSession.getId(), sessionSummary.size());
                } else {
                    context.put("recentSessionsCount", 0);
                    context.put("noSessionsFound", true);
                    log.warn("用户 {} 没有找到任何会话", userId);
                }
            } catch (Exception e) {
                log.error("获取会话历史失败 - 用户ID: {}", userId, e);
                context.put("recentSessionsCount", 0);
            }

            // 获取用户统计信息
            try {
                EEGSessionService.SessionStatistics stats = sessionService.getUserSessionStatistics(userId);
                context.put("userStats", Map.of(
                        "totalSessions", stats.totalSessions,
                        "completedSessions", stats.completedSessions,
                        "activeSessions", stats.activeSessions,
                        "avgDurationSeconds", stats.avgDurationSeconds,
                        "hasAnyData", stats.totalSessions > 0,
                        "dataQuality", assessOverallDataQuality(stats)
                ));
            } catch (Exception e) {
                log.debug("获取用户统计失败", e);
                context.put("userStats", Map.of("hasAnyData", false));
            }

        } catch (Exception e) {
            log.warn("构建会话上下文时出现错误", e);
            context.put("sessionContextError", "构建会话上下文失败: " + e.getMessage());
        }
    }

public void buildToolSelectionGuidance(Map<String, Object> context, CollaborationStrategy strategy, String userQuery) {
        Map<String, Object> guidance = new HashMap<>();

        guidance.put("collaborationType", strategy.getType());
        guidance.put("expectedToolRange", strategy.getMinTools() + "-" + strategy.getMaxTools());
        guidance.put("executionMode", strategy.getExecutionMode());
        guidance.put("requiredTools", strategy.getRequiredTools());

        // 智能工具推荐 - 基于查询内容
        List<String> recommendedTools = aiQueryStrategyService.generateToolRecommendations(userQuery, strategy);
        guidance.put("recommendedTools", recommendedTools);

        // 协作模式建议
        guidance.put("collaborationPattern", aiQueryStrategyService.determineCollaborationPattern(strategy));

        // 工具使用优先级指导
        guidance.put("toolPriorities", Map.of(
                "PRIMARY_FIRST", "优先使用主要工具获取核心数据",
                "SECONDARY_SUPPORT", "使用次要工具提供支撑分析",
                "AUXILIARY_DETAIL", "使用辅助工具获取细节信息",
                "SPECIALIZED_ADVANCED", "使用专业工具进行高级分析"
        ));

        context.put("toolSelectionGuidance", guidance);
    }

public void buildDataProcessingStrategy(Long userId, Map<String, Object> context, QueryComplexityAnalysis complexity) {
        Map<String, Object> strategy = new HashMap<>();

        try {
            // 根据复杂度确定处理策略
            switch (complexity.getLevel()) {
                case VERY_HIGH:
                    strategy.put("preferredApproach", "multi_tool_orchestration");
                    strategy.put("dataType", "comprehensive_multi_domain");
                    strategy.put("samplingStrategy", "intelligent_hierarchical");
                    strategy.put("allowCustomSQL", true);
                    break;
                case HIGH:
                    strategy.put("preferredApproach", "coordinated_analysis");
                    strategy.put("dataType", "multi_dimensional");
                    strategy.put("samplingStrategy", "adaptive_sampling");
                    strategy.put("allowCustomSQL", true);
                    break;
                case MEDIUM:
                    strategy.put("preferredApproach", "dual_tool_collaboration");
                    strategy.put("dataType", "focused_analysis");
                    strategy.put("samplingStrategy", "targeted_sampling");
                    strategy.put("allowCustomSQL", false);
                    break;
                case LOW:
                case VERY_LOW:
                default:
                    strategy.put("preferredApproach", "direct_tool_execution");
                    strategy.put("dataType", "specific_query");
                    strategy.put("samplingStrategy", "efficient_direct");
                    strategy.put("allowCustomSQL", false);
                    break;
            }

            strategy.put("performanceOptimization", generatePerformanceHints(context));

            context.put("dataProcessingStrategy", strategy);

        } catch (Exception e) {
            log.warn("构建数据处理策略失败", e);
            context.put("strategyError", "数据处理策略构建失败");
        }
    }

public void buildPerformanceOptimizationConfig(Map<String, Object> context, CollaborationStrategy strategy) {
        Map<String, Object> config = new HashMap<>();

        config.put("collaborationType", strategy.getType());
        config.put("expectedToolCount", strategy.getExpectedToolCount());
        config.put("executionMode", strategy.getExecutionMode());

        // 根据协作类型设置优化参数
        switch (strategy.getType()) {
            case MULTI_TOOL_RESEARCH:
                config.put("enableCaching", true);
                config.put("enableSmartSampling", true);
                config.put("timeoutMultiplier", 2.0);
                break;
            case MULTI_TOOL_ANALYSIS:
                config.put("enableCaching", true);
                config.put("enableSmartSampling", true);
                config.put("timeoutMultiplier", 1.5);
                break;
            case DUAL_TOOL_COMBO:
                config.put("enableCaching", true);
                config.put("enableSmartSampling", false);
                config.put("timeoutMultiplier", 1.2);
                break;
            default:
                config.put("enableCaching", false);
                config.put("enableSmartSampling", false);
                config.put("timeoutMultiplier", 1.0);
                break;
        }

        context.put("performanceOptimization", config);
    }

public Map<String, Object> buildSessionContextInfo(EEGSession session) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", session.getId());
        info.put("startTimeUtc", session.getSessionStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        info.put("endTimeUtc", session.getSessionEndTimeUtc() != null ?
                session.getSessionEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        info.put("createdAt", session.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)); // 添加创建时间
        info.put("durationSeconds", session.calculateDurationSeconds());
        info.put("status", session.getSessionStatus().toString());
        info.put("isCompleted", session.getSessionStatus() == EEGSession.SessionStatus.COMPLETED);

        // 数据统计
        long totalPackets = (session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0) +
                (session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0) +
                (session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0);
        info.put("totalDataPackets", totalPackets);
        info.put("hasData", totalPackets > 0);

        // 添加更详细的时间信息用于AI判断
        info.put("timeContext", Map.of(
                "createdAtTimestamp", session.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC),
                "isRecent", java.time.Duration.between(session.getCreatedAt(), LocalDateTime.now()).toDays() < 7,
                "ageInHours", java.time.Duration.between(session.getCreatedAt(), LocalDateTime.now()).toHours()
        ));

        return info;
    }

public Long extractEegSessionId(Map<String, Object> context) {
        if (context == null) return null;

        // 尝试从活跃会话中提取
        if (context.containsKey("activeSession")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> activeSession = (Map<String, Object>) context.get("activeSession");
            if (activeSession != null && activeSession.containsKey("id")) {
                try {
                    return ((Number) activeSession.get("id")).longValue();
                } catch (Exception e) {
                    log.debug("无法解析活跃会话ID", e);
                }
            }
        }

        return null;
    }

public Map<String, Object> generatePerformanceHints(Map<String, Object> context) {
        Map<String, Object> hints = new HashMap<>();

        try {
            // 根据用户统计信息提供性能提示
            if (context.containsKey("userStats")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> stats = (Map<String, Object>) context.get("userStats");

                long totalSessions = ((Number) stats.get("totalSessions")).longValue();

                if (totalSessions > 100) {
                    hints.put("recommendCaching", true);
                    hints.put("useSampling", true);
                    hints.put("reason", "大量会话数据，建议启用缓存和采样策略");
                } else if (totalSessions > 50) {
                    hints.put("recommendCaching", false);
                    hints.put("useSampling", false);
                    hints.put("reason", "中等规模数据，使用标准处理策略");
                } else {
                    hints.put("recommendCaching", false);
                    hints.put("useSampling", false);
                    hints.put("reason", "小规模数据，无需优化策略");
                }

                hints.put("totalSessionsAnalyzed", totalSessions);
            }
        } catch (Exception e) {
            log.warn("生成性能提示时出现异常", e);
            hints.put("error", "性能提示生成失败，使用默认策略");
            hints.put("recommendCaching", false);
            hints.put("useSampling", false);
        }

        return hints;
    }

public List<String> extractToolsUsed(AIResponse aiResponse) {
        if (aiResponse.toolResults() == null || aiResponse.toolResults().isEmpty()) {
            return new ArrayList<>();
        }

        return aiResponse.toolResults().stream()
                .map(toolResult -> toolResult.functionName())
                .distinct()
                .collect(Collectors.toList());
    }

public Map<String, Object> analyzeEnhancedToolCollaboration(AIResponse aiResponse) {
        Map<String, Object> stats = new HashMap<>();

        try {
            if (aiResponse.toolResults() == null || aiResponse.toolResults().isEmpty()) {
                stats.put("collaborationType", "no_tools_used");
                stats.put("toolCount", 0);
                stats.put("collaborationEfficiency", 0.0);
                stats.put("qualityAssessment", "no_tools");
                return stats;
            }

            List<String> toolsUsed = aiResponse.toolResults().stream()
                    .map(tr -> tr.functionName())
                    .distinct()
                    .collect(Collectors.toList());

            stats.put("toolCount", toolsUsed.size());
            stats.put("toolsUsed", toolsUsed);

            // 分析协作类型
            if (toolsUsed.size() == 1) {
                stats.put("collaborationType", "single_tool");
            } else if (toolsUsed.size() == 2) {
                stats.put("collaborationType", "dual_tool_collaboration");
            } else if (toolsUsed.size() >= 3 && toolsUsed.size() <= 5) {
                stats.put("collaborationType", "multi_tool_analysis");
            } else {
                stats.put("collaborationType", "complex_orchestration");
            }

            // 分析工具层级分布
            Map<String, Integer> tierDistribution = new HashMap<>();
            toolsUsed.forEach(tool -> {
                String tier = aiQueryStrategyService.getToolTier(tool);
                tierDistribution.put(tier, tierDistribution.getOrDefault(tier, 0) + 1);
            });
            stats.put("tierDistribution", tierDistribution);

            // 协作效率评估
            double collaborationEfficiency = calculateCollaborationEfficiency(toolsUsed);
            stats.put("collaborationEfficiency", collaborationEfficiency);

            // 协作质量评估
            String qualityAssessment = assessCollaborationQuality(collaborationEfficiency);
            stats.put("qualityAssessment", qualityAssessment);

            // MCP工具特定分析
            stats.put("mcpToolsUsed", toolsUsed.size());
            stats.put("primaryToolsUsed", toolsUsed.stream()
                    .filter(tool -> AIQueryStrategyService.MCP_TOOL_CATEGORIES.get("PRIMARY_TOOLS").contains(tool))
                    .count());
            stats.put("specializedToolsUsed", toolsUsed.stream()
                    .filter(tool -> AIQueryStrategyService.MCP_TOOL_CATEGORIES.get("SPECIALIZED_TOOLS").contains(tool))
                    .count());

            return stats;

        } catch (Exception e) {
            log.error("分析增强版工具协作情况时出错", e);
            stats.put("collaborationType", "analysis_error");
            stats.put("toolCount", 0);
            stats.put("collaborationEfficiency", 0.0);
            stats.put("qualityAssessment", "error");
            stats.put("error", e.getMessage());
            return stats;
        }
    }



public String assessOverallDataQuality(EEGSessionService.SessionStatistics stats) {
        if (stats.totalSessions == 0) return "无数据";

        double completionRate = (double) stats.completedSessions / stats.totalSessions;
        if (completionRate > 0.8 && stats.avgDurationSeconds > 300) {
            return "优秀";
        } else if (completionRate > 0.6 && stats.avgDurationSeconds > 180) {
            return "良好";
        } else if (completionRate > 0.4) {
            return "一般";
        } else {
            return "需要改善";
        }
    }

public double calculateCollaborationEfficiency(List<String> toolsUsed) {
        double efficiency = 0.0;

        // 主工具存在奖励
        if (toolsUsed.stream().anyMatch(tool -> AIQueryStrategyService.MCP_TOOL_CATEGORIES.get("PRIMARY_TOOLS").contains(tool))) {
            efficiency += 0.3;
        }

        // 工具多样性奖励
        Set<String> categories = new HashSet<>();
        toolsUsed.forEach(tool -> {
            for (Map.Entry<String, Set<String>> entry : AIQueryStrategyService.MCP_TOOL_CATEGORIES.entrySet()) {
                if (entry.getValue().contains(tool)) {
                    categories.add(entry.getKey());
                    break;
                }
            }
        });
        efficiency += categories.size() * 0.15;

        // 工具数量平衡性
        int toolCount = toolsUsed.size();
        if (toolCount >= 2 && toolCount <= 4) {
            efficiency += 0.25;
        } else if (toolCount == 1 || toolCount == 5) {
            efficiency += 0.15;
        } else {
            efficiency += 0.05;
        }

        // 特殊组合奖励
        if (toolsUsed.contains("getActiveSessionContext") && toolsUsed.contains("monitorSignalQuality")) {
            efficiency += 0.1;
        }
        if (toolsUsed.contains("getSessionHistory") && toolsUsed.contains("compareSessionDataQuality")) {
            efficiency += 0.1;
        }
        if (toolsUsed.contains("assessSessionDataVolume") && toolsUsed.contains("generateComprehensiveSessionSummary")) {
            efficiency += 0.1;
        }

        return Math.min(1.0, efficiency);
    }

public String assessCollaborationQuality(double efficiency) {
        if (efficiency >= 0.8) {
            return "excellent";
        } else if (efficiency >= 0.6) {
            return "good";
        } else if (efficiency >= 0.4) {
            return "fair";
        } else {
            return "needs_improvement";
        }
    }


}