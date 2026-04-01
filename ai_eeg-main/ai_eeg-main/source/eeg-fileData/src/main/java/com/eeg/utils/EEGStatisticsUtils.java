package com.eeg.utils;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * EEG统计分析工具类 - InfluxDB兼容版本
 * 替代数据库的复杂统计函数，在Java中进行计算
 */
@Slf4j
@Component
public class EEGStatisticsUtils {

    /**
     * 按通道分组并进行统计分析
     */
    public Map<String, Object> analyzeChannelData(JsonNode dataNode) {
        Map<Integer, List<Double>> channelData = groupDataByChannel(dataNode);
        Map<String, Object> result = new HashMap<>();

        List<Map<String, Object>> channelAnalyses = new ArrayList<>();
        double overallQuality = 0.0;
        int validChannels = 0;

        for (Map.Entry<Integer, List<Double>> entry : channelData.entrySet()) {
            int channel = entry.getKey();
            List<Double> values = entry.getValue();

            if (!values.isEmpty()) {
                Map<String, Object> channelStats = calculateChannelStatistics(channel, values);
                channelAnalyses.add(channelStats);

                // 累计质量分数
                if (channelStats.containsKey("qualityScore")) {
                    overallQuality += (Double) channelStats.get("qualityScore");
                    validChannels++;
                }
            }
        }

        result.put("channelAnalyses", channelAnalyses);
        result.put("overallQuality", validChannels > 0 ? overallQuality / validChannels : 0.0);
        result.put("totalChannels", channelData.size());
        result.put("validChannels", validChannels);
        result.put("totalDataPoints", dataNode.size());

        return result;
    }

    /**
     * 按频段分组并进行统计分析
     */
    public Map<String, Object> analyzeBandData(JsonNode dataNode) {
        Map<String, List<Double>> bandData = groupDataByBand(dataNode);
        Map<String, Object> result = new HashMap<>();

        Map<String, Object> bandAnalyses = new HashMap<>();
        for (Map.Entry<String, List<Double>> entry : bandData.entrySet()) {
            String band = entry.getKey();
            List<Double> values = entry.getValue();

            if (!values.isEmpty()) {
                Map<String, Object> bandStats = calculateBandStatistics(band, values);
                bandAnalyses.put(band, bandStats);
            }
        }

        result.put("bandAnalyses", bandAnalyses);
        result.put("totalBands", bandData.size());
        result.put("analysisMethod", "java_in_memory_calculation");

        return result;
    }

    /**
     * 按通道分组数据
     */
    private Map<Integer, List<Double>> groupDataByChannel(JsonNode dataNode) {
        Map<Integer, List<Double>> channelData = new HashMap<>();

        for (JsonNode record : dataNode) {
            if (record.has("channel") && record.has("value")) {
                int channel = record.get("channel").asInt();
                double value = record.get("value").asDouble();
                channelData.computeIfAbsent(channel, k -> new ArrayList<>()).add(value);
            }
        }

        return channelData;
    }

    /**
     * 按频段分组数据
     */
    private Map<String, List<Double>> groupDataByBand(JsonNode dataNode) {
        Map<String, List<Double>> bandData = new HashMap<>();

        for (JsonNode record : dataNode) {
            if (record.has("band") && record.has("value")) {
                String band = record.get("band").asText();
                double value = record.get("value").asDouble();
                bandData.computeIfAbsent(band, k -> new ArrayList<>()).add(value);
            }
        }

        return bandData;
    }

    /**
     * 计算单个通道的完整统计信息
     */
    public Map<String, Object> calculateChannelStatistics(int channel, List<Double> values) {
        Map<String, Object> stats = new HashMap<>();

        if (values == null || values.isEmpty()) {
            stats.put("error", "无数据");
            return stats;
        }

        try {
            int sampleCount = values.size();

            // 基础统计量
            double mean = calculateMean(values);
            double variance = calculateSampleVariance(values, mean);
            double stdDev = Math.sqrt(variance);
            double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            double range = max - min;

            // 高级统计量
            double median = calculateMedian(values);
            double[] quartiles = calculateQuartiles(values);
            double rms = calculateRMS(values);
            double coefficientOfVariation = Math.abs(mean) > 1e-12 ? stdDev / Math.abs(mean) : 0.0;

            // 质量评估
            double snr_dB = rms > 1e-12 ? 20 * Math.log10(rms / (stdDev + 1e-12)) : 0.0;
            double qualityScore = calculateQualityScore(rms, stdDev, coefficientOfVariation);

            stats.put("channel", channel);
            stats.put("basicStatistics", Map.of(
                    "sampleCount", sampleCount,
                    "mean", mean,
                    "standardDeviation", stdDev,
                    "variance", variance,
                    "minimum", min,
                    "maximum", max,
                    "range", range
            ));

            stats.put("advancedStatistics", Map.of(
                    "median", median,
                    "q1", quartiles[0],
                    "q3", quartiles[1],
                    "iqr", quartiles[1] - quartiles[0],
                    "rms", rms,
                    "coefficientOfVariation", coefficientOfVariation
            ));

            stats.put("qualityMetrics", Map.of(
                    "snr_dB", snr_dB,
                    "qualityScore", qualityScore,
                    "qualityLevel", getQualityLevel(qualityScore)
            ));

        } catch (Exception e) {
            log.error("计算通道 {} 统计信息失败", channel, e);
            stats.put("error", "统计计算失败: " + e.getMessage());
        }

        return stats;
    }

    /**
     * 计算频段统计信息
     */
    public Map<String, Object> calculateBandStatistics(String band, List<Double> values) {
        Map<String, Object> stats = new HashMap<>();

        if (values == null || values.isEmpty()) {
            stats.put("error", "无数据");
            return stats;
        }

        try {
            double mean = calculateMean(values);
            double stdDev = Math.sqrt(calculateSampleVariance(values, mean));
            double median = calculateMedian(values);
            double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

            stats.put("band", band);
            stats.put("sampleCount", values.size());
            stats.put("meanPower", mean);
            stats.put("standardDeviation", stdDev);
            stats.put("median", median);
            stats.put("minimum", min);
            stats.put("maximum", max);
            stats.put("powerRange", max - min);
            stats.put("biologicalMeaning", getBandBiologicalMeaning(band));

        } catch (Exception e) {
            log.error("计算频段 {} 统计信息失败", band, e);
            stats.put("error", "频段统计计算失败: " + e.getMessage());
        }

        return stats;
    }

    // ========== 基础统计计算方法 ==========

    public double calculateMean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    public double calculateSampleVariance(List<Double> values, double mean) {
        if (values.size() <= 1) return 0.0;

        double sumSquaredDiff = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .sum();

        return sumSquaredDiff / (values.size() - 1); // Bessel's correction
    }

    public double calculateMedian(List<Double> values) {
        List<Double> sorted = values.stream().sorted().collect(Collectors.toList());
        int size = sorted.size();

        if (size % 2 == 0) {
            return (sorted.get(size/2 - 1) + sorted.get(size/2)) / 2.0;
        } else {
            return sorted.get(size/2);
        }
    }

    public double[] calculateQuartiles(List<Double> values) {
        List<Double> sorted = values.stream().sorted().collect(Collectors.toList());
        int size = sorted.size();

        double q1Index = (size - 1) * 0.25;
        double q3Index = (size - 1) * 0.75;

        double q1 = interpolate(sorted, q1Index);
        double q3 = interpolate(sorted, q3Index);

        return new double[]{q1, q3};
    }

    private double interpolate(List<Double> sorted, double index) {
        int lowerIndex = (int) Math.floor(index);
        int upperIndex = (int) Math.ceil(index);

        if (lowerIndex == upperIndex || upperIndex >= sorted.size()) {
            return sorted.get(Math.min(lowerIndex, sorted.size() - 1));
        }

        double weight = index - lowerIndex;
        return sorted.get(lowerIndex) * (1 - weight) + sorted.get(upperIndex) * weight;
    }

    public double calculateRMS(List<Double> values) {
        return Math.sqrt(values.stream().mapToDouble(v -> v * v).average().orElse(0.0));
    }

    public double calculateQualityScore(double rms, double stdDev, double coefficientOfVariation) {
        // 基于RMS幅值的质量评分 (0-100)
        double amplitudeScore = calculateAmplitudeScore(rms);
        // 基于稳定性的质量评分
        double stabilityScore = Math.max(0, 100 - coefficientOfVariation * 100);
        // 综合质量评分
        return (amplitudeScore + stabilityScore) / 2.0;
    }

    private double calculateAmplitudeScore(double rms) {
        // 基于典型EEG幅值范围 (10-100μV) 的评分
        if (rms >= 10 && rms <= 100) return 100.0;
        if (rms >= 5 && rms <= 200) return 80.0;
        if (rms >= 1 && rms <= 500) return 60.0;
        return Math.max(0.0, 40.0 - Math.abs(Math.log10(rms / 50.0)) * 20);
    }

    private String getQualityLevel(double qualityScore) {
        if (qualityScore >= 85) return "优秀";
        if (qualityScore >= 70) return "良好";
        if (qualityScore >= 55) return "一般";
        if (qualityScore >= 40) return "较差";
        return "很差";
    }

    private String getBandBiologicalMeaning(String band) {
        return switch (band.toLowerCase()) {
            case "delta" -> "深度睡眠、无意识状态";
            case "theta" -> "REM睡眠、冥想状态、记忆巩固";
            case "alpha" -> "闭眼清醒状态、放松警觉";
            case "beta" -> "主动思维、注意力集中";
            case "gamma" -> "意识绑定、高级认知功能";
            default -> "未知频段";
        };
    }
}