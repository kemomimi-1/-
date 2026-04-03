package com.eeg.service.ai;

import com.eeg.entity.ai.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@lombok.extern.slf4j.Slf4j
public class AIQueryStrategyService {

// 工具协作模式识别
    public static final Map<String, Pattern> COLLABORATION_PATTERNS = Map.of(
            "COMPARISON", Pattern.compile("(?i)(对比|比较|差异|区别|哪个更好|最优|最佳)", Pattern.CASE_INSENSITIVE),
            "COMPREHENSIVE", Pattern.compile("(?i)(全面|详细|深入|完整|综合|深度|彻底)", Pattern.CASE_INSENSITIVE),
            "QUALITY_FOCUS", Pattern.compile("(?i)(质量|稳定|噪声|干扰|可靠|准确)", Pattern.CASE_INSENSITIVE),
            "TEMPORAL_ANALYSIS", Pattern.compile("(?i)(趋势|变化|时间|历史|发展|演变)", Pattern.CASE_INSENSITIVE),
            "TECHNICAL_DEEP_DIVE", Pattern.compile("(?i)(技术|参数|规格|配置|采样|通道)", Pattern.CASE_INSENSITIVE),
            "RESEARCH_ORIENTED", Pattern.compile("(?i)(研究|分析|评估|调查|探索|发现)", Pattern.CASE_INSENSITIVE)
    );

// 15个核心工具分类 - 与MCPToolRegistry完全对应
    public static final Map<String, Set<String>> MCP_TOOL_CATEGORIES = Map.of(
            "PRIMARY_TOOLS", Set.of(
                    "getActiveSessionContext", "queryLatestBandPowerData", "generateComprehensiveSessionSummary"
            ),
            "SECONDARY_TOOLS", Set.of(
                    "getSessionDetails", "monitorSignalQuality", "getUserStatistics"
            ),
            "AUXILIARY_TOOLS", Set.of(
                    "queryRawEEGData", "queryFilteredEEGData", "assessSessionDataVolume"
            ),
            "SPECIALIZED_TOOLS", Set.of(
                    "compareSessionDataQuality", "querySessionsByConditions",
                    "getSessionTechnicalSpecs", "getSessionHistory"
            ),
            "AI_AUTONOMOUS_TOOLS", Set.of("executeCustomQuery"),
            "TIME_QUERY_TOOLS", Set.of("queryDataByTimeRange")  // 新增时间查询
    );

public CollaborationStrategy determineEnhancedCollaborationStrategy(String userQuery, QueryComplexityAnalysis complexity) {
        CollaborationStrategy strategy = new CollaborationStrategy();
        String query = userQuery.toLowerCase();

        // 根据关键词模式确定协作类型
        for (Map.Entry<String, Pattern> entry : COLLABORATION_PATTERNS.entrySet()) {
            if (entry.getValue().matcher(query).find()) {
                strategy.addCollaborationType(entry.getKey());
            }
        }

        // 根据复杂度确定工具数量和执行模式
        switch (complexity.getLevel()) {
            case VERY_HIGH:
                strategy.setExpectedToolCount(5, 10);
                strategy.setExecutionMode(ExecutionMode.MIXED);
                strategy.setType(CollaborationType.MULTI_TOOL_RESEARCH);
                break;
            case HIGH:
                strategy.setExpectedToolCount(3, 6);
                strategy.setExecutionMode(ExecutionMode.SEQUENTIAL);
                strategy.setType(CollaborationType.MULTI_TOOL_ANALYSIS);
                break;
            case MEDIUM:
                strategy.setExpectedToolCount(2, 4);
                strategy.setExecutionMode(ExecutionMode.SEQUENTIAL);
                strategy.setType(CollaborationType.DUAL_TOOL_COMBO);
                break;
            case LOW:
            case VERY_LOW:
            default:
                strategy.setExpectedToolCount(1, 2);
                strategy.setExecutionMode(ExecutionMode.SINGLE);
                strategy.setType(CollaborationType.SINGLE_TOOL_DIRECT);
                break;
        }

        // 特殊场景工具推荐 - 基于15个核心工具
        addIntelligentToolRecommendations(strategy, query);

        return strategy;
    }

public List<String> generateToolRecommendations(String userQuery, CollaborationStrategy strategy) {
        List<String> recommendations = new ArrayList<>();
        String query = userQuery.toLowerCase();

        // 基于查询内容的智能推荐
        if (containsAny(query, "最新", "现在", "当前")) {
            recommendations.add("getActiveSessionContext");
            if (containsAny(query, "频谱", "频段", "功率")) {
                recommendations.add("queryLatestBandPowerData");
            }
        }

        if (containsAny(query, "对比", "比较")) {
            recommendations.add("getSessionHistory");
            recommendations.add("compareSessionDataQuality");
        }

        if (containsAny(query, "全面", "详细", "深入")) {
            recommendations.add("assessSessionDataVolume");
            recommendations.add("generateComprehensiveSessionSummary");
        }

        if (containsAny(query, "质量", "稳定")) {
            recommendations.add("monitorSignalQuality");
        }

        if (containsAny(query, "技术", "参数", "规格")) {
            recommendations.add("getSessionTechnicalSpecs");
        }

        if (containsAny(query, "筛选", "条件", "符合")) {
            recommendations.add("querySessionsByConditions");
        }

        if (containsAny(query, "历史", "记录", "所有")) {
            recommendations.add("getSessionHistory");
        }

        if (containsAny(query, "原始", "时间序列")) {
            recommendations.add("queryRawEEGData");
        }

        if (containsAny(query, "滤波", "清洁")) {
            recommendations.add("queryFilteredEEGData");
        }

        if (containsAny(query, "统计", "总计", "平均")) {
            recommendations.add("getUserStatistics");
        }

        if (containsAny(query, "时间", "范围")) {
            recommendations.add("queryDataByTimeRange");
        }

        // 确保推荐数量符合策略
        if (recommendations.size() < strategy.getMinTools()) {
            if (!recommendations.contains("getUserStatistics")) {
                recommendations.add("getUserStatistics");
            }
            if (!recommendations.contains("getSessionDetails") && strategy.getMinTools() > 2) {
                recommendations.add("getSessionDetails");
            }
        }

        // 如果标准工具无法满足，推荐自定义SQL
        if (containsAny(query, "复杂", "特殊", "自定义") || strategy.getType() == CollaborationType.MULTI_TOOL_RESEARCH) {
            recommendations.add("executeCustomQuery");
        }

        return recommendations.stream().distinct().limit(strategy.getMaxTools()).collect(Collectors.toList());
    }

public String determineCollaborationPattern(CollaborationStrategy strategy) {
        return switch (strategy.getType()) {
            case SINGLE_TOOL_DIRECT -> "direct_execution";
            case DUAL_TOOL_COMBO -> "sequential_collaboration";
            case MULTI_TOOL_ANALYSIS -> "coordinated_analysis";
            case MULTI_TOOL_RESEARCH -> "research_orchestration";
            default -> "adaptive_collaboration";
        };
    }

public QueryComplexityAnalysis analyzeQueryComplexity(String userQuery) {
        QueryComplexityAnalysis analysis = new QueryComplexityAnalysis();
        String query = userQuery.toLowerCase();

        // 复杂度指标计算
        int complexityScore = 0;

        // 关键词复杂度
        if (containsAny(query, "全面", "详细", "深入", "完整", "综合")) complexityScore += 3;
        if (containsAny(query, "对比", "比较", "分析", "评估")) complexityScore += 2;
        if (containsAny(query, "所有", "全部", "历史", "趋势")) complexityScore += 2;
        if (containsAny(query, "质量", "稳定", "技术", "参数")) complexityScore += 1;

        // 数量词复杂度
        if (query.matches(".*\\d+.*")) complexityScore += 1;
        if (containsAny(query, "几个", "多个", "各种", "不同")) complexityScore += 2;

        // 时间词复杂度
        if (containsAny(query, "最近", "历史", "变化", "趋势", "发展")) complexityScore += 1;

        // 问句复杂度
        long questionMarks = query.chars().filter(ch -> ch == '？' || ch == '?').count();
        if (questionMarks > 1) complexityScore += 2;

        // 确定复杂度级别
        if (complexityScore >= 8) {
            analysis.setLevel(ComplexityLevel.VERY_HIGH);
            analysis.setDescription("超高复杂度查询，需要多工具深度协作");
        } else if (complexityScore >= 6) {
            analysis.setLevel(ComplexityLevel.HIGH);
            analysis.setDescription("高复杂度查询，需要多工具协作");
        } else if (complexityScore >= 4) {
            analysis.setLevel(ComplexityLevel.MEDIUM);
            analysis.setDescription("中等复杂度查询，可能需要双工具协作");
        } else if (complexityScore >= 2) {
            analysis.setLevel(ComplexityLevel.LOW);
            analysis.setDescription("低复杂度查询，单工具可能足够");
        } else {
            analysis.setLevel(ComplexityLevel.VERY_LOW);
            analysis.setDescription("极简查询，单工具直接解决");
        }

        analysis.setScore(complexityScore);
        analysis.setKeywords(extractKeywords(userQuery));

        return analysis;
    }

public boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

public List<String> extractKeywords(String query) {
        return Arrays.asList(query.toLowerCase().split("\\s+"));
    }

public String getToolTier(String toolName) {
        for (Map.Entry<String, Set<String>> entry : MCP_TOOL_CATEGORIES.entrySet()) {
            if (entry.getValue().contains(toolName)) {
                return entry.getKey().toLowerCase();
            }
        }
        return "other";
    }

public String getToolCategory(String toolName) {
        for (Map.Entry<String, Set<String>> entry : MCP_TOOL_CATEGORIES.entrySet()) {
            if (entry.getValue().contains(toolName)) {
                return entry.getKey();
            }
        }
        return "OTHER";
    }



    public void addIntelligentToolRecommendations(CollaborationStrategy strategy, String query) {
        // 实时状态查询
        if (this.containsAny(query, "当前", "现在", "正在", "活跃", "实时")) {
            strategy.addRequiredTool("getActiveSessionContext");
        }

        // 频谱数据查询
        if (this.containsAny(query, "最新", "频谱", "频段", "功率", "alpha", "beta", "theta", "delta", "gamma")) {
            strategy.addRequiredTool("queryLatestBandPowerData");
        }

        // 质量相关查询
        if (this.containsAny(query, "质量", "稳定", "噪声", "干扰", "可靠")) {
            strategy.addRequiredTool("monitorSignalQuality");
        }

        // 对比分析查询
        if (this.containsAny(query, "对比", "比较", "哪个", "最好", "差异")) {
            strategy.addRequiredTool("compareSessionDataQuality");
            strategy.addRequiredTool("getSessionHistory");
        }

        // 历史数据查询
        if (this.containsAny(query, "历史", "记录", "所有会话", "以前")) {
            strategy.addRequiredTool("getSessionHistory");
        }

        // 技术规格查询
        if (this.containsAny(query, "技术", "参数", "规格", "配置", "采样")) {
            strategy.addRequiredTool("getSessionTechnicalSpecs");
        }

        // 综合分析查询
        if (this.containsAny(query, "全面", "详细", "深入", "完整", "综合")) {
            strategy.addRequiredTool("generateComprehensiveSessionSummary");
            strategy.addRequiredTool("assessSessionDataVolume");
        }

        // 原始数据查询
        if (this.containsAny(query, "原始", "raw", "时间序列")) {
            strategy.addRequiredTool("queryRawEEGData");
        }

        // 滤波数据查询
        if (this.containsAny(query, "滤波", "filtered", "清洁")) {
            strategy.addRequiredTool("queryFilteredEEGData");
        }

        // 统计信息查询
        if (this.containsAny(query, "统计", "总计", "平均", "使用情况")) {
            strategy.addRequiredTool("getUserStatistics");
        }

        // 条件筛选查询
        if (this.containsAny(query, "筛选", "条件", "符合", "满足")) {
            strategy.addRequiredTool("querySessionsByConditions");
        }

        // 自定义查询
        if (this.containsAny(query, "复杂", "自定义", "特殊", "sql")) {
            strategy.addRequiredTool("executeCustomQuery");
        }
    }

    
}
