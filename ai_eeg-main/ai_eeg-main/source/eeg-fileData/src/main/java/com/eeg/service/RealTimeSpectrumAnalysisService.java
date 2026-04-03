// RealTimeSpectrumAnalysisService.java - 简化版删除功能
package com.eeg.service;

import com.eeg.entity.Barrage;
import com.eeg.repository.BarrageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class RealTimeSpectrumAnalysisService {

    private final InfluxDBService influxDBService;
    private final BarrageRepository barrageRepository;
    private final WebSocketNotificationService webSocketService;
    private final ObjectMapper objectMapper;
    private final AIModelService aiModelService;

    // 活跃用户追踪
    private final Set<Long> activeUsers = ConcurrentHashMap.newKeySet();

    // 用户最后分析时间跟踪，实现持续采样 - 【关键修复】
    private final ConcurrentHashMap<Long, LocalDateTime> lastAnalysisTimes = new ConcurrentHashMap<>();

    @Value("${eeg.realtime-analysis.sample-window-minutes:2}")
    private int sampleWindowMinutes;

    @Value("${eeg.realtime-analysis.min-samples:10}")
    private int minSamples;

    @Value("${eeg.realtime-analysis.analysis-interval-seconds:30}")
    private int analysisIntervalSeconds;

    // ========== 原有功能保持不变 ==========

    /**
     * 启动用户的实时分析
     */
    public void startRealTimeAnalysis(Long userId) {
        activeUsers.add(userId);

        // 【关键修复】初始化用户的分析起始点为当前最新数据时间
        initializeUserAnalysisStartPoint(userId);

        log.info("启动用户 {} 的实时频谱分析，初始分析时间点: {}",
                userId, lastAnalysisTimes.get(userId));

        // 立即执行一次分析
        performAnalysisForUser(userId);
    }

    /**
     * 初始化用户分析起始点为最新数据时间点的稍早处，
     * 使得首次分析能立即查到数据（time > cursor）。
     */
    private void initializeUserAnalysisStartPoint(Long userId) {
        try {
            // 获取用户最新一批数据里最早的那条时间，作为游标起点
            String query = String.format(
                    "SELECT time FROM avg_band_power " +
                            "WHERE user_id = '%d' " +
                            "ORDER BY time DESC " +
                            "LIMIT %d",
                    userId,
                    minSamples * 5
            );

            String jsonResponse = influxDBService.queryData(query, "json").block();
            if (jsonResponse != null && !jsonResponse.trim().isEmpty() && !jsonResponse.equals("[]")) {
                JsonNode rootNode = objectMapper.readTree(jsonResponse);
                if (rootNode.isArray() && rootNode.size() > 0) {
                    // 取最后一条（最旧的那条），作为游标起点，这样 time > cursor 能查出所有这批数据
                    JsonNode lastRecord = rootNode.get(rootNode.size() - 1);
                    String timeStr = null;

                    if (lastRecord.isArray() && lastRecord.size() >= 1) {
                        timeStr = lastRecord.get(0).asText();
                    } else if (lastRecord.isObject()) {
                        timeStr = lastRecord.get("time") != null ? lastRecord.get("time").asText() : null;
                    }

                    if (timeStr != null) {
                        LocalDateTime cursorTime = parseTimeString(timeStr);
                        if (cursorTime != null) {
                            // 再往前推1秒，确保 time > cursor 能取到这条数据
                            cursorTime = cursorTime.minusSeconds(1);
                            lastAnalysisTimes.put(userId, cursorTime);
                            log.info("用户 {} 分析游标初始化为: {}", userId, cursorTime);
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取用户 {} 最新数据时间失败，使用默认策略", userId, e);
        }

        // fallback: 从5分钟前开始追新数据
        lastAnalysisTimes.put(userId, LocalDateTime.now().minusMinutes(5));
        log.info("用户 {} 分析游标使用默认值（当前时间-5分钟）", userId);
    }

    /**
     * 检查用户的实时分析是否处于活跃状态
     */
    public boolean isAnalysisActive(Long userId) {
        return activeUsers.contains(userId);
    }

    /**
     * 【新增】执行一次性分析（不启动持续循环）
     */
    public void performSingleAnalysis(Long userId) {
        log.info("用户 {} 触发一次性分析", userId);
        // 临时初始化起始点（如果没有的话）
        if (!lastAnalysisTimes.containsKey(userId)) {
            initializeUserAnalysisStartPoint(userId);
        }
        performAnalysisForUser(userId);
    }

    /**
     * 停止用户的实时分析
     */
    public void stopRealTimeAnalysis(Long userId) {
        activeUsers.remove(userId);
        lastAnalysisTimes.remove(userId);
        log.info("停止用户 {} 的实时频谱分析", userId);
    }

    /**
     * 定时分析任务 - 每30秒执行一次
     */
    @Scheduled(fixedRateString = "${eeg.realtime-analysis.analysis-interval-seconds:30}000")
    @Async
    public void performScheduledAnalysis() {
        if (activeUsers.isEmpty()) {
            return;
        }

        log.debug("开始定时频谱分析，活跃用户数: {}", activeUsers.size());

        for (Long userId : activeUsers) {
            try {
                performAnalysisForUser(userId);
            } catch (Exception e) {
                log.error("用户 {} 的定时分析失败", userId, e);
            }
        }
    }

    /**
     * 为特定用户执行分析 - 【核心修复】实现真正的持续向前推进
     */
    @SuppressWarnings("null")
    private void performAnalysisForUser(Long userId) {
        try {
            log.debug("开始为用户 {} 执行分析", userId);

            // 【关键修复】使用持续采样策略，确保每次获取不同时间段的数据
            SpectralData spectralData = getContinuousSpectralData(userId);

            if (spectralData == null || spectralData.getSampleCount() < minSamples) {
                log.warn("用户 {} 无法获取足够的数据样本: {}/{}",
                        userId, spectralData != null ? spectralData.getSampleCount() : 0, minSamples);
                return;
            }

            log.info("用户 {} 获取到 {} 个样本数据，时间范围: {} ~ {}",
                    userId, spectralData.getSampleCount(), spectralData.getStartTime(), spectralData.getEndTime());

            // 执行频谱分析
            AnalysisResult analysisResult = analyzeSpectralData(spectralData);

            // 生成弹幕消息
            Barrage barrage = createBarrageMessage(userId, spectralData, analysisResult);

            // 保存弹幕
            Barrage savedBarrage = barrageRepository.save(barrage);

            // 通过WebSocket推送弹幕
            sendBarrageNotification(userId, savedBarrage);

            // 更新游标为本次数据最新时间，下次从这里往后查
            updateAnalysisTimePoint(userId, spectralData.getEndTime());

            log.info("用户 {} 生成新弹幕: {}", userId, savedBarrage.getContent());

        } catch (Exception e) {
            log.error("用户 {} 的频谱分析失败", userId, e);
        }
    }

    /**
     * 更新分析时间游标为本次数据的最新时间，下次查询 time > cursor 时取到更新的数据
     */
    private void updateAnalysisTimePoint(Long userId, LocalDateTime newestDataTime) {
        lastAnalysisTimes.put(userId, newestDataTime);
        log.debug("用户 {} 更新分析游标为: {}", userId, newestDataTime);
    }

    /**
     * 获取持续频谱数据 - 每次取比游标更新的数据，游标不断正向推进
     */
    private SpectralData getContinuousSpectralData(Long userId) {
        return getContinuousSpectralDataWithDepth(userId, 0);
    }

    private SpectralData getContinuousSpectralDataWithDepth(Long userId, int depth) {
        if (depth > 1) {
            log.warn("用户 {} 数据采样已达最大重试次数，暂无新数据", userId);
            return null;
        }

        LocalDateTime cursor = lastAnalysisTimes.get(userId);

        if (cursor == null) {
            log.warn("用户 {} 分析游标为空，重新初始化", userId);
            initializeUserAnalysisStartPoint(userId);
            cursor = lastAnalysisTimes.get(userId);
            if (cursor == null) {
                log.error("用户 {} 无法初始化分析游标", userId);
                return null;
            }
        }

        try {
            // 【核心修复】查比游标更新的数据，游标正向推进
            String query = String.format(
                    "SELECT time, band, value FROM avg_band_power " +
                            "WHERE user_id = '%d' AND time > '%s' " +
                            "ORDER BY time ASC " +
                            "LIMIT %d",
                    userId,
                    cursor.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")),
                    minSamples * 5
            );

            log.debug("用户 {} 持续采样查询 - 游标: {}", userId, cursor);

            String jsonResponse = influxDBService.queryData(query, "json").block();

            if (jsonResponse != null && !jsonResponse.trim().isEmpty() && !jsonResponse.equals("[]")) {
                SpectralData data = parseSpectralData(jsonResponse);

                if (data != null && data.getSampleCount() >= minSamples) {
                    log.info("用户 {} 持续采样成功 - 样本数: {}, 时间范围: {} ~ {}",
                            userId, data.getSampleCount(), data.getStartTime(), data.getEndTime());
                    return data;
                } else {
                    log.warn("用户 {} 新数据不足 - 样本数: {}/{}",
                            userId, data != null ? data.getSampleCount() : 0, minSamples);
                }
            } else {
                log.debug("用户 {} 暂无新数据（游标: {}），等待下次调度", userId, cursor);
            }

            // 没有足够的新数据时，不重置游标，直接返回 null 等待下次调度
            return null;

        } catch (Exception e) {
            log.error("用户 {} 持续数据采样失败", userId, e);
            return null;
        }
    }

    // ========== 删除弹幕功能 ==========

    /**
     * 删除单条弹幕
     * @param userId 用户ID
     * @param barrageId 弹幕ID
     * @return 删除结果
     */
    @Transactional
    public boolean deleteBarrage(Long userId, Long barrageId) {
        try {
            // 验证弹幕是否存在且属于该用户
            Optional<Barrage> barrageOpt = barrageRepository.findByIdAndUserId(barrageId, userId);

            if (barrageOpt.isEmpty()) {
                log.warn("用户 {} 尝试删除不存在或不属于自己的弹幕: {}", userId, barrageId);
                return false;
            }

            Barrage barrage = barrageOpt.get();

            // 删除弹幕
            int deletedCount = barrageRepository.deleteByIdAndUserId(barrageId, userId);

            if (deletedCount > 0) {
                log.info("用户 {} 成功删除弹幕 {} - 内容: {}", userId, barrageId, barrage.getContent());

                // 发送删除通知
                sendBarrageDeletionNotification(userId, barrageId, barrage.getContent());

                return true;
            } else {
                log.error("用户 {} 删除弹幕 {} 失败 - 数据库操作返回0", userId, barrageId);
                return false;
            }

        } catch (Exception e) {
            log.error("用户 {} 删除弹幕 {} 时发生异常", userId, barrageId, e);
            return false;
        }
    }

    /**
     * 发送弹幕删除通知
     */
    private void sendBarrageDeletionNotification(Long userId, Long barrageId, String content) {
        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("type", "BARRAGE_DELETED");
            notification.put("barrageId", barrageId);
            notification.put("content", content);
            notification.put("timestamp", System.currentTimeMillis());

            webSocketService.notifyUser(userId, "BARRAGE_DELETION", notification);
        } catch (Exception e) {
            log.error("发送弹幕删除通知失败 - 用户: {}, 弹幕ID: {}", userId, barrageId, e);
        }
    }

    /**
     * 解析InfluxDB返回的频谱数据
     */
    private SpectralData parseSpectralData(String jsonResponse) {
        try {
            log.debug("开始解析频谱数据，JSON长度: {}", jsonResponse.length());

            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            SpectralData spectralData = new SpectralData();

            Map<String, List<Double>> bandValues = new HashMap<>();
            bandValues.put("alpha", new ArrayList<>());
            bandValues.put("beta", new ArrayList<>());
            bandValues.put("theta", new ArrayList<>());
            bandValues.put("delta", new ArrayList<>());
            bandValues.put("gamma", new ArrayList<>());

            LocalDateTime firstTime = null;
            LocalDateTime lastTime = null;

            // 处理InfluxDB 3.x的不同返回格式
            if (rootNode.isArray()) {
                log.debug("处理数组格式的JSON响应，记录数: {}", rootNode.size());

                for (JsonNode record : rootNode) {
                    try {
                        String timeStr = null;
                        String band = null;
                        double value = 0.0;

                        if (record.isArray() && record.size() >= 3) {
                            // 数组格式: [time, band, value]
                            timeStr = record.get(0).asText();
                            band = record.get(1).asText();
                            value = record.get(2).asDouble();
                        } else if (record.isObject()) {
                            // 对象格式: {"time": "...", "band": "...", "value": ...}
                            timeStr = record.get("time") != null ? record.get("time").asText() : null;
                            band = record.get("band") != null ? record.get("band").asText() : null;
                            value = record.get("value") != null ? record.get("value").asDouble() : 0.0;
                        }

                        if (timeStr == null || band == null) {
                            continue;
                        }

                        LocalDateTime recordTime = parseTimeString(timeStr);
                        if (recordTime == null) {
                            continue;
                        }

                        // ORDER BY time ASC：第一条是最早时间，最后一条是最新时间
                        if (firstTime == null) {
                            firstTime = recordTime; // 最早时间（游标后第一条）
                        }
                        lastTime = recordTime; // 持续更新为最新时间

                        if (bandValues.containsKey(band)) {
                            bandValues.get(band).add(value);
                        }

                    } catch (Exception e) {
                        log.debug("解析单个数据记录失败", e);
                    }
                }
            }

            if (firstTime != null && lastTime != null) {
                spectralData.setStartTime(firstTime);  // 最早时间
                spectralData.setEndTime(lastTime);     // 最新时间
                spectralData.setBandValues(bandValues);

                int totalSamples = bandValues.values().stream().mapToInt(List::size).sum();
                spectralData.setSampleCount(totalSamples);

                log.debug("解析完成 - 总样本数: {}, 时间范围: {} ~ {}", totalSamples, firstTime, lastTime);

                if (totalSamples > 0) {
                    return spectralData;
                }
            }

            return new SpectralData();

        } catch (Exception e) {
            log.error("解析频谱数据失败", e);
            return new SpectralData();
        }
    }

    /**
     * 解析时间字符串 - 支持多种格式
     */
    private LocalDateTime parseTimeString(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return null;
        }

        try {
            // 处理常见的时间格式
            String[] formats = {
                    "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS",
                    "yyyy-MM-dd'T'HH:mm:ss",
                    "yyyy-MM-dd HH:mm:ss.SSSSSS",
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    "yyyy-MM-dd HH:mm:ss"
            };

            for (String format : formats) {
                try {
                    String timeStringToUse = timeStr.length() > format.length() ?
                            timeStr.substring(0, format.length()) : timeStr;
                    return LocalDateTime.parse(timeStringToUse, DateTimeFormatter.ofPattern(format));
                } catch (Exception ignored) {
                    // 继续尝试下一个格式
                }
            }

            // 最后尝试ISO格式
            if (timeStr.contains("T") && timeStr.length() >= 19) {
                return LocalDateTime.parse(timeStr.substring(0, 19));
            }

        } catch (Exception e) {
            log.trace("解析时间字符串失败: {}", timeStr);
        }

        return null;
    }

    /**
     * 分析频谱数据 - 基于科学的脑电频段特征
     */
    private AnalysisResult analyzeSpectralData(SpectralData data) {
        AnalysisResult result = new AnalysisResult();

        // 计算各频段的平均值
        Map<String, Double> averages = new HashMap<>();
        for (Map.Entry<String, List<Double>> entry : data.getBandValues().entrySet()) {
            double avg = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            averages.put(entry.getKey(), avg);
        }

        result.setAverages(averages);

        // 归一化处理
        double totalPower = averages.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Double> normalized = new HashMap<>();
        if (totalPower > 0) {
            for (Map.Entry<String, Double> entry : averages.entrySet()) {
                normalized.put(entry.getKey(), entry.getValue() / totalPower);
            }
        } else {
            // 防止除零错误
            normalized.put("alpha", 0.2);
            normalized.put("beta", 0.2);
            normalized.put("theta", 0.2);
            normalized.put("delta", 0.2);
            normalized.put("gamma", 0.2);
        }
        result.setNormalized(normalized);

        // 分析精神状态
        analyzeMentalState(result, normalized);

        // 计算置信度
        result.setConfidenceScore(calculateConfidenceScore(data.getSampleCount(), normalized));

        return result;
    }

    /**
     * 分析精神状态 - 基于科学的脑电频段特征
     */
    private void analyzeMentalState(AnalysisResult result, Map<String, Double> normalized) {
        double alpha = normalized.getOrDefault("alpha", 0.0);
        double beta = normalized.getOrDefault("beta", 0.0);
        double theta = normalized.getOrDefault("theta", 0.0);
        double delta = normalized.getOrDefault("delta", 0.0);
        double gamma = normalized.getOrDefault("gamma", 0.0);

        // 找出主导频率
        String dominantBand = normalized.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("gamma");

        result.setDominantFrequency(dominantBand);

        // 基于科学研究的状态判断逻辑
        Barrage.MentalState mentalState;
        Barrage.AlertLevel alertLevel = Barrage.AlertLevel.NORMAL;
        String recommendation = "";

        if (delta > 0.4) {
            // Delta波占主导 - 深度睡眠或极度疲劳
            mentalState = Barrage.MentalState.DROWSY;
            alertLevel = Barrage.AlertLevel.ATTENTION;
            recommendation = "建议休息或检查是否过度疲劳";

        } else if (theta > 0.3 && alpha > 0.2) {
            // Theta + Alpha 组合 - 冥想或创造性状态
            mentalState = Barrage.MentalState.MEDITATIVE;
            recommendation = "保持当前放松状态，有利于创造性思维";

        } else if (alpha > 0.35) {
            // Alpha波占主导 - 放松状态
            mentalState = alpha > 0.5 ? Barrage.MentalState.DEEP_RELAXATION : Barrage.MentalState.RELAXED;
            recommendation = "当前处于理想的放松状态";

        } else if (beta > 0.4) {
            if (beta > 0.6) {
                // 高Beta - 可能过度紧张
                mentalState = Barrage.MentalState.STRESSED;
                alertLevel = Barrage.AlertLevel.WARNING;
                recommendation = "Beta波过高，建议进行放松练习";
            } else {
                // 适度Beta - 专注状态
                mentalState = Barrage.MentalState.FOCUSED;
                recommendation = "当前专注度良好，适合学习工作";
            }

        } else if (gamma > 0.4) {
            if (gamma > 0.6) {
                // 过高Gamma - 可能过度活跃
                mentalState = Barrage.MentalState.HYPERACTIVE;
                alertLevel = Barrage.AlertLevel.WARNING;
                recommendation = "高频活动过度，建议适当休息";
            } else {
                // 适度Gamma - 高度认知状态
                mentalState = Barrage.MentalState.ALERT;
                recommendation = "认知活动活跃，思维清晰";
            }

        } else {
            // 频率分布不均衡
            mentalState = Barrage.MentalState.UNBALANCED;
            alertLevel = Barrage.AlertLevel.ATTENTION;
            recommendation = "脑电活动模式不规律，建议关注精神状态";
        }

        result.setPrimaryState(mentalState);
        result.setAlertLevel(alertLevel);
        result.setRecommendation(recommendation);
    }

    /**
     * 计算分析置信度
     */
    private double calculateConfidenceScore(int sampleCount, Map<String, Double> normalized) {
        // 基于样本数量的基础置信度
        double baseConfidence = Math.min((double) sampleCount / 100.0, 1.0);

        // 基于频率分布均衡性的调整
        double maxValue = normalized.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double balanceScore = Math.max(0.2, 1.0 - (maxValue - 0.4)); // 防止过低的置信度

        return Math.max(0.3, Math.min(1.0, baseConfidence * balanceScore)); // 最低30%置信度
    }

    /**
     * 创建弹幕消息
     */
    private Barrage createBarrageMessage(Long userId, SpectralData data, AnalysisResult result) {
        Barrage barrage = new Barrage();
        barrage.setUserId(userId);
        barrage.setPrimaryState(result.getPrimaryState());
        barrage.setAlertLevel(result.getAlertLevel());
        barrage.setDataStartTime(data.getStartTime());
        barrage.setDataEndTime(data.getEndTime());
        barrage.setSampleCount(data.getSampleCount());
        barrage.setConfidenceScore(result.getConfidenceScore());
        barrage.setDominantFrequency(result.getDominantFrequency());
        barrage.setRecommendation(result.getRecommendation());

        // 设置频段值
        Map<String, Double> averages = result.getAverages();
        barrage.setAlphaValue(averages.getOrDefault("alpha", 0.0));
        barrage.setBetaValue(averages.getOrDefault("beta", 0.0));
        barrage.setThetaValue(averages.getOrDefault("theta", 0.0));
        barrage.setDeltaValue(averages.getOrDefault("delta", 0.0));
        barrage.setGammaValue(averages.getOrDefault("gamma", 0.0));

        // 【核心优化】尝试生成 AI 专家级分析内容
        String content = generateAIExpertAnalysis(userId, result, data);
        barrage.setContent(content);

        return barrage;
    }

    /**
     * 【新增】调用 AI 大模型生成医学专家水准的专业分析
     */
    private String generateAIExpertAnalysis(Long userId, AnalysisResult result, SpectralData data) {
        try {
            Map<String, Double> norm = result.getNormalized();
            
            // 构建专业化 Prompt
            StringBuilder prompt = new StringBuilder();
            prompt.append("你是一位资深的临床神经内科医生。请根据以下患者的【归一化脑电频段功率】数据进行诊断：\n");
            prompt.append(String.format("- Alpha (放松/静息): %.2f%%\n", norm.getOrDefault("alpha", 0.0) * 100));
            prompt.append(String.format("- Beta (逻辑/焦虑): %.2f%%\n", norm.getOrDefault("beta", 0.0) * 100));
            prompt.append(String.format("- Theta (创意/浅睡): %.2f%%\n", norm.getOrDefault("theta", 0.0) * 100));
            prompt.append(String.format("- Delta (深睡/疲劳): %.2f%%\n", norm.getOrDefault("delta", 0.0) * 100));
            prompt.append(String.format("- Gamma (认知/警觉): %.2f%%\n", norm.getOrDefault("gamma", 0.0) * 100));
            prompt.append("- 目前主导频率: ").append(result.getDominantFrequency().toUpperCase()).append("\n\n");
            
            prompt.append("任务：\n");
            prompt.append("1. 以专业医学口吻提供一段精炼的分析报告 (70字以内)。\n");
            prompt.append("2. 用带有【专家级医学】风格的一句话建议作为结尾。\n");
            prompt.append("3. 不要包含任何 HTML 标签，只需纯文本。不要使用废话开头（如‘根据数据...’）。\n");

            log.info("正在为用户 {} 请求 AI 专家分析报告...", userId);
            
            // 使用阻塞调用（因为在 Async 任务中跑，安全）
            AIModelService.AIResponse response = aiModelService.processUserQuery(userId, prompt.toString(), new HashMap<>()).block();
            
            if (response != null && response.success()) {
                String aiContent = response.content().trim();
                
                // 自动加上真正的时间范围
                aiContent += " [" + data.getStartTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + 
                             " ~ " + data.getEndTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "]";
                             
                log.info("AI 专家分析报告生成成功: {}", aiContent);
                return aiContent;
            } else {
                log.warn("AI 专家分析请求失败，使用基础逻辑回退: {}", (response != null ? response.content() : "Unknown Error"));
            }
        } catch (Exception e) {
            log.error("AI 专家分析生成过程中发生异常", e);
        }

        // --- 回退逻辑 (Fallback) ---
        return generateBarrageContent(result, data);
    }

    /**
     * 生成基础弹幕内容 (保底逻辑)
     */
    private String generateBarrageContent(AnalysisResult result, SpectralData data) {
        StringBuilder content = new StringBuilder();
        content.append("【").append(result.getPrimaryState().getDescription()).append("】");
        content.append(" 主导: ").append(result.getDominantFrequency().toUpperCase());
        content.append(String.format(" (置信度: %.1f%%)", result.getConfidenceScore() * 100));
        
        if (result.getRecommendation() != null && !result.getRecommendation().isEmpty()) {
            content.append(" - ").append(result.getRecommendation());
        }

        content.append(" [")
                .append(data.getStartTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                .append(" ~ ")
                .append(data.getEndTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                .append("]");

        return content.toString();
    }

    /**
     * 发送弹幕通知
     */
    private void sendBarrageNotification(Long userId, Barrage barrage) {
        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("type", "NEW_BARRAGE");
            notification.put("barrage", barrage);
            notification.put("timestamp", System.currentTimeMillis());

            webSocketService.notifyUser(userId, "REAL_TIME_BARRAGE", notification);
        } catch (Exception e) {
            log.error("发送弹幕通知失败 - 用户: {}", userId, e);
        }
    }

    /**
     * 获取用户历史弹幕
     */
    public List<Barrage> getUserRecentBarrages(Long userId, int limit) {
        try {
            return barrageRepository.findByUserIdOrderByCreatedAtDesc(userId,
                    org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
        } catch (Exception e) {
            log.error("获取用户 {} 历史弹幕失败", userId, e);
            return new ArrayList<>();
        }
    }

    // 数据传输对象
    public static class SpectralData {
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Map<String, List<Double>> bandValues = new HashMap<>();
        private int sampleCount = 0;

        public SpectralData() {
            this.bandValues.put("alpha", new ArrayList<>());
            this.bandValues.put("beta", new ArrayList<>());
            this.bandValues.put("theta", new ArrayList<>());
            this.bandValues.put("delta", new ArrayList<>());
            this.bandValues.put("gamma", new ArrayList<>());
        }

        // getters and setters
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
        public Map<String, List<Double>> getBandValues() { return bandValues; }
        public void setBandValues(Map<String, List<Double>> bandValues) { this.bandValues = bandValues; }
        public int getSampleCount() { return sampleCount; }
        public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
    }

    public static class AnalysisResult {
        private Map<String, Double> averages;
        private Map<String, Double> normalized;
        private Barrage.MentalState primaryState;
        private Barrage.AlertLevel alertLevel;
        private String dominantFrequency;
        private String recommendation;
        private double confidenceScore;

        // getters and setters
        public Map<String, Double> getAverages() { return averages; }
        public void setAverages(Map<String, Double> averages) { this.averages = averages; }
        public Map<String, Double> getNormalized() { return normalized; }
        public void setNormalized(Map<String, Double> normalized) { this.normalized = normalized; }
        public Barrage.MentalState getPrimaryState() { return primaryState; }
        public void setPrimaryState(Barrage.MentalState primaryState) { this.primaryState = primaryState; }
        public Barrage.AlertLevel getAlertLevel() { return alertLevel; }
        public void setAlertLevel(Barrage.AlertLevel alertLevel) { this.alertLevel = alertLevel; }
        public String getDominantFrequency() { return dominantFrequency; }
        public void setDominantFrequency(String dominantFrequency) { this.dominantFrequency = dominantFrequency; }
        public String getRecommendation() { return recommendation; }
        public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
        public double getConfidenceScore() { return confidenceScore; }
        public void setConfidenceScore(double confidenceScore) { this.confidenceScore = confidenceScore; }
    }
}