// 数据透明化增强版：确保AI分析完全基于真实数据
package com.eeg.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Arrays;

/**
 * 专业脑电数据分析AI大模型配置 - 简化透明化版本
 * 核心特性：绝对数据真实性、完全分析透明度、严格科学验证
 * 设计理念：让AI成为完全基于真实数据的脑电数据分析专家，杜绝任何数据虚构
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.tongyi")
public class AIModelConfig {

    // ========== 通义千问API配置 ==========
    private String apiKey = "sk-1f46b1e2693846c4a85db50d5e41460c";
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String model = "qwen-max";

    // ========== 调用参数配置 ==========
    private Double temperature = 0.01; // 极低随机性确保数据透明度
    private Integer maxTokens = 8000;
    private Double topP = 0.7;
    private Integer timeout = 300;
    private Integer connectionTimeout = 60;
    private Integer readTimeout = 300;
    private Integer maxRetries = 3;

    // ========== MCP工具集成配置 ==========
    private boolean enableMcpTools = true;
    private Integer maxToolCalls = 14;
    private boolean enableAutoSelection = true;
    private boolean enableToolCollaboration = true;
    private String collaborationMode = "guided";
    private Integer toolCallTimeout = 60;
    private boolean enableParallelToolCalls = true;

    // ========== 数据透明化配置 ==========
    private boolean enableDataTransparency = true;
    private boolean requireDataSource = true;
    private boolean showCalculationProcess = true;
    private boolean enableRawDataDisplay = true;
    private boolean preventDataFabrication = true;

    // ========== 科学严谨性配置 ==========
    private boolean enableDataValidation = true;
    private boolean enableScientificExplanation = true;
    private boolean enableNeuroscienceKnowledge = true;
    private Double confidenceThreshold = 0.7;

    // ========== 会话管理配置 ==========
    private boolean enableConversationHistory = true;
    private Integer maxConversationTurns = 15;
    private boolean enableTimeContextAwareness = true;
    private boolean enableDataTimeCorrelation = true;

    // ========== 核心系统提示词 - 简化透明化版本 ==========
    private String systemPrompt = """
        你是一位世界顶级的脑电数据分析专家，专门从事OpenBCI EEG信号分析和神经科学研究。你的专业能力包括时域分析、频域分析、信号质量评估和科研级数据解释。

        【绝对数据透明化原则 - 这是你的核心行为准则】
        
        🔬 **绝对禁止数据虚构**：
        - 你绝对不会编造、虚构或假设任何EEG数据值
        - 每一个数值、统计结果、计算结果都必须来自真实的数据库查询
        - 如果没有实际数据支撑，你会明确告诉用户"根据当前可获取的数据，我无法提供这项分析"
        - 你永远不会说出类似"例如，通道1的标准差为7.046"这样的话，除非你真的从数据中计算出了这个值

        📊 **完全分析透明化**：
        - 你的每一个结论都必须明确说明数据来源和计算过程
        - 当你说"通道1的标准差为X"时，你必须说明这个标准差是基于哪个时间段的多少个数据点计算得出的
        - 当你说"Alpha频段平均功率为Y"时，你必须说明这个平均值是基于多少个时间点的数据计算的
        - 你会主动展示关键的原始数据样本，让用户看到分析的数据基础

        🔍 **数据来源必须明确**：
        - 分析信号质量时：明确说明分析了哪个时间段的数据，使用了哪张数据表，计算了多少个数据点
        - 分析频谱数据时：明确说明使用了多少个时间点的数据，每个频段包含多少条记录
        - 分析会话信息时：明确说明会话的确切时间范围和数据传输统计

        🧠 **神经科学专业基础**：
        **EEG频段生物学意义**：
        - Delta (0.5-4Hz): 深度睡眠，异常时可能提示脑损伤
        - Theta (4-8Hz): 工作记忆，创造性任务，REM睡眠
        - Alpha (8-13Hz): 放松警觉，默认模式网络，闭眼清醒状态
        - Beta (13-30Hz): 专注状态，执行控制，主动思维
        - Gamma (30-100Hz): 意识统一，特征绑定，高级认知功能
        
        **信号质量评估标准**：
        - 优秀SNR: > 15 dB (适用于合成EEG数据)
        - 良好SNR: 5-15 dB
        - 可接受SNR: -5 to 5 dB  
        - 正常EEG振幅: 1-200 μV (合成数据)，10-100 μV (真实数据)
        
        📋 **输出格式要求**：
        - 使用专业但通俗易懂的语言与用户交流
        - 重点展示数据来源和计算过程的透明度
        - 提供学术级统计分析和生物学意义解释  
        - 主动询问是否需要更详细的某个方面分析
        - 避免暴露底层技术实现细节
        - 用科学准确但平易近人的解释

        🔒 **数据安全和伦理原则**：
        - 严格的用户身份验证和会话管理，每次查询都会验证用户权限
        - 绝不访问或显示其他用户的EEG数据
        - 所有数据查询都限定在当前用户范围内
        - 从不修改、删除或篡改原始EEG数据
        - 不提供医学诊断或治疗建议
        - 遵循科学伦理准则，不提供医学诊断

        💡 **透明化表达方式示例**：
        ✅ 正确："根据会话ID 12（2025-08-28 11:07-11:15，479秒）的滤波数据分析，我计算了最后30秒窗口内的8个通道共2,400个数据点，发现通道1的样本标准差为7.046μV（基于300个数据点计算）。"

        ❌ 错误："通道1的标准差为7.046，通道2的标准差为19.723。"（没有说明数据来源和计算基础）

        ✅ 正确："基于最新10个时间点的频谱数据（时间范围：11:15:16.891 - 11:15:17.000），Alpha频段的平均功率为0.0195μV²，这是根据10条avg_band_power表记录计算得出。"

        ❌ 错误："Alpha频段平均功率为0.0195μV²。"（没有说明基于多少数据点和什么时间范围）

        记住：你是用户可信赖的EEG数据分析伙伴，你的专业性体现在对真实数据的深度理解和完全透明的科学分析上。帮助用户从复杂的脑电数据中获得有意义的科学洞察，必须基于数据回答，每个结果都要有数据根据，要说出具体哪个数据让你分析出了这样的结论，数据得出结论更能让用户信服。
        
        【绝对禁止行为清单】
        ❌ 绝对不编造任何数值、统计结果或分析数据
        ❌ 绝对不使用"例如"来举虚构的数据例子
        ❌ 绝对不在没有查询数据的情况下给出具体数值
        ❌ 绝对不忽略数据来源说明和计算过程透明度
        ❌ 绝对不给出没有数据支撑的医学建议
        """;

    /**
     * 数据安全和隐私保护提示词
     */
    private String privacyPrompt = """
        【数据安全和隐私保护原则】
        
        🔒 **数据访问控制透明化**：
        - 严格的用户身份验证和会话管理，每次查询都会验证用户权限
        - 每个分析查询都包含用户ID验证，确保数据隔离
        - 绝不访问或显示其他用户的EEG数据
        - 所有数据查询都限定在当前用户范围内
        
        🛡️ **数据完整性保证透明化**：
        - 从不修改、删除或篡改原始EEG数据
        - 所有返回的数值都是真实的数据库记录
        - 时间戳和会话信息保持原始精度
        - 数据分析过程透明可追溯，用户可以验证每个步骤
        
        🔐 **隐私保护措施透明化**：
        - 不存储用户个人对话内容到外部系统
        - 分析结果仅在当前会话中有效
        - 不与第三方共享任何EEG数据或分析结果
        - 遵循最小数据访问原则
        
        ⚖️ **科学伦理准则透明化**：
        - 所有EEG数据分析仅用于科研和教育目的
        - 不提供医学诊断或治疗建议
        - 尊重数据主体的隐私和自主权
        - 遵循神经科学研究伦理标准
        """;

    /**
     * 协作优化提示词
     */
    private String collaborationOptimizationPrompt = """
        【智能协作优化策略】
        
        🚀 **性能优化原则**：
        - 小数据集(<1000记录)：全量分析，多维度验证，展示完整数据样本
        - 中数据集(1K-10K)：智能采样，重点分析，展示关键数据点
        - 大数据集(10K-100K)：分层采样，摘要生成，展示统计摘要
        - 超大数据集(>100K)：强制使用智能摘要分析，展示代表性样本
        
        ⚡ **智能执行策略**：
        - 单一查询：直接执行，快速响应，明确展示数据来源
        - 双重验证：主分析+质量检查，展示验证过程
        - 多维分析：按优先级排序执行，展示每步数据流
        - 深度研究：分阶段执行，避免超时，提供进度透明度
        
        **用户体验透明优化**：
        - 复杂分析提供进度指示，说明当前处理的数据范围
        - 长时间查询分段反馈，展示已获取的数据统计
        - 主动询问是否需要更详细的分析，提供透明度选择
        """;

    /**
     * 获取完整的系统提示词（包含所有智能指导）
     */
    public String getFullSystemPrompt() {
        return systemPrompt + "\n\n" + privacyPrompt + "\n\n" + collaborationOptimizationPrompt;
    }

    /**
     * 根据场景获取上下文相关的系统提示词
     */
    public String getContextualPrompt(String scenario) {
        StringBuilder prompt = new StringBuilder(getFullSystemPrompt());

        switch (scenario.toLowerCase()) {
            case "real_time_monitoring":
                prompt.append("\n\n【实时监控专用透明化增强】")
                        .append("当前查询专注于实时EEG数据监控。")
                        .append("优先使用实时会话状态分析，必须展示实时数据的获取时间和统计基础。")
                        .append("强调数据的时效性和当前状态的准确描述。")
                        .append("如果没有活跃会话，明确说明并建议开始数据采集。");
                break;

            case "frequency_analysis":
                prompt.append("\n\n【频域分析专用透明化增强】")
                        .append("当前查询专注于脑电频谱特征分析。")
                        .append("必须展示频谱数据的时间范围、记录数量和计算方法。")
                        .append("提供详细的神经科学解释，说明各频段的认知意义。")
                        .append("结合信号质量评估确保分析结果的可靠性，展示质量验证过程。");
                break;

            case "signal_quality":
                prompt.append("\n\n【信号质量评估专用透明化增强】")
                        .append("当前查询专注于EEG信号质量控制。")
                        .append("必须展示质量评估的具体指标和计算依据。")
                        .append("提供具体的质量指标和改进建议，基于IEEE标准。")
                        .append("必要时结合技术规格分析找出质量问题根源，提供完整的诊断过程。");
                break;

            case "historical_analysis":
                prompt.append("\n\n【历史趋势分析专用透明化增强】")
                        .append("当前查询专注于历史EEG数据趋势分析。")
                        .append("必须展示历史数据的时间跨度和统计方法。")
                        .append("识别长期变化模式和认知状态演化。")
                        .append("提供科学的趋势解释和预测建议，基于实际历史数据。");
                break;

            case "comparative_study":
                prompt.append("\n\n【对比研究专用透明化增强】")
                        .append("当前查询专注于多会话EEG数据对比。")
                        .append("必须展示每个会话的具体数据统计和对比标准。")
                        .append("提供详细的统计学对比和科学解释。")
                        .append("明确指出差异的神经科学意义，基于真实的对比数据。");
                break;

            case "comprehensive_research":
                prompt.append("\n\n【综合研究专用透明化增强】")
                        .append("当前查询需要深度综合分析。")
                        .append("必须展示完整的分析策略和数据处理过程。")
                        .append("协调多个分析工具提供全面报告。")
                        .append("确保分析结果的科学性和完整性，提供完整的数据透明度。");
                break;

            default:
                // 使用默认的完整提示词
                break;
        }

        return prompt.toString();
    }

    /**
     * 根据用户查询推荐场景
     */
    public String recommendScenario(String userQuery) {
        if (userQuery == null) return "default";

        String query = userQuery.toLowerCase();

        // 智能场景识别算法
        Map<String, Integer> scenarioScores = new HashMap<>();
        scenarioScores.put("real_time_monitoring", 0);
        scenarioScores.put("frequency_analysis", 0);
        scenarioScores.put("signal_quality", 0);
        scenarioScores.put("historical_analysis", 0);
        scenarioScores.put("comparative_study", 0);
        scenarioScores.put("comprehensive_research", 0);

        // 数据透明化相关关键词也会影响场景选择
        if (containsAny(query, "数据来源", "怎么算的", "基于什么", "计算过程", "透明")) {
            // 增加所有场景的透明化需求权重
            scenarioScores.replaceAll((k, v) -> v + 2);
        }

        // 实时监控场景评分
        if (containsAny(query, "当前", "现在", "正在", "实时", "活跃")) {
            scenarioScores.put("real_time_monitoring", scenarioScores.get("real_time_monitoring") + 5);
        }

        // 频域分析场景评分
        if (containsAny(query, "最新", "频谱", "频段", "功率", "alpha", "beta", "theta", "delta", "gamma")) {
            scenarioScores.put("frequency_analysis", scenarioScores.get("frequency_analysis") + 5);
        }

        // 信号质量场景评分
        if (containsAny(query, "信号质量", "数据质量", "噪声", "稳定", "干扰")) {
            scenarioScores.put("signal_quality", scenarioScores.get("signal_quality") + 5);
        }

        // 历史分析场景评分
        if (containsAny(query, "历史", "趋势", "变化", "发展", "演化")) {
            scenarioScores.put("historical_analysis", scenarioScores.get("historical_analysis") + 5);
        }

        // 对比研究场景评分
        if (containsAny(query, "对比", "比较", "差异", "区别", "哪个更好", "最优", "最佳")) {
            scenarioScores.put("comparative_study", scenarioScores.get("comparative_study") + 5);
        }

        // 综合研究场景评分
        if (containsAny(query, "全面", "详细", "深入", "完整", "综合", "深度")) {
            scenarioScores.put("comprehensive_research", scenarioScores.get("comprehensive_research") + 5);
        }

        // 找出得分最高的场景
        return scenarioScores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .orElse("default");
    }

    /**
     * 检查文本是否包含任意关键词
     */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取针对特定复杂度的配置
     */
    public RequestConfig getOptimalConfig(String userQuery) {
        String scenario = recommendScenario(userQuery);
        int complexityScore = assessQueryComplexity(userQuery);

        RequestConfig config = new RequestConfig(this);
        config.setScenarioContext(scenario);

        // 根据场景和复杂度调整参数，透明化查询使用更低的温度
        if (scenario.equals("comprehensive_research") || complexityScore > 8) {
            config.setTemperature(0.005);   // 透明化研究需要高度一致性
            config.setMaxTokens(8000);     // 最大token用于详细分析
            config.setCollaborationMode("free");
        } else if (complexityScore > 5) {
            config.setTemperature(0.01);   // 极低随机性确保数据透明度
            config.setMaxTokens(6000);     // 足够的空间用于透明化分析
            config.setCollaborationMode("guided");
        } else {
            config.setTemperature(0.005);  // 最低随机性用于简单透明查询
            config.setMaxTokens(4000);     // 简洁但透明的回答
            config.setCollaborationMode("guided");
        }

        return config;
    }

    /**
     * 评估查询复杂度
     */
    private int assessQueryComplexity(String userQuery) {
        if (userQuery == null) return 0;

        String query = userQuery.toLowerCase();
        int complexity = 0;

        // 透明化相关关键词增加复杂度
        if (containsAny(query, "数据来源", "怎么算", "基于什么", "计算过程", "透明")) complexity += 2;

        // 关键词复杂度
        if (containsAny(query, "全面", "详细", "深入", "完整", "综合")) complexity += 3;
        if (containsAny(query, "对比", "比较", "分析", "评估")) complexity += 2;
        if (containsAny(query, "所有", "全部", "历史", "趋势")) complexity += 2;
        if (containsAny(query, "质量", "稳定", "技术", "参数")) complexity += 1;

        // 数量词复杂度
        if (query.matches(".*\\d+.*")) complexity += 1;
        if (containsAny(query, "几个", "多个", "各种", "不同")) complexity += 2;

        // 时间词复杂度
        if (containsAny(query, "最近", "历史", "变化", "趋势", "发展")) complexity += 1;

        // 问句复杂度
        long questionMarks = query.chars().filter(ch -> ch == '？' || ch == '?').count();
        if (questionMarks > 1) complexity += 2;

        // 专业术语复杂度
        if (containsAny(query, "alpha", "beta", "theta", "delta", "gamma", "频段", "频谱")) complexity += 2;

        return complexity;
    }

    // ========== 内部类：请求配置 ==========

    @Data
    public static class RequestConfig {
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private String model;
        private boolean enableTools;
        private String scenarioContext;
        private String collaborationMode;
        private boolean enableIntelligentSelection;

        public RequestConfig(AIModelConfig config) {
            this.temperature = config.getTemperature();
            this.maxTokens = config.getMaxTokens();
            this.topP = config.getTopP();
            this.model = config.getModel();
            this.enableTools = config.isEnableMcpTools();
            this.collaborationMode = config.getCollaborationMode();
            this.enableIntelligentSelection = config.isEnableAutoSelection();
        }

        public RequestConfig(AIModelConfig config, String scenario) {
            this(config);
            this.scenarioContext = scenario;
        }
    }

    // ========== 配置验证方法 ==========

    /**
     * 验证配置的有效性
     */
    public void validateConfiguration() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("AI API密钥不能为空");
        }

        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("AI API基础URL不能为空");
        }

        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("温度参数必须在0.0-2.0之间");
        }

        if (topP < 0.0 || topP > 1.0) {
            throw new IllegalArgumentException("Top-p参数必须在0.0-1.0之间");
        }

        if (maxTokens <= 0 || maxTokens > 8000) {
            throw new IllegalArgumentException("最大token数必须在1-8000之间");
        }

        if (maxToolCalls <= 0 || maxToolCalls > 14) {
            throw new IllegalArgumentException("最大工具调用次数必须在1-14之间");
        }

        if (!Arrays.asList("free", "guided", "strict").contains(collaborationMode)) {
            throw new IllegalArgumentException("协作模式必须是: free, guided, strict");
        }

        if (confidenceThreshold < 0.0 || confidenceThreshold > 1.0) {
            throw new IllegalArgumentException("置信度阈值必须在0.0-1.0之间");
        }
    }

    /**
     * 获取配置摘要信息
     */
    public Map<String, Object> getConfigSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("model", model);
        summary.put("temperature", temperature);
        summary.put("maxTokens", maxTokens);
        summary.put("enableMcpTools", enableMcpTools);
        summary.put("maxToolCalls", maxToolCalls);
        summary.put("collaborationMode", collaborationMode);
        summary.put("enableDataTransparency", enableDataTransparency);
        summary.put("requireDataSource", requireDataSource);
        summary.put("showCalculationProcess", showCalculationProcess);
        summary.put("enableRawDataDisplay", enableRawDataDisplay);
        summary.put("preventDataFabrication", preventDataFabrication);
        summary.put("enableScientificExplanation", enableScientificExplanation);
        summary.put("enableDataValidation", enableDataValidation);
        summary.put("enableTimeContextAwareness", enableTimeContextAwareness);
        summary.put("systemPromptLength", getFullSystemPrompt().length());
        summary.put("mcpToolsIntegrated", true);
        summary.put("dataTransparencyEnhanced", true);
        summary.put("configurationValid", true);

        return summary;
    }
}