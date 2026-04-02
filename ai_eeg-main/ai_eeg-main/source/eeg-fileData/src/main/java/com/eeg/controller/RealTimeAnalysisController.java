// RealTimeAnalysisController.java - 简化版删除功能
package com.eeg.controller;

import com.eeg.entity.Barrage;
import com.eeg.service.RealTimeSpectrumAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/realtime-analysis")
@RequiredArgsConstructor
public class RealTimeAnalysisController {

    private final RealTimeSpectrumAnalysisService analysisService;
    
    /**
     * 获取当前用户的实时分析状态
     */
    @GetMapping("/status")
    public ResponseEntity<Object> getAnalysisStatus(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(createErrorResponse("用户未登录"));
        }
        
        boolean isActive = analysisService.isAnalysisActive(userId);
        Map<String, Object> response = createSuccessResponse("获取分析状态成功");
        response.put("analysisActive", isActive);
        response.put("userId", userId);
        
        return ResponseEntity.ok(response);
    }

    /**
     * 启动实时分析
     */
    @PostMapping("/start")
    public ResponseEntity<Object> startRealTimeAnalysis(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(createErrorResponse("用户未登录"));
        }

        try {
            analysisService.startRealTimeAnalysis(userId);

            Map<String, Object> response = createSuccessResponse("实时分析已启动");
            response.put("userId", userId);
            response.put("analysisActive", true);
            response.put("strategy", "智能数据获取：实时数据 -> 历史数据 -> 持续采样");

            log.info("用户 {} 启动实时频谱分析", userId);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("启动用户 {} 的实时分析失败", userId, e);
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("启动实时分析失败: " + e.getMessage()));
        }
    }

    /**
     * 停止实时分析
     */
    @PostMapping("/stop")
    public ResponseEntity<Object> stopRealTimeAnalysis(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(createErrorResponse("用户未登录"));
        }

        try {
            analysisService.stopRealTimeAnalysis(userId);

            Map<String, Object> response = createSuccessResponse("实时分析已停止");
            response.put("userId", userId);
            response.put("analysisActive", false);

            log.info("用户 {} 停止实时频谱分析", userId);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("停止用户 {} 的实时分析失败", userId, e);
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("停止实时分析失败: " + e.getMessage()));
        }
    }

    /**
     * 获取历史弹幕
     */
    @GetMapping("/barrages")
    public ResponseEntity<Object> getRecentBarrages(
            @RequestParam(defaultValue = "20") int limit,
            HttpSession session) {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(createErrorResponse("用户未登录"));
        }

        try {
            List<Barrage> barrages = analysisService.getUserRecentBarrages(userId, limit);

            Map<String, Object> response = createSuccessResponse("获取历史弹幕成功");
            response.put("barrages", barrages);
            response.put("total", barrages.size());
            response.put("userId", userId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("获取用户 {} 的历史弹幕失败", userId, e);
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("获取历史弹幕失败: " + e.getMessage()));
        }
    }

    /**
     * 立即执行一次分析（不会启动持续分析）
     */
    @PostMapping("/analyze-now")
    public ResponseEntity<Object> analyzeNow(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(createErrorResponse("用户未登录"));
        }

        try {
            // 【修复】只执行一次分析，不启动持续分析循环
            analysisService.performSingleAnalysis(userId);

            Map<String, Object> response = createSuccessResponse("立即分析已触发");
            response.put("userId", userId);
            response.put("note", "分析结果将通过WebSocket推送或查询历史弹幕获取");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("用户 {} 的立即分析失败", userId, e);
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("立即分析失败: " + e.getMessage()));
        }
    }

    // ========== 【新增】删除弹幕功能 ==========

    /**
     * 删除单条弹幕
     * DELETE /api/realtime-analysis/barrages/{barrageId}
     */
    @DeleteMapping("/barrages/{barrageId}")
    public ResponseEntity<Object> deleteBarrage(@PathVariable Long barrageId, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(createErrorResponse("用户未登录"));
        }

        try {
            boolean success = analysisService.deleteBarrage(userId, barrageId);

            if (success) {
                Map<String, Object> response = createSuccessResponse("弹幕删除成功");
                response.put("barrageId", barrageId);
                response.put("userId", userId);

                log.info("用户 {} 成功删除弹幕 {}", userId, barrageId);
                return ResponseEntity.ok(response);
            } else {
                Map<String, Object> response = createErrorResponse("弹幕不存在或无权限删除");
                response.put("barrageId", barrageId);

                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

        } catch (Exception e) {
            log.error("删除弹幕时发生未处理异常 - 用户: {}, 弹幕ID: {}", userId, barrageId, e);
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("删除弹幕时系统错误: " + e.getMessage()));
        }
    }

    /**
     * 创建成功响应
     */
    private Map<String, Object> createSuccessResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("timestamp", System.currentTimeMillis());
        response.put("service", "RealTimeSpectrumAnalysis");
        return response;
    }

    /**
     * 创建错误响应
     */
    private Map<String, Object> createErrorResponse(String error) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", error);
        response.put("timestamp", System.currentTimeMillis());
        response.put("service", "RealTimeSpectrumAnalysis");
        return response;
    }
}