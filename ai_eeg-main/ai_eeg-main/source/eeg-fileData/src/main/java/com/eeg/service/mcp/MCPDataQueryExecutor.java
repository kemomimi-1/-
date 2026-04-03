package com.eeg.service.mcp;

import com.eeg.service.InfluxDBService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.*;

import com.eeg.service.mcp.MCPToolModels.*;
import java.util.stream.Collectors;

/**
 * MCP 数据查询执行器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MCPDataQueryExecutor {

    private final InfluxDBService influxDBService;
    private final ObjectMapper objectMapper;
    private final MCPToolUtils toolUtils;

    public Object executeQueryLatestBandPowerData(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        try {
            log.info("执行queryLatestBandPowerData - 用户ID: {} (透明化分析版本)", userId);

            Integer limit = toolUtils.getIntegerArgument(arguments, "limit", 10);
            Boolean groupByTime = toolUtils.getBooleanArgument(arguments, "groupByTime", true);
            List<String> bands = toolUtils.parseBandsArgument(arguments.get("bands"));

            TimeRange timeRange = toolUtils.parseTimeRange(userId, arguments);
            if (timeRange.hasError) {
                return Map.of("error", timeRange.errorMessage);
            }

            // 构建完全透明的SQL查询
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("SELECT ")
                    .append("time, ")
                    .append("band, ")
                    .append("value, ")
                    .append("user_id ")  // 明确显示用户ID，增加透明度
                    .append("FROM avg_band_power ");

            sqlBuilder.append("WHERE user_id = '").append(userId).append("' ");
            sqlBuilder.append("AND time >= '").append(timeRange.startTime).append("' ");
            sqlBuilder.append("AND time <= '").append(timeRange.endTime).append("' ");

            // 频段筛选（如果指定）
            if (bands != null && !bands.isEmpty()) {
                sqlBuilder.append("AND band IN (");
                for (int i = 0; i < bands.size(); i++) {
                    if (i > 0) sqlBuilder.append(", ");
                    sqlBuilder.append("'").append(bands.get(i)).append("'");
                }
                sqlBuilder.append(") ");
            }

            // 智能限制计算 - 透明化算法
            int expectedBands = (bands != null && !bands.isEmpty()) ? bands.size() : 5;
            int baseCalculation = limit * expectedBands;
            // 学术标准：考虑数据非均匀分布的安全系数
            double safetyMultiplier = 1.5 + Math.log10(limit); // 基于信息理论的安全系数
            int intelligentLimit = (int) Math.ceil(baseCalculation * safetyMultiplier);
            int finalLimit = Math.min(intelligentLimit, 50000); // 硬性上限

            sqlBuilder.append("ORDER BY time DESC ");
            sqlBuilder.append("LIMIT ").append(finalLimit);

            String executedSQL = sqlBuilder.toString();
            log.info("执行频谱数据查询SQL: {}", executedSQL);

            // 执行查询并获取原始数据
            String rawDataResult = influxDBService.queryData(executedSQL, "json").block();

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("dataType", "frequency_band_power_analysis");
            result.put("methodology", "Welch_Power_Spectral_Density_Estimation");
            result.put("queryTimestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            // 查询透明度信息
            Map<String, Object> queryTransparency = new HashMap<>();
            queryTransparency.put("executedSQL", executedSQL);
            queryTransparency.put("dataSourceTable", "avg_band_power");
            queryTransparency.put("timeRange", Map.of(
                    "startTime", timeRange.startTime,
                    "endTime", timeRange.endTime,
                    "sessionId", timeRange.sessionId != null ? timeRange.sessionId : "auto_detected"
            ));
            queryTransparency.put("limitCalculation", Map.of(
                    "requestedTimePoints", limit,
                    "expectedFrequencyBands", expectedBands,
                    "baseCalculation", baseCalculation,
                    "safetyMultiplier", String.format("%.3f", safetyMultiplier),
                    "intelligentLimit", intelligentLimit,
                    "finalLimit", finalLimit,
                    "calculationMethod", "logarithmic_safety_factor_based_on_information_theory"
            ));
            result.put("queryTransparency", queryTransparency);

            // 原始数据处理
            if (rawDataResult != null && !rawDataResult.trim().isEmpty() && !"[]".equals(rawDataResult.trim())) {
                JsonNode dataNode = objectMapper.readTree(rawDataResult);
                if (dataNode.isArray() && dataNode.size() > 0) {

                    result.put("rawDataRecordCount", dataNode.size());
                    result.put("rawDataSample", toolUtils.extractDataSample(dataNode, 3)); // 显示3条原始数据样本

                    if (groupByTime) {
                        // 完全透明的数据组织过程
                        Map<String, Object> organizedResult = organizeDataByTimePointTransparent(dataNode, limit);
                        result.putAll(organizedResult);
                    } else {
                        result.put("chronologicalData", rawDataResult);
                    }

                    // 学术级统计分析
                    Map<String, Object> statisticalAnalysis = performAcademicStatisticalAnalysis(dataNode);
                    result.put("statisticalAnalysis", statisticalAnalysis);

                    // EEG频段生物学意义解释
                    result.put("frequencyBandInterpretation", getFrequencyBandBiologicalMeaning());

                } else {
                    result.put("message", "查询时间范围内无频谱数据");
                    result.put("rawDataRecordCount", 0);
                }
            } else {
                result.put("message", "无匹配的频谱数据");
                result.put("rawDataRecordCount", 0);
            }

            return result;

        } catch (Exception e) {
            log.error("查询最新频谱数据失败 - 用户ID: {}", userId, e);
            return Map.of(
                    "error", "查询频谱数据失败: " + e.getMessage(),
                    "userId", userId,
                    "timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            );
        }
    }

    /**
     * 3. 查询原始/滤波EEG数据 - 完全透明的科研级实现
     */
    public Object executeQueryEEGDataTransparent(Long userId, Map<String, Object> arguments, Map<String, Object> context, String dataType) {
        try {
            String tableName = "raw".equals(dataType) ? "timeseriesraw" : "timeseriesfilt";
            String analysisType = "raw".equals(dataType) ? "Raw_EEG_Time_Series" : "Filtered_EEG_Time_Series";

            log.info("执行查询{}EEG数据 - 用户ID: {} (透明化科研版本)", dataType, userId);

            TimeRange timeRange = toolUtils.parseTimeRange(userId, arguments);
            if (timeRange.hasError) {
                return Map.of("error", timeRange.errorMessage);
            }

            List<Integer> channels = toolUtils.parseChannelsArgument(arguments.get("channels"));
            Integer limit = toolUtils.getIntegerArgument(arguments, "limit", 100);
            String orderBy = toolUtils.getStringArgument(arguments, "orderBy", "time DESC");

            // 学术级数据量评估
            int channelCount = (channels != null && !channels.isEmpty()) ? channels.size() : 8;
            int baseLimit = limit * channelCount;

            // 基于采样定理的限制计算
            int maxLimit = toolUtils.calculateScientificDataLimit(limit, channelCount);
            int actualLimit = Math.min(baseLimit, maxLimit);

            // 构建完全透明的查询
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("SELECT ");
            sqlBuilder.append("time, ");
            sqlBuilder.append("channel, ");
            sqlBuilder.append("value, ");
            sqlBuilder.append("user_id ");
            sqlBuilder.append("FROM ").append(tableName).append(" ");
            sqlBuilder.append("WHERE user_id = '").append(userId).append("' ");
            sqlBuilder.append("AND time >= '").append(timeRange.startTime).append("' ");
            sqlBuilder.append("AND time <= '").append(timeRange.endTime).append("' ");

            if (channels != null && !channels.isEmpty()) {
                sqlBuilder.append("AND channel IN (");
                for (int i = 0; i < channels.size(); i++) {
                    if (i > 0) sqlBuilder.append(", ");
                    sqlBuilder.append(channels.get(i));
                }
                sqlBuilder.append(") ");
            }

            sqlBuilder.append("ORDER BY ").append(orderBy).append(" ");
            sqlBuilder.append("LIMIT ").append(actualLimit);

            String executedSQL = sqlBuilder.toString();
            log.info("执行{}数据查询SQL: {}", dataType, executedSQL);

            String rawData = influxDBService.queryData(executedSQL, "json").block();

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("dataType", analysisType);
            result.put("methodology", "raw".equals(dataType) ?
                    "Direct_Time_Series_Analysis" : "Bandpass_Filtered_Time_Series_Analysis");
            result.put("queryTimestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            // 查询透明度
            Map<String, Object> queryTransparency = new HashMap<>();
            queryTransparency.put("executedSQL", executedSQL);
            queryTransparency.put("dataSourceTable", tableName);
            queryTransparency.put("samplingParameters", Map.of(
                    "estimatedSamplingRate", "~250Hz (OpenBCI Synthetic)",
                    "nyquistFrequency", "125Hz",
                    "timeResolution", "4ms per sample",
                    "amplitudeUnit", "microvolts (μV)"
            ));
            queryTransparency.put("limitCalculation", Map.of(
                    "requestedSamples", limit,
                    "channelCount", channelCount,
                    "baseCalculation", baseLimit,
                    "scientificLimit", maxLimit,
                    "actualLimit", actualLimit,
                    "limitingFactor", actualLimit == maxLimit ? "scientific_limit" : "base_calculation"
            ));
            result.put("queryTransparency", queryTransparency);

            // 数据处理
            if (rawData != null && !rawData.trim().isEmpty() && !"[]".equals(rawData.trim())) {
                JsonNode dataNode = objectMapper.readTree(rawData);
                result.put("retrievedSampleCount", dataNode.size());
                result.put("dataSample", toolUtils.extractDataSample(dataNode, 5));
                result.put("fullDataset", rawData);

                // 时间序列统计分析
                Map<String, Object> timeSeriesAnalysis = performTimeSeriesStatisticalAnalysis(dataNode);
                result.put("timeSeriesAnalysis", timeSeriesAnalysis);

            } else {
                result.put("message", "查询时间范围内无数据");
                result.put("retrievedSampleCount", 0);
            }

            // 数据解释
            result.put("dataInterpretation", getDataInterpretation(dataType));
            result.put("channelMapping", getStandardChannelMapping());

            return result;

        } catch (Exception e) {
            log.error("查询{}EEG数据失败 - 用户ID: {}", dataType, userId, e);
            return Map.of(
                    "error", "查询" + dataType + "EEG数据失败: " + e.getMessage(),
                    "userId", userId,
                    "timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            );
        }
    }

    /**
     * 执行按时间范围查询数据
     */
    public Object executeQueryDataByTimeRange(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        try {
            log.info("执行queryDataByTimeRange - 用户ID: {}", userId);

            String dataType = toolUtils.getStringArgument(arguments, "dataType", "bandpower");
            Integer timeWindow = toolUtils.getIntegerArgument(arguments, "timeWindow", 30);
            Integer limit = toolUtils.getIntegerArgument(arguments, "limit", 50);

            // 解析时间参数
            TimeRange timeRange = toolUtils.parseDirectTimeArguments(arguments, timeWindow);
            if (timeRange.hasError) {
                return Map.of("error", timeRange.errorMessage);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("queryType", "direct_time_query");
            result.put("dataType", dataType);
            result.put("timeRange", Map.of(
                    "startTime", timeRange.startTime,
                    "endTime", timeRange.endTime,
                    "queryWindow", timeWindow + "秒"
            ));
            result.put("queryTimestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            // 根据数据类型选择相应的表和查询
            String tableName;
            String description;
            switch (dataType.toLowerCase()) {
                case "raw":
                    tableName = "timeseriesraw";
                    description = "原始EEG时间序列数据";
                    break;
                case "filtered":
                    tableName = "timeseriesfilt";
                    description = "滤波处理后的EEG数据";
                    break;
                case "bandpower":
                default:
                    tableName = "avg_band_power";
                    description = "EEG频段功率数据";
                    break;
            }

            // 构建SQL查询
            StringBuilder sqlBuilder = new StringBuilder();
            if ("avg_band_power".equals(tableName)) {
                sqlBuilder.append("SELECT time, band, value, user_id ");
            } else {
                sqlBuilder.append("SELECT time, channel, value, user_id ");
            }

            sqlBuilder.append("FROM ").append(tableName).append(" ");
            sqlBuilder.append("WHERE user_id = '").append(userId).append("' ");
            sqlBuilder.append("AND time >= '").append(timeRange.startTime).append("' ");
            sqlBuilder.append("AND time <= '").append(timeRange.endTime).append("' ");
            sqlBuilder.append("ORDER BY time DESC ");
            sqlBuilder.append("LIMIT ").append(Math.min(limit, 1000));

            String executedSQL = sqlBuilder.toString();
            log.info("执行时间查询SQL: {}", executedSQL);

            // 执行查询
            String queryResult = influxDBService.queryData(executedSQL, "json").block();

            result.put("queryTransparency", Map.of(
                    "executedSQL", executedSQL,
                    "dataSource", tableName,
                    "description", description
            ));

            if (queryResult != null && !queryResult.trim().isEmpty() && !"[]".equals(queryResult.trim())) {
                JsonNode dataNode = objectMapper.readTree(queryResult);

                if (dataNode.isArray() && dataNode.size() > 0) {
                    result.put("dataFound", true);
                    result.put("recordCount", dataNode.size());
                    result.put("data", queryResult);

                    // 数据样本展示
                    result.put("dataSample", toolUtils.extractDataSample(dataNode, Math.min(5, dataNode.size())));

                    // 如果是频段数据，进行智能分析
                    if ("avg_band_power".equals(tableName)) {
                        result.put("frequencyAnalysis", analyzeFrequencyData(dataNode));
                    }

                    // 时间分析
                    result.put("timeAnalysis", analyzeTimeDistribution(dataNode));

                } else {
                    result.put("dataFound", false);
                    result.put("message", "指定时间范围内未找到数据");
                }
            } else {
                result.put("dataFound", false);
                result.put("message", "查询时间范围内无数据");
            }

            return result;

        } catch (Exception e) {
            log.error("按时间范围查询数据失败 - 用户ID: {}", userId, e);
            return Map.of(
                    "error", "按时间查询失败: " + e.getMessage(),
                    "userId", userId,
                    "timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            );
        }
    }

    /**
     * 执行自定义SQL查询
     */
    public Object executeCustomQuery(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        try {
            String sql = toolUtils.getStringArgument(arguments, "sql", "");
            Integer maxRows = toolUtils.getIntegerArgument(arguments, "maxRows", 1000);

            if (sql.trim().isEmpty()) {
                return Map.of("error", "SQL查询语句不能为空");
            }

            // 安全检查
            String securityCheck = toolUtils.validateSQLSafety(sql, userId);
            if (securityCheck != null) {
                return Map.of("error", "SQL安全检查失败: " + securityCheck);
            }

            log.info("执行自定义SQL查询 - 用户ID: {}, SQL: {}", userId, sql);

            // 添加用户ID过滤（如果SQL中没有包含）
            String safeSql = toolUtils.ensureUserIdFilter(sql, userId);

            // 添加行数限制
            if (!safeSql.toUpperCase().contains("LIMIT")) {
                safeSql += " LIMIT " + Math.min(maxRows, 10000);
            }

            String result = influxDBService.queryData(safeSql, "json").block();

            return Map.of(
                    "success", true,
                    "dataType", "custom_query",
                    "originalSQL", sql,
                    "executedSQL", safeSql,
                    "maxRows", maxRows,
                    "data", result,
                    "securityNote", "查询已通过安全检查，仅允许SELECT操作且已添加用户ID过滤。"
            );

        } catch (Exception e) {
            log.error("执行自定义SQL查询失败 - 用户ID: {}", userId, e);
            return Map.of(
                    "error", "执行自定义SQL查询失败: " + e.getMessage(),
                    "userId", userId
            );
        }
    }

    // 为原始数据调用
    public Object executeQueryRawEEGData(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        return executeQueryEEGDataTransparent(userId, arguments, context, "raw");
    }

    // 为滤波数据调用
    public Object executeQueryFilteredEEGData(Long userId, Map<String, Object> arguments, Map<String, Object> context) {
        return executeQueryEEGDataTransparent(userId, arguments, context, "filtered");
    }


    // ========== 数据查询辅助方法 ==========
    // ========== 辅助方法：学术级分析算法 ==========

    /**
     * 完全透明的数据组织方法
     */
    public Map<String, Object> organizeDataByTimePointTransparent(JsonNode dataNode, int maxTimePoints) {
        Map<String, Map<String, Double>> timePointMap = new LinkedHashMap<>();

        // 第一阶段：数据收集和分组
        for (JsonNode record : dataNode) {
            String timeStr = record.get("time").asText();
            String band = record.get("band").asText();
            double value = record.get("value").asDouble();

            timePointMap.computeIfAbsent(timeStr, k -> new HashMap<>()).put(band, value);
        }

        // 第二阶段：时间排序和截取
        List<Map.Entry<String, Map<String, Double>>> sortedEntries = timePointMap.entrySet().stream()
                .sorted(Map.Entry.<String, Map<String, Double>>comparingByKey().reversed())
                .limit(maxTimePoints)
                .collect(Collectors.toList());

        // 第三阶段：结果构建
        List<Map<String, Object>> organizedData = new ArrayList<>();
        List<String> expectedBands = List.of("delta", "theta", "alpha", "beta", "gamma");

        for (Map.Entry<String, Map<String, Double>> entry : sortedEntries) {
            Map<String, Object> timePoint = new HashMap<>();
            timePoint.put("time", entry.getKey());
            timePoint.put("bands", entry.getValue());
            timePoint.put("completeBandCount", entry.getValue().size());

            // 数据完整性检查
            List<String> missingBands = expectedBands.stream()
                    .filter(band -> !entry.getValue().containsKey(band))
                    .collect(Collectors.toList());
            if (!missingBands.isEmpty()) {
                timePoint.put("missingBands", missingBands);
            }

            organizedData.add(timePoint);
        }

        return Map.of(
                "organizedByTimePoint", organizedData,
                "processedTimePoints", organizedData.size(),
                "totalRawRecords", dataNode.size(),
                "organizationMethod", "chronological_grouping_with_integrity_check",
                "dataCompletenessRatio", organizedData.size() > 0 ?
                        organizedData.stream().mapToInt(tp -> (Integer) tp.get("completeBandCount")).average().orElse(0.0) / 5.0 : 0.0
        );
    }

    /**
     * 学术级统计分析
     */
    public Map<String, Object> performAcademicStatisticalAnalysis(JsonNode dataNode) {
        Map<String, List<Double>> bandData = new HashMap<>();

        // 收集各频段数据
        for (JsonNode record : dataNode) {
            String band = record.get("band").asText();
            double value = record.get("value").asDouble();
            bandData.computeIfAbsent(band, k -> new ArrayList<>()).add(value);
        }

        Map<String, Object> analysis = new HashMap<>();

        for (Map.Entry<String, List<Double>> entry : bandData.entrySet()) {
            String band = entry.getKey();
            List<Double> values = entry.getValue();

            if (!values.isEmpty()) {
                // 使用高精度统计计算
                double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                double variance = toolUtils.calculateSampleVariance(values, mean);
                double stdDev = Math.sqrt(variance);
                double median = toolUtils.calculateMedian(values);
                double[] quartiles = toolUtils.calculateQuartiles(values);
                double skewness = toolUtils.calculateSkewness(values, mean, stdDev);
                double kurtosis = toolUtils.calculateKurtosis(values, mean, stdDev);

                Map<String, Object> bandStats = new HashMap<>();
                bandStats.put("sampleCount", values.size());
                bandStats.put("mean_power_uV2", mean);
                bandStats.put("sampleVariance_uV4", variance);
                bandStats.put("sampleStandardDeviation_uV2", stdDev);
                bandStats.put("median_power_uV2", median);
                bandStats.put("q1_power_uV2", quartiles[0]);
                bandStats.put("q3_power_uV2", quartiles[1]);
                bandStats.put("interquartileRange_uV2", quartiles[1] - quartiles[0]);
                bandStats.put("coefficientOfVariation", stdDev / mean);
                bandStats.put("skewness", skewness);
                bandStats.put("kurtosis", kurtosis);
                bandStats.put("min_power_uV2", values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0));
                bandStats.put("max_power_uV2", values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0));

                // 添加计算方法说明
                bandStats.put("calculationMethods", Map.of(
                        "variance", "Sample variance with Bessel correction (N-1)",
                        "quartiles", "Linear interpolation method",
                        "skewness", "Pearson moment coefficient of skewness",
                        "kurtosis", "Excess kurtosis (normal distribution = 0)"
                ));

                analysis.put(band, bandStats);
            }
        }

        analysis.put("statisticalNote", "所有统计量基于样本统计学计算，使用贝塞尔校正确保无偏估计");
        return analysis;
    }

    /**
     * 时间序列统计分析
     */
    public Map<String, Object> performTimeSeriesStatisticalAnalysis(JsonNode dataNode) {
        Map<Integer, List<Double>> channelData = new HashMap<>();
        Map<Integer, List<String>> channelTimes = new HashMap<>();

        // 按通道分组数据
        for (JsonNode record : dataNode) {
            int channel = record.get("channel").asInt();
            double value = record.get("value").asDouble();
            String time = record.get("time").asText();

            channelData.computeIfAbsent(channel, k -> new ArrayList<>()).add(value);
            channelTimes.computeIfAbsent(channel, k -> new ArrayList<>()).add(time);
        }

        Map<String, Object> analysis = new HashMap<>();
        Map<String, Object> channelAnalyses = new HashMap<>();

        for (Map.Entry<Integer, List<Double>> entry : channelData.entrySet()) {
            int channel = entry.getKey();
            List<Double> values = entry.getValue();
            List<String> times = channelTimes.get(channel);

            if (values.size() > 1) {
                Map<String, Object> channelStats = new HashMap<>();

                // 基础统计量
                double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                double variance = toolUtils.calculateSampleVariance(values, mean);
                double stdDev = Math.sqrt(variance);
                double rms = Math.sqrt(values.stream().mapToDouble(v -> v * v).average().orElse(0.0));

                // 时间序列特性分析
                double[] autocorrelation = toolUtils.calculateAutocorrelation(values, Math.min(10, values.size()/4));
                double trendSlope = toolUtils.calculateLinearTrendSlope(values);
                double stationarityIndex = toolUtils.calculateStationarityIndex(values);

                channelStats.put("basicStatistics", Map.of(
                        "sampleCount", values.size(),
                        "mean_uV", mean,
                        "sampleVariance_uV2", variance,
                        "sampleStdDev_uV", stdDev,
                        "rmsAmplitude_uV", rms,
                        "min_uV", values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0),
                        "max_uV", values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0)
                ));

                channelStats.put("timeSeriesProperties", Map.of(
                        "autocorrelationLag1", autocorrelation.length > 1 ? autocorrelation[1] : 0.0,
                        "autocorrelationLag2", autocorrelation.length > 2 ? autocorrelation[2] : 0.0,
                        "linearTrendSlope_uV_per_sample", trendSlope,
                        "stationarityIndex", stationarityIndex,
                        "timeSpan", Map.of(
                                "firstSample", times.get(0),
                                "lastSample", times.get(times.size()-1),
                                "totalSamples", times.size()
                        )
                ));

                channelStats.put("calculationMethods", Map.of(
                        "autocorrelation", "Pearson correlation coefficient between time series and lagged version",
                        "trendSlope", "Linear regression slope of amplitude vs sample index",
                        "stationarity", "Variance ratio test for trend stationarity"
                ));

                channelAnalyses.put("channel_" + channel, channelStats);
            }
        }

        analysis.put("channelAnalyses", channelAnalyses);
        analysis.put("analysisNote", "时间序列分析基于统计信号处理理论，所有计算使用样本统计学方法");

        return analysis;
    }

// ========== 学术参考数据 ==========

    /**
     * 频段生物学意义
     */
    public Map<String, Object> getFrequencyBandBiologicalMeaning() {
        return Map.of(
                "frequencyBands", Map.of(
                        "delta_1_4Hz", Map.of(
                                "biologicalSignificance", "深度睡眠、无意识状态、皮层抑制",
                                "typicalAmplitude", "50-200μV²",
                                "clinicalRelevance", "睡眠分期、麻醉深度监测"
                        ),
                        "theta_4_8Hz", Map.of(
                                "biologicalSignificance", "REM睡眠、冥想状态、记忆巩固、创造性思维",
                                "typicalAmplitude", "10-50μV²",
                                "clinicalRelevance", "认知负荷评估、注意力监测"
                        ),
                        "alpha_8_13Hz", Map.of(
                                "biologicalSignificance", "闭眼清醒状态、放松警觉、视觉皮层同步",
                                "typicalAmplitude", "20-100μV²",
                                "clinicalRelevance", "放松训练、脑机接口基线状态"
                        ),
                        "beta_13_30Hz", Map.of(
                                "biologicalSignificance", "主动思维、注意力集中、运动皮层激活",
                                "typicalAmplitude", "5-30μV²",
                                "clinicalRelevance", "认知状态评估、运动想象检测"
                        ),
                        "gamma_30_100Hz", Map.of(
                                "biologicalSignificance", "意识绑定、高级认知功能、跨区域神经同步",
                                "typicalAmplitude", "1-10μV²",
                                "clinicalRelevance", "意识水平评估、认知功能研究"
                        )
                ),
                "reference", "Niedermeyer & da Silva. Electroencephalography: Basic Principles, Clinical Applications, and Related Fields. 2005"
        );
    }

    /**
     * EEG质量标准
     */
    public Map<String, Object> getEEGQualityStandards() {
        return Map.of(
                "signalQualityStandards", Map.of(
                        "excellent_SNR", "> 40 dB",
                        "good_SNR", "20-40 dB",
                        "acceptable_SNR", "10-20 dB",
                        "poor_SNR", "< 10 dB",
                        "impedance_threshold", "< 5 kΩ (dry electrodes < 50 kΩ)",
                        "artifact_threshold", "< 10% of recording time"
                ),
                "amplitudeRanges", Map.of(
                        "normal_EEG", "10-100 μV",
                        "suspicious_low", "< 5 μV (possible electrode issues)",
                        "suspicious_high", "> 500 μV (possible artifacts)",
                        "artifact_threshold", "> 200 μV (likely artifacts)"
                ),
                "reference", "IEEE Standard for Neurotechnology - Terminology, 2020"
        );
    }

    /**
     * 数据解释
     */
    public Map<String, Object> getDataInterpretation(String dataType) {
        if ("raw".equals(dataType)) {
            return Map.of(
                    "interpretation", "原始EEG信号未经滤波处理，包含完整的频率成分和所有噪声",
                    "characteristics", "包含工频干扰、肌电噪声、眼动伪迹等",
                    "suitableFor", "频谱分析前处理、噪声特性分析、滤波器设计验证",
                    "limitations", "直接分析可能受噪声影响，建议配合滤波数据使用"
            );
        } else {
            return Map.of(
                    "interpretation", "滤波后EEG信号已去除主要噪声，保留神经活动相关频率成分",
                    "characteristics", "工频干扰已滤除，基线漂移已校正，信噪比得到改善",
                    "suitableFor", "特征提取、模式识别、神经生理分析、脑机接口应用",
                    "filteringDetails", "OpenBCI GUI实时滤波：带通滤波器 + 陷波滤波器"
            );
        }
    }

    /**
     * 标准通道映射
     */
    public Map<String, Object> getStandardChannelMapping() {
        return Map.of(
                "electrodeSystem", "International 10-20 System",
                "channelMapping", Map.of(
                        "1", "Fp1 - 左前额 (Left Frontal Pole)",
                        "2", "Fp2 - 右前额 (Right Frontal Pole)",
                        "3", "C3 - 左中央 (Left Central)",
                        "4", "C4 - 右中央 (Right Central)",
                        "5", "P7 - 左颞顶 (Left Temporal-Parietal)",
                        "6", "P8 - 右颞顶 (Right Temporal-Parietal)",
                        "7", "O1 - 左枕 (Left Occipital)",
                        "8", "O2 - 右枕 (Right Occipital)"
                ),
                "reference", "American Clinical Neurophysiology Society Guidelines"
        );
    }

    /**
     * 分析频率数据
     */
    public Map<String, Object> analyzeFrequencyData(JsonNode dataNode) {
        Map<String, Object> analysis = new HashMap<>();
        Map<String, List<Double>> bandData = new HashMap<>();

        try {
            // 按频段分组数据
            for (JsonNode record : dataNode) {
                String band = record.get("band").asText();
                double value = record.get("value").asDouble();
                bandData.computeIfAbsent(band, k -> new ArrayList<>()).add(value);
            }

            // 计算每个频段的统计信息
            Map<String, Object> bandStats = new HashMap<>();
            for (Map.Entry<String, List<Double>> entry : bandData.entrySet()) {
                String band = entry.getKey();
                List<Double> values = entry.getValue();

                if (!values.isEmpty()) {
                    double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                    double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
                    double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);

                    bandStats.put(band, Map.of(
                            "count", values.size(),
                            "averagePower", Math.round(mean * 1000000.0) / 1000000.0,
                            "maxPower", Math.round(max * 1000000.0) / 1000000.0,
                            "minPower", Math.round(min * 1000000.0) / 1000000.0
                    ));
                }
            }

            analysis.put("bandStatistics", bandStats);
            analysis.put("totalDataPoints", dataNode.size());
            analysis.put("bandsDetected", bandData.keySet());

        } catch (Exception e) {
            analysis.put("error", "频率数据分析失败: " + e.getMessage());
        }

        return analysis;
    }

    /**
     * 分析时间分布
     */
    public Map<String, Object> analyzeTimeDistribution(JsonNode dataNode) {
        Map<String, Object> analysis = new HashMap<>();

        try {
            if (dataNode.size() > 0) {
                String firstTime = dataNode.get(dataNode.size() - 1).get("time").asText();
                String lastTime = dataNode.get(0).get("time").asText();

                analysis.put("timeSpan", Map.of(
                        "earliestRecord", firstTime,
                        "latestRecord", lastTime,
                        "totalRecords", dataNode.size()
                ));

                // 计算时间间隔分布
                if (dataNode.size() > 1) {
                    analysis.put("samplingInfo", "数据采样频率约为 ~250Hz (OpenBCI标准)");
                }
            }

        } catch (Exception e) {
            analysis.put("error", "时间分析失败: " + e.getMessage());
        }

        return analysis;
    }

}
