// 强制设置UTC时区配置类
package com.eeg.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

@Slf4j
@Configuration
public class TimeZoneConfig {

    /**
     * 【修复】应用启动时强制设置JVM时区为北京时间
     * 统一所有时间为 Asia/Shanghai，避免 UTC 与北京时间混用导致数据混乱
     */
    @PostConstruct
    public void initTimeZone() {
        // 设置JVM默认时区为北京时间
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));

        // 验证设置是否生效
        TimeZone currentTimeZone = TimeZone.getDefault();
        log.info("应用启动时区配置:");
        log.info("JVM默认时区: {}", currentTimeZone.getID());
        log.info("UTC偏移量: +{} 小时", currentTimeZone.getRawOffset() / (1000 * 60 * 60));
        log.info("系统默认ZoneId: {}", java.time.ZoneId.systemDefault());
        log.info("当前北京时间: {}", java.time.LocalDateTime.now());

        if ("Asia/Shanghai".equals(currentTimeZone.getID())) {
            log.info("✓ JVM时区已成功设置为 Asia/Shanghai (北京时间)");
        } else {
            log.warn("警告: JVM时区设置可能失败，当前时区为: {}", currentTimeZone.getID());
        }
    }
}