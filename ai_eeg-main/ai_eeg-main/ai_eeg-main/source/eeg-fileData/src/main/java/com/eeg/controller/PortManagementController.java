//  端口池管理控制器（基础功能）
package com.eeg.controller;

import com.eeg.service.EnhancedPortAllocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/ports")
@RequiredArgsConstructor
public class PortManagementController {

    private final EnhancedPortAllocationService portService;

    /**
     * 获取端口池统计信息
     */
    @GetMapping("/statistics")
    public ResponseEntity<?> getPortPoolStatistics(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            EnhancedPortAllocationService.PortPoolStatistics stats = portService.getPortPoolStatistics();

            Map<String, Object> response = new HashMap<>();
            response.put("totalPorts", stats.getTotalPorts());
            response.put("usedPorts", stats.getUsedPorts());
            response.put("availablePorts", stats.getAvailablePorts());
            response.put("activeAllocations", stats.getActiveAllocations());
            response.put("utilizationRate", Math.round(stats.getUtilizationRate() * 100) / 100.0);
            response.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            return ResponseEntity.ok(createSuccessResponse(response));

        } catch (Exception e) {
            log.error("获取端口池统计信息失败", e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取统计信息失败: " + e.getMessage()));
        }
    }

    /**
     * 获取所有活跃的端口分配
     */
    @GetMapping("/allocations/active")
    public ResponseEntity<?> getActiveAllocations(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            Map<Long, EnhancedPortAllocationService.PortAllocation> activeAllocations =
                    portService.getAllActiveAllocations();

            Map<String, Object> response = new HashMap<>();
            response.put("activeAllocations", activeAllocations);
            response.put("count", activeAllocations.size());

            return ResponseEntity.ok(createSuccessResponse(response));

        } catch (Exception e) {
            log.error("获取活跃端口分配失败", e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取活跃分配失败: " + e.getMessage()));
        }
    }

    /**
     * 强制释放用户的端口分配（紧急情况使用）
     */
    @PostMapping("/allocations/force-release")
    public ResponseEntity<?> forceReleaseUserPorts(@RequestBody ForceReleaseRequest request,
                                                   HttpSession session) {
        Long adminUserId = (Long) session.getAttribute("userId");
        if (adminUserId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            Long targetUserId = request.getUserId();
            String reason = request.getReason() != null ? request.getReason() : "管理员强制释放";

            if (!portService.hasActivePortAllocation(targetUserId)) {
                return ResponseEntity.badRequest().body(createErrorResponse("目标用户没有活跃的端口分配"));
            }

            portService.forceReleaseUserPorts(targetUserId, reason);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "成功强制释放用户端口分配");
            response.put("targetUserId", targetUserId);
            response.put("reason", reason);

            log.warn("管理员 {} 强制释放用户 {} 的端口分配，原因: {}", adminUserId, targetUserId, reason);

            return ResponseEntity.ok(createSuccessResponse(response));

        } catch (Exception e) {
            log.error("强制释放端口分配失败", e);
            return ResponseEntity.badRequest().body(createErrorResponse("强制释放失败: " + e.getMessage()));
        }
    }

    /**
     * 系统健康检查 - 端口池状态
     */
    @GetMapping("/health")
    public ResponseEntity<?> getPortPoolHealth(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("未登录"));
        }

        try {
            EnhancedPortAllocationService.PortPoolStatistics stats = portService.getPortPoolStatistics();

            // 健康状态评估
            String healthStatus = "HEALTHY";
            String healthMessage = "端口池运行正常";

            //端口池的使用率超过90%时发出WARNING警告
            if (stats.getUtilizationRate() > 90) { // 90%
                healthStatus = "WARNING";
                healthMessage = "端口使用率过高";
            }
            //当可用端口数少于30个时设为CRITICAL状态
            if (stats.getAvailablePorts() < 300) { // 至少能支持10个用户
                healthStatus = "CRITICAL";
                healthMessage = "可用端口数量过低";
            }

            Map<String, Object> health = new HashMap<>();
            health.put("status", healthStatus);
            health.put("message", healthMessage);
            health.put("utilizationRate", stats.getUtilizationRate());
            health.put("availablePorts", stats.getAvailablePorts());
            health.put("activeAllocations", stats.getActiveAllocations());

            return ResponseEntity.ok(createSuccessResponse(Map.of("health", health)));

        } catch (Exception e) {
            log.error("获取端口池健康状态失败", e);
            return ResponseEntity.badRequest().body(createErrorResponse("健康检查失败: " + e.getMessage()));
        }
    }

    // 请求数据类
    @Data
    public static class ForceReleaseRequest {
        private Long userId;
        private String reason;
    }

    // 辅助方法
    private Map<String, Object> createSuccessResponse(Map<String, Object> data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("timestamp", System.currentTimeMillis());
        response.putAll(data);
        return response;
    }

    private Map<String, Object> createErrorResponse(String errorMessage) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", errorMessage);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
}