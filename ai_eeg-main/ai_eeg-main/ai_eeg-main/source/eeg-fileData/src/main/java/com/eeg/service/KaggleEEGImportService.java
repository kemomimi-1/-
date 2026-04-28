package com.eeg.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kaggle EEG 数据集导入服务
 * 将 "Confused Student EEG" 数据集 (CSV) 导入到 InfluxDB 中,
 * 供 AI 助手通过 MCP 工具链查询和分析。
 *
 * 数据集来源: https://www.kaggle.com/datasets/wanghaohan/confused-eeg/data
 * CSV 列: SubjectID, VideoID, Attention, Mediation, Raw, Delta, Theta,
 *         Alpha1, Alpha2, Beta1, Beta2, Gamma1, Gamma2,
 *         predefinedlabel, user-definedlabeln
 */
@Slf4j
@Service
public class KaggleEEGImportService {

    @Autowired
    private InfluxDBService influxDBService;

    // 导入状态跟踪
    private final AtomicBoolean importing = new AtomicBoolean(false);
    private final AtomicInteger totalRows = new AtomicInteger(0);
    private final AtomicInteger importedRows = new AtomicInteger(0);
    private final AtomicInteger failedRows = new AtomicInteger(0);
    private final AtomicLong startTime = new AtomicLong(0);
    private volatile String statusMessage = "空闲";
    private volatile String lastError = null;

    // 每批写入的行数（InfluxDB 推荐批量写入）
    private static final int BATCH_SIZE = 500;

    // 采样间隔: 数据集每 0.5 秒采样一次
    private static final long SAMPLE_INTERVAL_NS = 500_000_000L; // 0.5s in nanoseconds

    /**
     * 从指定路径导入 Kaggle EEG CSV 数据
     * @param csvFilePath CSV 文件绝对路径
     * @return 导入结果摘要
     */
    public Map<String, Object> importFromCSV(String csvFilePath) {
        if (importing.getAndSet(true)) {
            return Map.of("success", false, "error", "正在导入中，请等待当前导入完成");
        }

        // 重置状态
        totalRows.set(0);
        importedRows.set(0);
        failedRows.set(0);
        startTime.set(System.currentTimeMillis());
        lastError = null;
        statusMessage = "开始导入...";

        try {
            Path path = Paths.get(csvFilePath);
            if (!Files.exists(path)) {
                lastError = "文件不存在: " + csvFilePath;
                statusMessage = "导入失败";
                return Map.of("success", false, "error", lastError);
            }

            log.info("开始导入 Kaggle EEG 数据集: {}", csvFilePath);
            statusMessage = "正在读取 CSV 文件...";

            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.size() < 2) {
                lastError = "CSV 文件为空或只有表头";
                statusMessage = "导入失败";
                return Map.of("success", false, "error", lastError);
            }

            // 解析表头
            String headerLine = lines.get(0).trim();
            // 处理 BOM
            if (headerLine.startsWith("\uFEFF")) {
                headerLine = headerLine.substring(1);
            }
            String[] headers = headerLine.split(",");
            log.info("CSV 表头: {}", Arrays.toString(headers));

            // 验证必要的列
            Map<String, Integer> columnIndex = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                columnIndex.put(headers[i].trim().toLowerCase(), i);
            }

            if (!columnIndex.containsKey("subjectid") || !columnIndex.containsKey("delta")) {
                lastError = "CSV 格式不正确，缺少必要的列 (SubjectID, Delta 等)";
                statusMessage = "导入失败";
                return Map.of("success", false, "error", lastError);
            }

            totalRows.set(lines.size() - 1); // 减去表头
            statusMessage = String.format("正在导入 %d 行数据...", totalRows.get());
            log.info("准备导入 {} 行数据", totalRows.get());

            // 按 SubjectID+VideoID 分组，为每组分配不同的时间基线
            // 这样不同学生/视频的数据在时间轴上不重叠，便于查询
            Map<String, Long> sessionBaseTime = new HashMap<>();
            long baseEpochNs = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli() * 1_000_000L;
            long sessionGap = 120_000_000_000L; // 每个 session 间隔 2 分钟

            // 批量写入缓冲区
            StringBuilder batchBuffer = new StringBuilder();
            int batchCount = 0;

            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                try {
                    String[] values = line.split(",");

                    // 解析各字段 - 使用 parseDouble 应对科学计数法
                    int rawSubjectId = (int) parseDouble(values, columnIndex, "subjectid", 0);
                    int rawVideoId = (int) parseDouble(values, columnIndex, "videoid", 0);
                    String subjectId = String.valueOf(rawSubjectId);
                    String videoId = String.valueOf(rawVideoId);
                    
                    double attention = parseDouble(values, columnIndex, "attention", 0);
                    double mediation = parseDouble(values, columnIndex, "mediation", 0);
                    double raw = parseDouble(values, columnIndex, "raw", 0);
                    double delta = parseDouble(values, columnIndex, "delta", 0);
                    double theta = parseDouble(values, columnIndex, "theta", 0);
                    double alpha1 = parseDouble(values, columnIndex, "alpha1", 0);
                    double alpha2 = parseDouble(values, columnIndex, "alpha2", 0);
                    double beta1 = parseDouble(values, columnIndex, "beta1", 0);
                    double beta2 = parseDouble(values, columnIndex, "beta2", 0);
                    double gamma1 = parseDouble(values, columnIndex, "gamma1", 0);
                    double gamma2 = parseDouble(values, columnIndex, "gamma2", 0);
                    
                    double rawPredefined = parseDouble(values, columnIndex, "predefinedlabel", 0);
                    double rawUserDefined = parseDouble(values, columnIndex, "user-definedlabeln", 0);

                    // 计算时间戳
                    String sessionKey = subjectId + "_" + videoId;
                    if (!sessionBaseTime.containsKey(sessionKey)) {
                        long sessionOffset = sessionBaseTime.size() * sessionGap;
                        sessionBaseTime.put(sessionKey, baseEpochNs + sessionOffset);
                    }
                    long baseTs = sessionBaseTime.get(sessionKey);

                    // 统计该 session 内已有多少行，据此计算偏移
                    // 简化：直接用全局行号
                    long timestamp = baseTs + ((long)(i - 1) * SAMPLE_INTERVAL_NS);

                    // Alpha 和 Beta 合并（Alpha = Alpha1 + Alpha2, Beta = Beta1 + Beta2）
                    double alphaTotal = alpha1 + alpha2;
                    double betaTotal = beta1 + beta2;
                    double gammaTotal = gamma1 + gamma2;

                    // 构建 Line Protocol
                    // measurement: kaggle_eeg
                    // tags: subject_id, video_id, confused (predefined), self_confused (user-defined)
                    // fields: all numeric values
                    String lineProtocol = String.format(
                            "kaggle_eeg,subject_id=S%s,video_id=V%s,confused=%s,self_confused=%s " +
                                    "attention=%.1f,mediation=%.1f,raw=%.1f," +
                                    "delta=%.1f,theta=%.1f," +
                                    "alpha1=%.1f,alpha2=%.1f,alpha_total=%.1f," +
                                    "beta1=%.1f,beta2=%.1f,beta_total=%.1f," +
                                    "gamma1=%.1f,gamma2=%.1f,gamma_total=%.1f " +
                                    "%d",
                            subjectId, videoId,
                            rawPredefined > 0.5 ? "yes" : "no",
                            rawUserDefined > 0.5 ? "yes" : "no",
                            attention, mediation, raw,
                            delta, theta,
                            alpha1, alpha2, alphaTotal,
                            beta1, beta2, betaTotal,
                            gamma1, gamma2, gammaTotal,
                            timestamp
                    );

                    batchBuffer.append(lineProtocol).append("\n");
                    batchCount++;

                    // 达到批量大小时写入
                    if (batchCount >= BATCH_SIZE) {
                        influxDBService.writeLineProtocol(batchBuffer.toString());
                        importedRows.addAndGet(batchCount);
                        batchBuffer.setLength(0);
                        batchCount = 0;

                        // 更新状态
                        int progress = (int) ((importedRows.get() * 100.0) / totalRows.get());
                        statusMessage = String.format("导入中... %d/%d (%d%%)",
                                importedRows.get(), totalRows.get(), progress);

                        if (importedRows.get() % 2000 == 0) {
                            log.info("导入进度: {}/{}", importedRows.get(), totalRows.get());
                        }

                        // 每批之间稍微暂停，避免压垮 InfluxDB
                        Thread.sleep(50);
                    }

                } catch (Exception e) {
                    failedRows.incrementAndGet();
                    if (failedRows.get() <= 5) {
                        log.warn("第 {} 行解析失败: {}", i, e.getMessage());
                    }
                }
            }

            // 写入剩余数据
            if (batchCount > 0) {
                influxDBService.writeLineProtocol(batchBuffer.toString());
                importedRows.addAndGet(batchCount);
            }

            long duration = System.currentTimeMillis() - startTime.get();
            statusMessage = String.format("导入完成! 成功 %d 行, 失败 %d 行, 耗时 %.1f 秒",
                    importedRows.get(), failedRows.get(), duration / 1000.0);
            log.info(statusMessage);
            log.info("会话映射: {} 个不同的 Subject-Video 组合", sessionBaseTime.size());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", statusMessage);
            result.put("totalRows", totalRows.get());
            result.put("importedRows", importedRows.get());
            result.put("failedRows", failedRows.get());
            result.put("sessions", sessionBaseTime.size());
            result.put("durationMs", duration);
            return result;

        } catch (Exception e) {
            lastError = "导入异常: " + e.getMessage();
            statusMessage = "导入失败: " + e.getMessage();
            log.error("导入 Kaggle EEG 数据失败", e);
            return Map.of("success", false, "error", lastError);
        } finally {
            importing.set(false);
        }
    }

    /**
     * 获取当前导入状态
     */
    public Map<String, Object> getImportStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("importing", importing.get());
        status.put("status", statusMessage);
        status.put("totalRows", totalRows.get());
        status.put("importedRows", importedRows.get());
        status.put("failedRows", failedRows.get());
        if (importing.get() && totalRows.get() > 0) {
            status.put("progress", (int) ((importedRows.get() * 100.0) / totalRows.get()));
        }
        if (lastError != null) {
            status.put("lastError", lastError);
        }
        if (startTime.get() > 0) {
            status.put("elapsedMs", System.currentTimeMillis() - startTime.get());
        }
        return status;
    }

    // ========== 辅助方法 ==========

    private String parseField(String[] values, Map<String, Integer> columnIndex, String field, String defaultValue) {
        Integer idx = columnIndex.get(field);
        if (idx == null || idx >= values.length) return defaultValue;
        String val = values[idx].trim();
        return val.isEmpty() ? defaultValue : val;
    }

    private double parseDouble(String[] values, Map<String, Integer> columnIndex, String field, double defaultValue) {
        try {
            String val = parseField(values, columnIndex, field, null);
            if (val == null) return defaultValue;
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
