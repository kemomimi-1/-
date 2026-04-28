// 修复数据缓冲区限制版本
package com.eeg.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
public class InfluxDBService {

    @Value("${influxdb.url:http://localhost:8181}")
    private String influxdbUrl;

    @Value("${influxdb.token}")
    private String influxdbToken;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public InfluxDBService() {
        // 【关键修复】增加缓冲区大小到 16MB，解决大数据量查询问题
        final int maxBufferSize = 16 * 1024 * 1024; // 16MB

        final ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs((ClientCodecConfigurer configurer) -> {
                    configurer.defaultCodecs().maxInMemorySize(maxBufferSize);
                })
                .build();

        this.webClient = WebClient.builder()
                .exchangeStrategies(strategies)
                .build();

        this.objectMapper = new ObjectMapper();

        log.info("InfluxDBService 初始化完成 - 最大缓冲区大小: {}MB", maxBufferSize / 1024 / 1024);
    }

    public void writeLineProtocol(String lineProtocol) {
        try {
            // 使用InfluxDB 3 Core的v2兼容API，统一写入eeg_db数据库
            String writeUrl = influxdbUrl + "/api/v2/write?precision=ns&bucket=eeg_db";

            webClient.post()
                    .uri(writeUrl)
                    .header("Authorization", "Bearer " + influxdbToken)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .bodyValue(lineProtocol)
                    .retrieve()
                    .toBodilessEntity()
                    .doOnSuccess(response -> {
                        log.debug("成功写入InfluxDB - 数据库: eeg_db");
                    })
                    .doOnError(error -> {
                        log.error("写入InfluxDB失败 - 数据库: eeg_db", error);
                    })
                    .subscribe();

        } catch (Exception e) {
            log.error("InfluxDB写入异常 - 数据库: eeg_db", e);
        }
    }

    public Mono<String> queryData(String query) {
        String queryUrl = influxdbUrl + "/api/v3/query_sql";

        // 构建JSON请求体
        Map<String, Object> requestBody = Map.of(
                "db", "eeg_db",
                "q", query,
                "format", "json"
        );

        return webClient.post()
                .uri(queryUrl)
                .header("Authorization", "Bearer " + influxdbToken)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(response -> {
                    // 记录响应大小用于监控
                    int responseSize = response != null ? response.length() : 0;
                    if (responseSize > 1024 * 1024) { // 大于1MB时记录警告
                        log.warn("InfluxDB查询返回大量数据 - 大小: {}MB, 查询: {}",
                                responseSize / 1024 / 1024,
                                query.length() > 100 ? query.substring(0, 100) + "..." : query);
                    } else {
                        log.debug("InfluxDB查询成功 - 数据库: eeg_db, 响应大小: {}KB", responseSize / 1024);
                    }
                })
                .doOnError(error -> {
                    log.error("InfluxDB查询失败 - 数据库: eeg_db, 查询: {}",
                            query.length() > 200 ? query.substring(0, 200) + "..." : query, error);
                });
    }

    /**
     * 执行查询并返回结果，支持指定返回格式
     * @param query SQL查询语句
     * @param format 返回格式：json, jsonl, csv, pretty, parquet
     * @return 查询结果
     */
    public Mono<String> queryData(String query, String format) {
        String queryUrl = influxdbUrl + "/api/v3/query_sql";

        // 构建JSON请求体
        Map<String, Object> requestBody = Map.of(
                "db", "eeg_db",
                "q", query,
                "format", format != null ? format : "json"
        );

        return webClient.post()
                .uri(queryUrl)
                .header("Authorization", "Bearer " + influxdbToken)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(response -> {
                    // 监控响应大小
                    int responseSize = response != null ? response.length() : 0;
                    if (responseSize > 1024 * 1024) { // 大于1MB
                        log.warn("大数据量查询 - 响应大小: {}MB, 格式: {}", responseSize / 1024 / 1024, format);
                        log.info("查询语句: {}", query.length() > 150 ? query.substring(0, 150) + "..." : query);
                    } else {
                        log.debug("查询成功 - 响应大小: {}KB, 格式: {}", responseSize / 1024, format);
                    }
                })
                .doOnError(error -> {
                    log.error("查询失败 - 格式: {}, 查询: {}", format,
                            query.length() > 200 ? query.substring(0, 200) + "..." : query, error);
                });
    }

    /**
     * 分页查询数据，用于处理大数据集
     * @param query 基础查询语句（不包含LIMIT和OFFSET）
     * @param pageSize 每页大小
     * @param pageNumber 页码（从0开始）
     * @param format 返回格式
     * @return 查询结果
     */
    public Mono<String> queryDataPaged(String query, int pageSize, int pageNumber, String format) {
        // 添加分页参数
        String pagedQuery = query + String.format(" LIMIT %d OFFSET %d", pageSize, pageNumber * pageSize);

        log.debug("执行分页查询 - 页大小: {}, 页码: {}, 查询: {}",
                pageSize, pageNumber,
                pagedQuery.length() > 100 ? pagedQuery.substring(0, 100) + "..." : pagedQuery);

        return queryData(pagedQuery, format);
    }

    /**
     * 执行聚合查询，减少数据传输量
     * @param baseQuery 基础查询
     * @param aggregationLevel 聚合级别：minute, hour, day
     * @return 聚合后的查询结果
     */
    public Mono<String> queryDataAggregated(String baseQuery, String aggregationLevel) {
        // 根据聚合级别修改查询
        String timeFunction;
        switch (aggregationLevel.toLowerCase()) {
            case "minute":
                timeFunction = "DATE_TRUNC('minute', time)";
                break;
            case "hour":
                timeFunction = "DATE_TRUNC('hour', time)";
                break;
            case "day":
                timeFunction = "DATE_TRUNC('day', time)";
                break;
            default:
                timeFunction = "DATE_TRUNC('minute', time)";
        }

        // 自动将查询转换为聚合形式
        String aggregatedQuery = baseQuery.replaceAll(
                "SELECT\\s+([^F]+)FROM",
                "SELECT " + timeFunction + " as time_bucket, $1 FROM"
        ).replaceAll(
                "ORDER BY[^L]+",
                "GROUP BY time_bucket ORDER BY time_bucket"
        );

        log.debug("执行聚合查询 - 级别: {}, 查询: {}",
                aggregationLevel,
                aggregatedQuery.length() > 100 ? aggregatedQuery.substring(0, 100) + "..." : aggregatedQuery);

        return queryData(aggregatedQuery, "json");
    }

    /**
     * 执行InfluxQL查询（可选）
     * @param query InfluxQL查询语句
     * @return 查询结果
     */
    public Mono<String> queryInfluxQL(String query) {
        String queryUrl = influxdbUrl + "/api/v3/query_influxql";

        // 构建JSON请求体
        Map<String, Object> requestBody = Map.of(
                "db", "eeg_db",
                "q", query,
                "format", "json"
        );

        return webClient.post()
                .uri(queryUrl)
                .header("Authorization", "Bearer " + influxdbToken)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(error -> {
                    log.error("InfluxQL查询失败 - 数据库: eeg_db, 查询: {}", query, error);
                });
    }

    /**
     * 检查数据库连接
     * @return 连接状态
     */
    public Mono<Boolean> checkConnection() {
        return queryData("SHOW TABLES", "json")
                .map(result -> {
                    log.debug("连接检查成功 - 响应: {}",
                            result.length() > 50 ? result.substring(0, 50) + "..." : result);
                    return true;
                })
                .onErrorReturn(false);
    }

    /**
     * 获取数据库信息
     * @return 数据库信息
     */
    public Mono<String> getDatabaseInfo() {
        return queryData("SELECT COUNT(*) as total_tables FROM information_schema.tables WHERE table_schema = 'iox'", "json")
                .doOnSuccess(result -> {
                    log.debug("数据库信息查询成功");
                })
                .onErrorReturn("{\"error\": \"Failed to get database info\"}");
    }

    /**
     * 获取表的数据量信息，用于优化查询策略
     * @param tableName 表名
     * @return 数据量信息
     */
    public Mono<Long> getTableRowCount(String tableName) {
        String query = String.format("SELECT COUNT(*) as row_count FROM %s", tableName);

        return queryData(query, "json")
                .map(result -> {
                    try {
                        if (result == null || result.trim().isEmpty() || "[]".equals(result.trim())) {
                            return 0L;
                        }
                        // 【修复】正确解析 JSON 而不是返回硬编码值
                        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(result);
                        if (node.isArray() && node.size() > 0) {
                            com.fasterxml.jackson.databind.JsonNode firstRow = node.get(0);
                            if (firstRow.has("row_count")) {
                                return firstRow.get("row_count").asLong();
                            }
                        }
                        return 0L;
                    } catch (Exception e) {
                        log.warn("解析行数失败: {}", e.getMessage());
                        return 0L;
                    }
                })
                .onErrorReturn(0L);
    }
}