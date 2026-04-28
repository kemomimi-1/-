package com.eeg.controller;

import com.eeg.service.KaggleEEGImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * Kaggle EEG 数据集导入控制器
 * 提供 REST API 以触发数据导入和查询导入状态
 */
@Slf4j
@RestController
@RequestMapping("/api/kaggle")
public class KaggleDataImportController {

    @Autowired
    private KaggleEEGImportService importService;

    /**
     * 触发 Kaggle EEG CSV 导入
     * POST /api/kaggle/import
     * Body: { "filePath": "C:/path/to/EEG_data.csv" }
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importData(
            @RequestBody Map<String, String> request,
            HttpSession session) {

        // 检查登录
        if (session.getAttribute("userId") == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "error", "未登录"));
        }

        String filePath = request.get("filePath");
        if (filePath == null || filePath.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "error", "请提供 CSV 文件路径 (filePath)")
            );
        }

        log.info("收到 Kaggle EEG 数据导入请求 - 文件: {}", filePath);

        // 异步导入（在新线程中执行，立即返回）
        new Thread(() -> {
            try {
                importService.importFromCSV(filePath.trim());
            } catch (Exception e) {
                log.error("后台导入线程异常", e);
            }
        }, "kaggle-import-thread").start();

        // 立即返回
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "导入任务已启动，请通过 /api/kaggle/status 查询进度"
        ));
    }

    /**
     * 查询导入状态
     * GET /api/kaggle/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(HttpSession session) {
        if (session.getAttribute("userId") == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "error", "未登录"));
        }
        return ResponseEntity.ok(importService.getImportStatus());
    }
}
