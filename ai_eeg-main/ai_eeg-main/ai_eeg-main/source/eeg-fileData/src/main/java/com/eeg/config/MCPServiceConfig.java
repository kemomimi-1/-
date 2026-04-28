//  MCP服务配置类
package com.eeg.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) 服务配置
 * 专为AI大模型脑电数据分析服务设计
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "eeg.mcp")
public class MCPServiceConfig {

    // 分析性能配置
    private AnalysisPerformance performance = new AnalysisPerformance();

    // 数据质量标准
    private DataQualityStandards qualityStandards = new DataQualityStandards();

    // 研究上下文配置
    private ResearchContextConfig researchContext = new ResearchContextConfig();

    // AI模型集成配置
    private AIIntegrationConfig aiIntegration = new AIIntegrationConfig();

    @Data
    public static class AnalysisPerformance {
        private int maxConcurrentAnalysis = 5;
        private int summaryTimeout = 30; // seconds
        private int featureExtractionTimeout = 45; // seconds
        private boolean enableParallelProcessing = true;
        private int dataPointThreshold = 100000; // 超过此数量的数据点使用摘要模式
    }

    @Data
    public static class DataQualityStandards {
        private double minDataCompleteness = 0.95; // 95%
        private double maxOutlierPercentage = 0.05; // 5%
        private double minSignalToNoiseRatio = 3.0;
        private int minRequiredChannels = 4;
        private double maxArtifactLevel = 0.1; // 10%
    }

    @Data
    public static class ResearchContextConfig {
        // 注意力研究标准
        private AttentionResearchStandards attention = new AttentionResearchStandards();
        // 冥想研究标准
        private MeditationResearchStandards meditation = new MeditationResearchStandards();
        // 认知负荷研究标准
        private CognitiveLoadStandards cognitiveLoad = new CognitiveLoadStandards();

        @Data
        public static class AttentionResearchStandards {
            private double betaAlphaRatioThreshold = 1.2;
            private List<Integer> primaryChannels = List.of(1, 2); // Fp1, Fp2
            private double sustainedAttentionWindow = 5.0; // seconds
        }

        @Data
        public static class MeditationResearchStandards {
            private double alphaEnhancementThreshold = 1.5;
            private double thetaBetaRatioThreshold = 0.8;
            private double stabilityWindow = 10.0; // seconds
        }

        @Data
        public static class CognitiveLoadStandards {
            private double thetaPowerThreshold = 1.3;
            private double betaVariabilityThreshold = 0.3;
            private List<Integer> cognitiveChannels = List.of(3, 4); // C3, C4
        }
    }

    @Data
    public static class AIIntegrationConfig {
        private boolean enableAutoSummarization = true;
        private String defaultSummaryLevel = "comprehensive";
        private boolean provideInterpretationGuidance = true;
        private boolean includeLiteratureReferences = true;
        private int maxFeatureVectorSize = 1000;
    }
}