package com.eeg;

import com.eeg.repository.EEGSessionRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * EEG AI平台主启动类
 * 集成了AI大模型和MCP服务的脑电数据分析平台
 */
@Slf4j
@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.eeg.config")
@EnableScheduling  // 启用定时任务
@EnableAsync      // 启用异步处理
public class EegFileDataApplication {

    @Autowired
    private EEGSessionRepository sessionRepository;

    public static void main(String[] args) {
        // 启动前设置系统属性
        System.setProperty("java.awt.headless", "true");
        System.setProperty("file.encoding", "UTF-8");

        try {
            SpringApplication app = new SpringApplication(EegFileDataApplication.class);
            app.run(args);
        } catch (Exception e) {
            log.error("应用启动失败", e);
            System.exit(1);
        }
    }

    @PostConstruct
    public void startupCleanup() {
        // 【启动清理】将上次未正常关闭的遗留 ACTIVE 会话标记为 INTERRUPTED
        // 防止重启后出现"端口占用"和"断开连接按钮无法激活"等问题
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            int cleaned = sessionRepository.markAllActiveSessionsAsInterrupted(now);
            if (cleaned > 0) {
                log.warn("=== 启动清理：发现并关闭了 {} 个遗留的 ACTIVE 会话（上次未正常退出）===", cleaned);
            } else {
                log.info("=== 启动清理：无遗留 ACTIVE 会话，状态干净 ===");
            }
        } catch (Exception e) {
            log.error("启动清理遗留会话失败，将继续正常启动", e);
        }

        log.info("========================================");
        log.info("🧠 EEG AI Platform 启动成功！");
        log.info("🤖 集成通义千问-Max AI大模型");
        log.info("🔧 支持MCP (Model Context Protocol)");
        log.info("⚡ 智能脑电数据分析服务已就绪");
        log.info("========================================");
        log.info("启动时间: {}", LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        log.info("系统时区: {}", java.time.ZoneId.systemDefault());
        log.info("Java版本: {}", System.getProperty("java.version"));
        log.info("========================================");
        log.info("API测试地址:");
        log.info("🔍 健康检查: GET  /api/ai/test/health");
        log.info("🔧 工具测试: GET  /api/ai/test/tools");
        log.info("💬 AI查询:   POST /api/ai/query");
        log.info("📊 AI能力:   GET  /api/ai/capabilities");
        log.info("========================================");
    }
}
