package com.eeg.entity.ai;

import java.util.List;
import java.util.Map;

/**
 * 脑科学与分析领域常量字典
 * 集中管理MCP分析平台相关的内联数据和配置常量
 */
public class NeuroscienceDictionary {

    public static final List<String> CORE_FEATURES = List.of(
            "multi_layer_data_summarization",
            "targeted_feature_extraction",
            "session_comparison_analysis",
            "data_quality_assessment",
            "research_guided_analysis"
    );

    public static final Map<String, String> ANALYSIS_LAYERS = Map.of(
            "layer1", "Basic Statistics & Data Overview",
            "layer2", "Frequency Domain Analysis",
            "layer3", "Temporal Pattern Recognition",
            "layer4", "Data Quality Assessment",
            "layer5", "Spatial Feature Analysis"
    );

    public static final Map<String, String> FREQUENCY_BANDS = Map.of(
            "delta", "1-4 Hz (deep sleep, unconscious)",
            "theta", "4-8 Hz (meditation, creativity)",
            "alpha", "8-13 Hz (relaxation, eyes closed)",
            "beta", "13-30 Hz (active thinking, focus)",
            "gamma", "30-100 Hz (high cognitive function)"
    );

    public static final Map<String, String> ELECTRODE_POSITIONS = Map.of(
            "frontal", "channels 1,2 (Fp1,Fp2)",
            "central", "channels 3,4 (C3,C4)",
            "parietal", "channels 5,6 (P7,P8)",
            "occipital", "channels 7,8 (O1,O2)"
    );

    public static final List<String> TYPICAL_APPLICATIONS = List.of(
            "Attention monitoring",
            "Meditation assessment",
            "Cognitive load measurement",
            "Sleep stage analysis",
            "Emotional state detection",
            "Neurofeedback training",
            "BCI control interfaces"
    );

    public static final Map<String, Object> NEUROSCIENCE_CONTEXT = Map.of(
            "frequency_bands", FREQUENCY_BANDS,
            "electrode_positions", ELECTRODE_POSITIONS,
            "typical_applications", TYPICAL_APPLICATIONS
    );

    public static final Map<String, String> AI_USAGE_GUIDELINES = Map.of(
            "data_independence", "Each session represents an independent recording context",
            "session_separation", "Never mix data from different sessions in analysis",
            "feature_extraction", "Use research-context-aware feature extraction",
            "quality_first", "Always assess data quality before analysis",
            "interpretation", "Consider neuroscientific context in result interpretation"
    );
}
