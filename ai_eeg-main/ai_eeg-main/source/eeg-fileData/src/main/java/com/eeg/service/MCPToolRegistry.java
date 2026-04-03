package com.eeg.service;

import com.eeg.service.mcp.*;
import com.eeg.service.mcp.MCPToolModels.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * MCP工具注册表 - 专注EEG数据分析核心科研需求
 * 重构后仅保留工具注册和调度逻辑，具体执行委托给各 Executor
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MCPToolRegistry {

    // ========== 执行器注入 ==========
    private final MCPDataQueryExecutor dataQueryExecutor;
    private final MCPSignalQualityExecutor signalQualityExecutor;
    private final MCPSessionExecutor sessionExecutor;
    private final MCPBigDataExecutor bigDataExecutor;

    private final Map<String, MCPTool> registeredTools = new HashMap<>();

    @PostConstruct
    public void initializeTools() {
        log.info("初始化优化版MCP工具注册表 - 专注EEG科研核心需求");

        // 注册核心会话管理工具
        registerCoreSessionTools();

        // 注册核心EEG数据查询工具
        registerCoreDataQueryTools();

        // 注册核心质量控制工具
        registerCoreQualityTools();

        // 注册核心大数据处理工具
        registerCoreBigDataTools();

        // 注册AI自主查询工具
        registerAIQueryTool();

        // 注册新的会话分析工具
        registerSessionAnalysisTools();

        // 【新增】注册时间查询工具
        registerTimeQueryTools();

        log.info("优化版MCP工具注册完成，共注册 {} 个核心工具", registeredTools.size());
        registeredTools.keySet().forEach(toolName -> log.info("核心工具: {}", toolName));
    }

    /**
     * 注册核心会话管理工具（2个）
     */
    private void registerCoreSessionTools() {
        // 1. 获取活跃会话上下文 - 最重要的会话工具
        registeredTools.put("getActiveSessionContext", new MCPTool(
                "getActiveSessionContext",
                "获取当前活跃会话的上下文信息",
                "智能识别用户当前正在进行的会话，包括实时状态、数据流状态、实时统计等信息。这是AI分析用户当前EEG数据的核心工具。",
                Map.of(
                        "includeDataStats", new ToolParameter("boolean", "包含数据统计", "是否包含实时数据统计信息", false, "true")
                ),
                sessionExecutor::executeGetActiveSessionContext
        ));

        // 2. 获取特定会话详情
        registeredTools.put("getSessionDetails", new MCPTool(
                "getSessionDetails",
                "获取指定会话的详细信息",
                "根据会话ID获取特定EEG数据会话的详细信息，包括精确的开始时间、结束时间、持续时长、数据包统计等",
                Map.of(
                        "sessionId", new ToolParameter("integer", "会话ID", "要查询的EEG会话ID", true)
                ),
                sessionExecutor::executeGetSessionDetails
        ));
    }

    /**
     * 注册核心EEG数据查询工具（3个）
     */
    private void registerCoreDataQueryTools() {
        // 3. 查询最新频段功率数据 - 频域分析核心
        registeredTools.put("queryLatestBandPowerData", new MCPTool(
                "queryLatestBandPowerData",
                "查询最新的EEG频段功率数据 - 完全透明化科研版",
                "专门用于查询用户最新的脑电频谱数据，基于Welch功率谱密度估计方法，提供完整的查询透明度信息、原始数据样本、学术级统计分析和频段生物学意义解释。这是EEG频域分析的核心工具，确保所有分析结论都有真实数据支撑。",
                Map.of(
                        "limit", new ToolParameter("integer", "数据条数", "返回最新数据的条数，默认10", false, "10"),
                        "sessionId", new ToolParameter("integer", "会话ID", "指定会话ID，如不指定则查询最新会话", false),
                        "bands", new ToolParameter("array", "频段列表", "指定频段，如['alpha', 'beta']，默认所有频段", false),
                        "groupByTime", new ToolParameter("boolean", "按时间分组", "是否按时间点分组显示所有频段", false, "true")
                ),
                dataQueryExecutor::executeQueryLatestBandPowerData
        ));

        // 4. 查询原始EEG数据 - 完全透明化科研版
        registeredTools.put("queryRawEEGData", new MCPTool(
                "queryRawEEGData",
                "查询原始EEG时间序列数据 - 透明化科研版",
                "直接从InfluxDB查询用户的原始EEG数据，支持按时间、通道等条件筛选。提供完整的查询透明度信息、采样参数说明、时间序列统计分析和通道映射信息。这是EEG时域分析的基础工具，确保用户了解数据的真实来源。",
                Map.of(
                        "sessionId", new ToolParameter("integer", "会话ID", "要查询的会话ID，用于确定时间范围", false),
                        "channels", new ToolParameter("array", "通道列表", "要查询的通道数组，如[1,2,3]", false),
                        "limit", new ToolParameter("integer", "数据条数", "返回的数据行数限制，默认100", false, "100"),
                        "orderBy", new ToolParameter("string", "排序方式", "数据排序: 'time ASC' 或 'time DESC'", false, "time DESC")
                ),
                dataQueryExecutor::executeQueryRawEEGData
        ));

        // 5. 查询滤波后EEG数据 - 完全透明化科研版
        registeredTools.put("queryFilteredEEGData", new MCPTool(
                "queryFilteredEEGData",
                "查询滤波后的EEG时间序列数据 - 透明化科研版",
                "查询经过滤波处理的EEG数据，降噪后更适合分析。提供完整的滤波处理透明度信息、信号处理方法说明、数据质量评估和滤波效果分析。支持与原始数据相同的筛选条件，确保分析的科学性。",
                Map.of(
                        "sessionId", new ToolParameter("integer", "会话ID", "要查询的会话ID，用于确定时间范围", false),
                        "channels", new ToolParameter("array", "通道列表", "要查询的通道数组", false),
                        "limit", new ToolParameter("integer", "数据条数", "返回的数据行数限制，默认100", false, "100"),
                        "orderBy", new ToolParameter("string", "排序方式", "数据排序方式", false, "time DESC")
                ),
                dataQueryExecutor::executeQueryFilteredEEGData
        ));
    }

    /**
     * 注册核心质量控制工具（2个）
     */
    private void registerCoreQualityTools() {
        // 6. 信号质量监测 - 基于IEEE标准的透明化版本
        registeredTools.put("monitorSignalQuality", new MCPTool(
                "monitorSignalQuality",
                "实时信号质量监测 - IEEE标准透明化版",
                "监测当前会话的EEG信号质量，基于IEEE标准进行科学评估，包括噪声水平、数据稳定性、异常检测等。提供完整的分析透明度信息，包括执行的SQL查询、统计方法说明、质量标准参考和计算过程详解。确保用户了解信号质量评估的科学依据。",
                Map.of(
                        "sessionId", new ToolParameter("integer", "会话ID", "指定会话ID", false),
                        "channels", new ToolParameter("array", "监测通道", "指定要监测的通道", false),
                        "timeWindow", new ToolParameter("integer", "时间窗口", "监测最近N秒的数据", false, "30")
                ),
                signalQualityExecutor::executeMonitorSignalQuality
        ));

        // 7. 用户统计信息
        registeredTools.put("getUserStatistics", new MCPTool(
                "getUserStatistics",
                "获取用户的EEG数据使用统计",
                "查询用户的总体使用统计，包括总会话数、平均时长、数据量等基础信息",
                Map.of(),
                sessionExecutor::executeGetUserStatistics
        ));
    }



    /**
     * 注册核心大数据处理工具（2个）
     */
    private void registerCoreBigDataTools() {
        // 8. 全面会话摘要 - 大数据量优化版
        registeredTools.put("generateComprehensiveSessionSummary", new MCPTool(
                "generateComprehensiveSessionSummary",
                "生成全面的EEG会话数据摘要分析",
                "针对大数据量会话生成多层次、多维度的数据摘要，包括频域、时域、空间和质量分析。采用优化算法避免内存溢出，适合处理长时间记录的EEG数据。",
                Map.of(
                        "sessionId", new ToolParameter("integer", "会话ID", "要分析的EEG会话ID", true),
                        "analysisLevel", new ToolParameter("string", "分析级别", "分析深度：basic（基础）、standard（标准）、comprehensive（全面）", false, "comprehensive"),
                        "includeTemporalAnalysis", new ToolParameter("boolean", "包含时序分析", "是否包含时间模式分析", false, "true"),
                        "includeFrequencyAnalysis", new ToolParameter("boolean", "包含频域分析", "是否包含频谱特征分析", false, "true"),
                        "includeSpatialAnalysis", new ToolParameter("boolean", "包含空间分析", "是否包含电极间相关性分析", false, "true"),
                        "includeQualityAssessment", new ToolParameter("boolean", "包含质量评估", "是否包含数据质量分析", false, "true")
                ),
                bigDataExecutor::executeGenerateComprehensiveSessionSummary
        ));

        // 9. 数据量评估
        registeredTools.put("assessSessionDataVolume", new MCPTool(
                "assessSessionDataVolume",
                "评估EEG会话数据量和最优处理策略",
                "分析会话的数据量规模，自动选择最优的查询和处理策略，避免大数据量导致的性能问题",
                Map.of(
                        "sessionId", new ToolParameter("integer", "会话ID", "要评估的EEG会话ID", true),
                        "includeRecommendations", new ToolParameter("boolean", "包含建议", "是否包含数据处理建议", false, "true"),
                        "analyzeDataStreams", new ToolParameter("boolean", "分析数据流", "是否分析各数据流的规模", false, "true")
                ),
                bigDataExecutor::executeAssessSessionDataVolume
        ));
    }

    /**
     * 注册AI自主查询工具（1个）
     */
    private void registerAIQueryTool() {
        // 10. 自定义SQL查询 - AI自主决策核心
        registeredTools.put("executeCustomQuery", new MCPTool(
                "executeCustomQuery",
                "执行自定义的EEG数据SQL查询",
                "执行用户自定义的SQL查询语句，仅允许SELECT操作，用于复杂的数据分析需求。这是AI模型自主执行SQL的核心工具。",
                Map.of(
                        "sql", new ToolParameter("string", "SQL查询语句", "要执行的SQL查询语句，仅支持SELECT", true),
                        "sessionId", new ToolParameter("integer", "会话ID", "相关的会话ID，用于安全检查", false),
                        "maxRows", new ToolParameter("integer", "最大行数", "返回结果的最大行数限制", false, "1000")
                ),
                dataQueryExecutor::executeCustomQuery
        ));
    }

    /**
     * 注册会话分析相关工具（4个）
     */
    private void registerSessionAnalysisTools() {
        // 11. 会话数据质量对比工具
        registeredTools.put("compareSessionDataQuality", new MCPTool(
                "compareSessionDataQuality",
                "对比多个会话的数据质量",
                "对比分析多个EEG会话的信号质量、数据完整性和稳定性，帮助用户找出最优质的数据会话。支持2-10个会话的同时对比。",
                Map.of(
                        "sessionIds", new ToolParameter("array", "会话ID列表", "要对比的会话ID数组，至少需要2个会话ID", true)
                ),
                sessionExecutor::executeCompareSessionDataQuality
        ));

        // 12. 按条件查询会话工具
        registeredTools.put("querySessionsByConditions", new MCPTool(
                "querySessionsByConditions",
                "根据条件筛选用户会话",
                "根据持续时间、状态等条件筛选用户的EEG会话，支持复杂的查询条件组合，并提供会话特点分析。",
                Map.of(
                        "minDurationSeconds", new ToolParameter("integer", "最小持续时间", "会话最小持续时间（秒）", false),
                        "maxDurationSeconds", new ToolParameter("integer", "最大持续时间", "会话最大持续时间（秒）", false),
                        "status", new ToolParameter("string", "会话状态", "筛选特定状态的会话：ACTIVE, COMPLETED, INTERRUPTED", false),
                        "limit", new ToolParameter("integer", "返回数量限制", "最多返回的会话数量", false, "100")
                ),
                sessionExecutor::executeQuerySessionsByConditions
        ));

        // 13. 获取会话详细技术规格工具
        registeredTools.put("getSessionTechnicalSpecs", new MCPTool(
                "getSessionTechnicalSpecs",
                "获取会话的详细技术规格参数",
                "获取EEG会话的完整技术参数，包括采样率、通道配置、数据流状态、设备信息等详细技术规格。是getSessionDetails的增强版。",
                Map.of(
                        "sessionId", new ToolParameter("integer", "会话ID", "要查询的EEG会话ID", true),
                        "includeDataSamples", new ToolParameter("boolean", "包含数据样本", "是否包含实际的数据样本", false, "false")
                ),
                sessionExecutor::executeGetSessionTechnicalSpecs
        ));

        // 14. 会话历史查询工具
        registeredTools.put("getSessionHistory", new MCPTool(
                "getSessionHistory",
                "获取用户EEG会话历史记录",
                "获取用户完整的EEG会话历史，支持排序、筛选和统计分析。提供比getUserStatistics更详细的历史记录信息。",
                Map.of(
                        "limit", new ToolParameter("integer", "返回数量", "返回的会话数量限制", false, "20"),
                        "sortBy", new ToolParameter("string", "排序字段", "排序依据：startTime, duration", false, "startTime"),
                        "sortOrder", new ToolParameter("string", "排序方向", "排序方向：ASC, DESC", false, "DESC"),
                        "includeStatistics", new ToolParameter("boolean", "包含统计", "是否包含统计分析信息", false, "true")
                ),
                sessionExecutor::executeGetSessionHistory
        ));


    }

    /**
     * 注册时间查询工具（新增）
     */
    private void registerTimeQueryTools() {
        // 15. 按时间范围查询数据工具
        registeredTools.put("queryDataByTimeRange", new MCPTool(
                "queryDataByTimeRange",
                "根据指定时间点或时间范围查询EEG数据",
                "支持精确时间点、时间范围查询各类EEG数据，包括频段功率、原始数据、滤波数据。自动识别时间格式并提供完整的数据分析。",
                Map.of(
                        "timePoint", new ToolParameter("string", "时间点", "精确时间点，格式如 '2025-09-10T07:58:12' 或 '2025-09-10 07:58:12'", false),
                        "startTime", new ToolParameter("string", "开始时间", "时间范围查询的开始时间", false),
                        "endTime", new ToolParameter("string", "结束时间", "时间范围查询的结束时间", false),
                        "dataType", new ToolParameter("string", "数据类型", "查询的数据类型：bandpower（频段功率）、raw（原始数据）、filtered（滤波数据）", false, "bandpower"),
                        "timeWindow", new ToolParameter("integer", "时间窗口", "围绕时间点的查询窗口（秒），默认30秒", false, "30"),
                        "limit", new ToolParameter("integer", "数据条数", "返回的最大数据条数", false, "50")
                ),
                dataQueryExecutor::executeQueryDataByTimeRange
        ));
    }

    // ========== API方法 ==========

    public List<Map<String, Object>> getAllToolsForAI() {
        List<Map<String, Object>> tools = new ArrayList<>();

        for (MCPTool tool : registeredTools.values()) {
            Map<String, Object> toolDef = new HashMap<>();
            toolDef.put("type", "function");

            Map<String, Object> function = new HashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("type", "object");

            Map<String, Object> properties = new HashMap<>();
            List<String> required = new ArrayList<>();

            for (Map.Entry<String, ToolParameter> entry : tool.parameters().entrySet()) {
                String paramName = entry.getKey();
                ToolParameter param = entry.getValue();

                Map<String, Object> paramDef = new HashMap<>();
                paramDef.put("type", param.type());
                paramDef.put("description", param.description());

                if (param.defaultValue() != null) {
                    paramDef.put("default", param.defaultValue());
                }

                properties.put(paramName, paramDef);

                if (param.required()) {
                    required.add(paramName);
                }
            }

            parameters.put("properties", properties);
            if (!required.isEmpty()) {
                parameters.put("required", required);
            }

            function.put("parameters", parameters);
            toolDef.put("function", function);
            tools.add(toolDef);
        }

        return tools;
    }

    public Object executeTool(Long userId, String toolName, Map<String, Object> arguments, Map<String, Object> context) {
        MCPTool tool = registeredTools.get(toolName);
        if (tool == null) {
            String error = "未知的工具: " + toolName + "。可用工具: " + String.join(", ", registeredTools.keySet());
            log.error(error);
            return Map.of("error", error);
        }

        log.info("执行优化版MCP工具: {} - 用户ID: {}, 参数: {}", toolName, userId, arguments);

        try {
            validateToolArguments(tool, arguments);
            Object result = tool.executor().execute(userId, arguments, context);
            log.info("优化版工具 {} 执行成功", toolName);
            return result;

        } catch (Exception e) {
            String error = "工具执行失败 [" + toolName + "]: " + e.getMessage();
            log.error(error, e);
            return Map.of("error", error, "toolName", toolName);
        }
    }

    private void validateToolArguments(MCPTool tool, Map<String, Object> arguments) {
        for (Map.Entry<String, ToolParameter> entry : tool.parameters().entrySet()) {
            String paramName = entry.getKey();
            ToolParameter param = entry.getValue();

            if (param.required() && !arguments.containsKey(paramName)) {
                throw new IllegalArgumentException("缺少必需参数: " + paramName + " (" + param.description() + ")");
            }
        }
    }

}
