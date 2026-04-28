//  脑电数据分析工具类
package com.eeg.utils;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 脑电数据分析工具类
 * 为MCP服务提供通用的数据处理和分析功能
 */
@Slf4j
public class EEGAnalysisUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 标准脑电频段定义（基于国际10-20系统）
    public static final Map<String, FrequencyBand> STANDARD_BANDS = Map.of(
            "delta", new FrequencyBand("delta", 1.0, 4.0, "Deep sleep, unconscious processes"),
            "theta", new FrequencyBand("theta", 4.0, 8.0, "Meditation, creativity, memory"),
            "alpha", new FrequencyBand("alpha", 8.0, 13.0, "Relaxed awareness, eyes closed"),
            "beta", new FrequencyBand("beta", 13.0, 30.0, "Active thinking, concentration"),
            "gamma", new FrequencyBand("gamma", 30.0, 100.0, "High-level cognitive processing")
    );

    // 标准电极位置（OpenBCI 8通道映射）
    public static final Map<Integer, ElectrodeInfo> ELECTRODE_MAPPING = Map.of(
            1, new ElectrodeInfo(1, "Fp1", "Frontal Left", "Attention, executive function"),
            2, new ElectrodeInfo(2, "Fp2", "Frontal Right", "Attention, executive function"),
            3, new ElectrodeInfo(3, "C3", "Central Left", "Motor control, sensorimotor"),
            4, new ElectrodeInfo(4, "C4", "Central Right", "Motor control, sensorimotor"),
            5, new ElectrodeInfo(5, "P7", "Parietal Left", "Language, spatial processing"),
            6, new ElectrodeInfo(6, "P8", "Parietal Right", "Spatial processing, attention"),
            7, new ElectrodeInfo(7, "O1", "Occipital Left", "Visual processing"),
            8, new ElectrodeInfo(8, "O2", "Occipital Right", "Visual processing")
    );

    // 脑区映射
    public static final Map<String, List<Integer>> BRAIN_REGIONS = Map.of(
            "frontal", List.of(1, 2),
            "central", List.of(3, 4),
            "parietal", List.of(5, 6),
            "occipital", List.of(7, 8),
            "left_hemisphere", List.of(1, 3, 5, 7),
            "right_hemisphere", List.of(2, 4, 6, 8)
    );

    /**
     * 解析InfluxDB JSON响应为结构化数据
     */
    public static List<Map<String, Object>> parseInfluxDBResponse(String jsonResponse) {
        List<Map<String, Object>> parsedData = new ArrayList<>();

        try {
            JsonNode rootNode = objectMapper.readTree(jsonResponse);

            // 处理InfluxDB 3.x的JSON格式
            if (rootNode.isArray()) {
                for (JsonNode row : rootNode) {
                    Map<String, Object> dataPoint = new HashMap<>();
                    row.fields().forEachRemaining(entry ->
                            dataPoint.put(entry.getKey(), parseJsonValue(entry.getValue()))
                    );
                    parsedData.add(dataPoint);
                }
            }

        } catch (Exception e) {
            log.error("解析InfluxDB响应失败: {}", jsonResponse, e);
        }

        return parsedData;
    }

    /**
     * 计算频段功率统计
     */
    public static Map<String, BandPowerStats> calculateBandPowerStatistics(List<Map<String, Object>> bandPowerData) {
        Map<String, List<Double>> bandValues = new HashMap<>();

        // 按频段分组数据
        for (Map<String, Object> dataPoint : bandPowerData) {
            String band = (String) dataPoint.get("band");
            Double value = parseDoubleValue(dataPoint.get("value"));

            if (band != null && value != null) {
                bandValues.computeIfAbsent(band, k -> new ArrayList<>()).add(value);
            }
        }

        // 计算每个频段的统计信息
        Map<String, BandPowerStats> statistics = new HashMap<>();
        for (Map.Entry<String, List<Double>> entry : bandValues.entrySet()) {
            String band = entry.getKey();
            List<Double> values = entry.getValue();

            statistics.put(band, calculateStatistics(values, band));
        }

        return statistics;
    }

    /**
     * 计算通道间相关性
     */
    public static Map<String, Double> calculateChannelCorrelations(List<Map<String, Object>> timeSeriesData) {
        Map<String, Double> correlations = new HashMap<>();

        // 按通道分组数据
        Map<Integer, List<Double>> channelData = timeSeriesData.stream()
                .collect(Collectors.groupingBy(
                        data -> parseIntValue(data.get("channel")),
                        Collectors.mapping(
                                data -> parseDoubleValue(data.get("value")),
                                Collectors.filtering(Objects::nonNull, Collectors.toList())
                        )
                ));

        // 计算通道对的相关性
        List<Integer> channels = new ArrayList<>(channelData.keySet());
        for (int i = 0; i < channels.size(); i++) {
            for (int j = i + 1; j < channels.size(); j++) {
                int channel1 = channels.get(i);
                int channel2 = channels.get(j);

                List<Double> values1 = channelData.get(channel1);
                List<Double> values2 = channelData.get(channel2);

                if (values1.size() == values2.size() && !values1.isEmpty()) {
                    double correlation = calculatePearsonCorrelation(values1, values2);
                    correlations.put(channel1 + "-" + channel2, correlation);
                }
            }
        }

        return correlations;
    }

    /**
     * 检测数据异常值
     */
    public static AnomalyDetectionResult detectAnomalies(List<Map<String, Object>> timeSeriesData,
                                                         double zScoreThreshold) {
        Map<Integer, List<Double>> channelData = timeSeriesData.stream()
                .collect(Collectors.groupingBy(
                        data -> parseIntValue(data.get("channel")),
                        Collectors.mapping(
                                data -> parseDoubleValue(data.get("value")),
                                Collectors.filtering(Objects::nonNull, Collectors.toList())
                        )
                ));

        Map<Integer, AnomalyStats> channelAnomalies = new HashMap<>();
        int totalAnomalies = 0;
        int totalDataPoints = 0;

        for (Map.Entry<Integer, List<Double>> entry : channelData.entrySet()) {
            int channel = entry.getKey();
            List<Double> values = entry.getValue();

            if (values.isEmpty()) continue;

            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double std = calculateStandardDeviation(values, mean);

            List<Integer> anomalyIndices = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                double zScore = Math.abs((values.get(i) - mean) / std);
                if (zScore > zScoreThreshold) {
                    anomalyIndices.add(i);
                }
            }

            channelAnomalies.put(channel, new AnomalyStats(
                    anomalyIndices.size(),
                    values.size(),
                    (double) anomalyIndices.size() / values.size(),
                    anomalyIndices
            ));

            totalAnomalies += anomalyIndices.size();
            totalDataPoints += values.size();
        }

        return new AnomalyDetectionResult(
                channelAnomalies,
                totalAnomalies,
                totalDataPoints,
                (double) totalAnomalies / totalDataPoints,
                zScoreThreshold
        );
    }

    /**
     * 生成数据质量报告
     */
    public static DataQualityReport generateQualityReport(List<Map<String, Object>> rawData,
                                                          List<Map<String, Object>> filteredData,
                                                          List<Map<String, Object>> bandPowerData) {
        DataQualityReport report = new DataQualityReport();

        // 基础完整性检查
        report.setTotalRawSamples(rawData.size());
        report.setTotalFilteredSamples(filteredData.size());
        report.setTotalBandPowerSamples(bandPowerData.size());

        // 通道完整性
        Set<Integer> rawChannels = rawData.stream()
                .map(data -> parseIntValue(data.get("channel")))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        report.setActiveChannels(rawChannels.size());
        report.setExpectedChannels(8);
        report.setChannelCompleteness((double) rawChannels.size() / 8);

        // 时间完整性
        LocalDateTime firstTimestamp = rawData.stream()
                .map(data -> parseTimestamp((String) data.get("time")))
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);

        LocalDateTime lastTimestamp = rawData.stream()
                .map(data -> parseTimestamp((String) data.get("time")))
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        if (firstTimestamp != null && lastTimestamp != null) {
            report.setFirstTimestamp(firstTimestamp);
            report.setLastTimestamp(lastTimestamp);
            report.setTotalDuration(
                    java.time.Duration.between(firstTimestamp, lastTimestamp).getSeconds()
            );
        }

        // 异常检测
        AnomalyDetectionResult anomalies = detectAnomalies(rawData, 3.0);
        report.setAnomalyRate(anomalies.overallAnomalyRate());

        // 质量评分
        double qualityScore = calculateQualityScore(report);
        report.setOverallQualityScore(qualityScore);
        report.setQualityGrade(determineQualityGrade(qualityScore));

        return report;
    }

    /**
     * 为AI模型生成解释性上下文
     */
    public static Map<String, Object> generateAIContext(String analysisType,
                                                        Map<String, Object> analysisResults) {
        Map<String, Object> context = new HashMap<>();

        context.put("analysis_type", analysisType);
        context.put("neuroscience_background", getNeuroscienceBackground(analysisType));
        context.put("interpretation_guidelines", getInterpretationGuidelines(analysisType));
        context.put("clinical_relevance", getClinicalRelevance(analysisType));
        context.put("research_applications", getResearchApplications(analysisType));
        context.put("data_limitations", getDataLimitations());
        context.put("quality_considerations", getQualityConsiderations());

        return context;
    }

    // ========== 辅助计算方法 ==========

    private static BandPowerStats calculateStatistics(List<Double> values, String bandName) {
        if (values.isEmpty()) {
            return new BandPowerStats(bandName, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        Collections.sort(values);

        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        double mean = sum / values.size();
        double std = calculateStandardDeviation(values, mean);
        double min = values.get(0);
        double max = values.get(values.size() - 1);
        double median = calculateMedian(values);
        double q25 = calculatePercentile(values, 25);
        double q75 = calculatePercentile(values, 75);

        return new BandPowerStats(bandName, values.size(), mean, std, min, max, median, q25, q75);
    }

    private static double calculatePearsonCorrelation(List<Double> x, List<Double> y) {
        if (x.size() != y.size() || x.isEmpty()) {
            return 0.0;
        }

        double meanX = x.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double meanY = y.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        double numerator = 0.0;
        double denomX = 0.0;
        double denomY = 0.0;

        for (int i = 0; i < x.size(); i++) {
            double dx = x.get(i) - meanX;
            double dy = y.get(i) - meanY;
            numerator += dx * dy;
            denomX += dx * dx;
            denomY += dy * dy;
        }

        double denominator = Math.sqrt(denomX * denomY);
        return denominator == 0 ? 0.0 : numerator / denominator;
    }

    private static double calculateStandardDeviation(List<Double> values, double mean) {
        if (values.size() <= 1) return 0.0;

        double sumSquaredDiff = values.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .sum();

        return Math.sqrt(sumSquaredDiff / (values.size() - 1));
    }

    private static double calculateMedian(List<Double> sortedValues) {
        int size = sortedValues.size();
        if (size % 2 == 0) {
            return (sortedValues.get(size / 2 - 1) + sortedValues.get(size / 2)) / 2.0;
        } else {
            return sortedValues.get(size / 2);
        }
    }

    private static double calculatePercentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) return 0.0;

        double index = (percentile / 100.0) * (sortedValues.size() - 1);
        int lowerIndex = (int) Math.floor(index);
        int upperIndex = (int) Math.ceil(index);

        if (lowerIndex == upperIndex) {
            return sortedValues.get(lowerIndex);
        } else {
            double weight = index - lowerIndex;
            return sortedValues.get(lowerIndex) * (1 - weight) + sortedValues.get(upperIndex) * weight;
        }
    }

    private static double calculateQualityScore(DataQualityReport report) {
        double completenessScore = report.getChannelCompleteness() * 30; // 30%
        double anomalyScore = Math.max(0, (1 - report.getAnomalyRate()) * 30); // 30%
        double durationScore = Math.min(30, report.getTotalDuration() / 60.0 * 30); // 30%, max at 1 minute
        double channelScore = (double) report.getActiveChannels() / report.getExpectedChannels() * 10; // 10%

        return Math.min(100, completenessScore + anomalyScore + durationScore + channelScore);
    }

    private static String determineQualityGrade(double score) {
        if (score >= 90) return "Excellent";
        else if (score >= 80) return "Good";
        else if (score >= 70) return "Fair";
        else if (score >= 60) return "Poor";
        else return "Unacceptable";
    }

    // ========== 解析辅助方法 ==========

    private static Object parseJsonValue(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble()) return node.asDouble();
        return node.asText();
    }

    private static Double parseDoubleValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime parseTimestamp(String timestamp) {
        if (timestamp == null) return null;
        try {
            return LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    // ========== AI上下文生成方法 ==========

    private static Map<String, Object> getNeuroscienceBackground(String analysisType) {
        Map<String, Object> background = new HashMap<>();

        switch (analysisType.toLowerCase()) {
            case "attention":
                background.put("primary_mechanisms", "Frontal cortex, attention networks");
                background.put("key_frequencies", "Beta (13-30Hz) for focused attention, Alpha suppression");
                background.put("neural_basis", "Prefrontal cortex executive control, anterior cingulate monitoring");
                break;
            case "meditation":
                background.put("primary_mechanisms", "Default mode network, present-moment awareness");
                background.put("key_frequencies", "Alpha enhancement (8-13Hz), Theta increases in deep states");
                background.put("neural_basis", "Reduced mind-wandering, enhanced interoceptive awareness");
                break;
            default:
                background.put("general_principles", "EEG reflects synchronized neural activity");
                background.put("frequency_interpretation", "Different frequencies associated with distinct cognitive states");
        }

        return background;
    }

    private static List<String> getInterpretationGuidelines(String analysisType) {
        return switch (analysisType.toLowerCase()) {
            case "attention" -> List.of(
                    "Higher Beta/Alpha ratios indicate increased attention",
                    "Frontal electrodes most relevant for attention assessment",
                    "Sustained patterns more meaningful than momentary spikes"
            );
            case "meditation" -> List.of(
                    "Progressive Alpha increases indicate deepening relaxation",
                    "Reduced Beta activity suggests decreased mental chatter",
                    "Stability of patterns indicates meditation quality"
            );
            default -> List.of(
                    "Consider individual baseline differences",
                    "Account for experimental context",
                    "Validate with established EEG research"
            );
        };
    }

    private static Map<String, String> getClinicalRelevance(String analysisType) {
        Map<String, String> relevance = new HashMap<>();

        switch (analysisType.toLowerCase()) {
            case "attention":
                relevance.put("ADHD", "Altered Beta/Alpha ratios often observed");
                relevance.put("cognitive_training", "Neurofeedback protocols target attention networks");
                relevance.put("aging", "Attention-related EEG changes with healthy aging");
                break;
            case "meditation":
                relevance.put("stress_reduction", "Meditation training affects stress-related EEG patterns");
                relevance.put("mental_health", "Mindfulness interventions show EEG changes");
                relevance.put("cognitive_enhancement", "Meditation may improve cognitive function");
                break;
        }

        return relevance;
    }

    private static List<String> getResearchApplications(String analysisType) {
        return switch (analysisType.toLowerCase()) {
            case "attention" -> List.of(
                    "ADHD research and diagnosis",
                    "Cognitive training effectiveness",
                    "Attention-based BCI systems",
                    "Educational neuroscience"
            );
            case "meditation" -> List.of(
                    "Mindfulness research",
                    "Contemplative neuroscience",
                    "Stress intervention studies",
                    "Consciousness research"
            );
            default -> List.of(
                    "General cognitive neuroscience",
                    "Clinical EEG applications",
                    "Brain-computer interfaces",
                    "Neurofeedback research"
            );
        };
    }

    private static List<String> getDataLimitations() {
        return List.of(
                "Limited spatial resolution compared to fMRI",
                "Susceptible to muscle and eye movement artifacts",
                "Individual differences in skull thickness affect signal",
                "Short recording duration may not capture state changes",
                "Environmental factors can influence recording quality"
        );
    }

    private static List<String> getQualityConsiderations() {
        return List.of(
                "Ensure good electrode contact quality",
                "Minimize movement and environmental noise",
                "Consider baseline recordings for comparison",
                "Account for circadian rhythm effects",
                "Validate findings with multiple sessions"
        );
    }

    // ========== 数据类定义 ==========

    public record FrequencyBand(String name, double lowFreq, double highFreq, String description) {}

    public record ElectrodeInfo(int channel, String name, String region, String function) {}

    public record BandPowerStats(String band, int sampleCount, double mean, double std,
                                 double min, double max, double median, double q25, double q75) {}

    public record AnomalyStats(int anomalyCount, int totalCount, double anomalyRate,
                               List<Integer> anomalyIndices) {}

    public record AnomalyDetectionResult(Map<Integer, AnomalyStats> channelAnomalies,
                                         int totalAnomalies, int totalDataPoints,
                                         double overallAnomalyRate, double zScoreThreshold) {}

    @lombok.Data
    public static class DataQualityReport {
        private int totalRawSamples;
        private int totalFilteredSamples;
        private int totalBandPowerSamples;
        private int activeChannels;
        private int expectedChannels;
        private double channelCompleteness;
        private LocalDateTime firstTimestamp;
        private LocalDateTime lastTimestamp;
        private long totalDuration;
        private double anomalyRate;
        private double overallQualityScore;
        private String qualityGrade;
    }
}