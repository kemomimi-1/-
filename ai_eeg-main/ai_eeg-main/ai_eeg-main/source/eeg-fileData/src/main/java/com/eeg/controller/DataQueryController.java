// 数据查询控制器
package com.eeg.controller;

import com.eeg.service.InfluxDBService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataQueryController {

    private final InfluxDBService influxDBService;

    @GetMapping("/summary")
    public Mono<ResponseEntity<Object>> getDataSummary(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Mono.just(ResponseEntity.status(401).body(createErrorResponse("未登录")));
        }

        // 查询用户数据概要
        String query = String.format("""
            SELECT 
                COUNT(*) as total_records,
                MIN(time) as first_record,
                MAX(time) as last_record
            FROM timeseriesraw 
            WHERE user_id = '%s'
            """, userId);

        return influxDBService.queryData(query, "json")
                .map(result -> ResponseEntity.ok(createSuccessResponse(Map.of(
                        "userId", userId,
                        "query", "summary",
                        "result", result
                ))))
                .onErrorResume(error -> {
                    log.error("查询数据概要失败", error);
                    return Mono.just(ResponseEntity.badRequest().body(createErrorResponse("查询失败: " + error.getMessage())));
                });
    }

    @GetMapping("/recent")
    public Mono<ResponseEntity<Object>> getRecentData(
            @RequestParam(defaultValue = "timeseriesraw") String measurement,
            @RequestParam(defaultValue = "100") int limit,
            HttpSession session) {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Mono.just(ResponseEntity.status(401).body(createErrorResponse("未登录")));
        }

        // 查询用户最近的数据
        String query = String.format("""
            SELECT time, channel, sample, value 
            FROM %s 
            WHERE user_id = '%s' 
            ORDER BY time DESC 
            LIMIT %d
            """, measurement, userId, limit);

        return influxDBService.queryData(query, "json")
                .map(result -> ResponseEntity.ok(createSuccessResponse(Map.of(
                        "userId", userId,
                        "measurement", measurement,
                        "limit", limit,
                        "result", result
                ))))
                .onErrorResume(error -> {
                    log.error("查询最近数据失败", error);
                    return Mono.just(ResponseEntity.badRequest().body(createErrorResponse("查询失败: " + error.getMessage())));
                });
    }

    @GetMapping("/channels")
    public Mono<ResponseEntity<Object>> getChannelData(
            @RequestParam int channel,
            @RequestParam(defaultValue = "timeseriesraw") String measurement,
            @RequestParam(defaultValue = "1") int hours,
            HttpSession session) {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Mono.just(ResponseEntity.status(401).body(createErrorResponse("未登录")));
        }

        // 查询指定通道的数据 - 注意InfluxDB 3 Core的时间函数语法
        String query = String.format("""
            SELECT time, sample, value 
            FROM %s 
            WHERE user_id = '%s' AND channel = %d 
            AND time >= now() - INTERVAL %d HOUR
            ORDER BY time DESC
            """, measurement, userId, channel, hours);

        return influxDBService.queryData(query, "json")
                .map(result -> ResponseEntity.ok(createSuccessResponse(Map.of(
                        "userId", userId,
                        "channel", channel,
                        "measurement", measurement,
                        "hours", hours,
                        "result", result
                ))))
                .onErrorResume(error -> {
                    log.error("查询通道数据失败", error);
                    return Mono.just(ResponseEntity.badRequest().body(createErrorResponse("查询失败: " + error.getMessage())));
                });
    }

    @GetMapping("/bandpower")
    public Mono<ResponseEntity<Object>> getBandPowerData(
            @RequestParam(defaultValue = "alpha") String band,
            @RequestParam(defaultValue = "1") int hours,
            HttpSession session) {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Mono.just(ResponseEntity.status(401).body(createErrorResponse("未登录")));
        }

        // 查询频段功率数据
        String query = String.format("""
            SELECT time, value 
            FROM avg_band_power 
            WHERE user_id = '%s' AND band = '%s'
            AND time >= now() - INTERVAL %d HOUR
            ORDER BY time DESC
            """, userId, band, hours);

        return influxDBService.queryData(query, "json")
                .map(result -> ResponseEntity.ok(createSuccessResponse(Map.of(
                        "userId", userId,
                        "band", band,
                        "hours", hours,
                        "result", result
                ))))
                .onErrorResume(error -> {
                    log.error("查询频段功率数据失败", error);
                    return Mono.just(ResponseEntity.badRequest().body(createErrorResponse("查询失败: " + error.getMessage())));
                });
    }

    @PostMapping("/query")
    public Mono<ResponseEntity<Object>> customQuery(
            @RequestBody Map<String, String> request,
            HttpSession session) {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Mono.just(ResponseEntity.status(401).body(createErrorResponse("未登录")));
        }

        String customQuery = request.get("query");
        if (customQuery == null || customQuery.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(createErrorResponse("查询语句不能为空")));
        }

        // 为安全起见，确保查询只能访问当前用户的数据
        if (!customQuery.toLowerCase().contains("user_id = '" + userId + "'")) {
            if (customQuery.toLowerCase().contains("where")) {
                customQuery = customQuery.replaceFirst("(?i)where", "WHERE user_id = '" + userId + "' AND");
            } else {
                customQuery += " WHERE user_id = '" + userId + "'";
            }
        }

        // 创建final变量用于lambda表达式
        final String finalQuery = customQuery;
        String format = request.getOrDefault("format", "json");

        log.info("用户 {} 执行自定义查询: {}", userId, finalQuery);

        return influxDBService.queryData(finalQuery, format)
                .map(result -> ResponseEntity.ok(createSuccessResponse(Map.of(
                        "userId", userId,
                        "query", finalQuery,
                        "format", format,
                        "result", result
                ))))
                .onErrorResume(error -> {
                    log.error("自定义查询执行失败", error);
                    return Mono.just(ResponseEntity.badRequest().body(createErrorResponse("查询执行失败: " + error.getMessage())));
                });
    }

    @GetMapping("/stats")
    public Mono<ResponseEntity<Object>> getDataStats(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Mono.just(ResponseEntity.status(401).body(createErrorResponse("未登录")));
        }

        // 使用更简单的查询来获取基本统计信息
        String query = String.format("""
            SELECT 
                'timeseriesraw' as measurement,
                COUNT(*) as count,
                COUNT(DISTINCT channel) as channels,
                MIN(time) as first_time,
                MAX(time) as last_time
            FROM timeseriesraw 
            WHERE user_id = '%s'
            UNION ALL
            SELECT 
                'timeseriesfilt' as measurement,
                COUNT(*) as count,
                COUNT(DISTINCT channel) as channels,
                MIN(time) as first_time,
                MAX(time) as last_time
            FROM timeseriesfilt 
            WHERE user_id = '%s'
            UNION ALL
            SELECT 
                'avg_band_power' as measurement,
                COUNT(*) as count,
                COUNT(DISTINCT band) as channels,
                MIN(time) as first_time,
                MAX(time) as last_time
            FROM avg_band_power 
            WHERE user_id = '%s'
            """, userId, userId, userId);

        return influxDBService.queryData(query, "json")
                .map(result -> ResponseEntity.ok(createSuccessResponse(Map.of(
                        "userId", userId,
                        "stats", result,
                        "generated_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                ))))
                .onErrorResume(error -> {
                    log.error("统计查询失败 - 用户: {}", userId, error);
                    // 如果统计查询失败，返回简化的错误信息
                    return Mono.just(ResponseEntity.ok(createSuccessResponse(Map.of(
                            "userId", userId,
                            "stats", "[]",
                            "error", "暂时无法获取统计信息",
                            "generated_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    ))));
                });
    }

    @GetMapping("/connection-test")
    public Mono<ResponseEntity<Object>> testConnection(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Mono.just(ResponseEntity.status(401).body(createErrorResponse("未登录")));
        }

        return influxDBService.checkConnection()
                .map(connected -> {
                    if (connected) {
                        return ResponseEntity.ok(createSuccessResponse(Map.of(
                                "connected", true,
                                "message", "数据库连接正常"
                        )));
                    } else {
                        return ResponseEntity.ok(createSuccessResponse(Map.of(
                                "connected", false,
                                "message", "数据库连接失败"
                        )));
                    }
                })
                .onErrorReturn(ResponseEntity.badRequest().body(createErrorResponse("连接测试失败")));
    }

    @GetMapping("/tables")
    public Mono<ResponseEntity<Object>> showTables(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Mono.just(ResponseEntity.status(401).body(createErrorResponse("未登录")));
        }

        String query = "SHOW TABLES";

        return influxDBService.queryData(query, "json")
                .map(result -> ResponseEntity.ok(createSuccessResponse(Map.of(
                        "userId", userId,
                        "tables", result
                ))))
                .onErrorResume(error -> {
                    log.error("查询表列表失败", error);
                    return Mono.just(ResponseEntity.badRequest().body(createErrorResponse("查询失败: " + error.getMessage())));
                });
    }

    // 辅助方法：创建成功响应
    private Object createSuccessResponse(Map<String, Object> data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("timestamp", System.currentTimeMillis());
        response.putAll(data);
        return response;
    }

    // 辅助方法：创建错误响应
    private Object createErrorResponse(String errorMessage) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", errorMessage);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
}