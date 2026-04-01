// 强制设置UTC时区配置类
package com.eeg.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneOffset;
import java.util.TimeZone;

@Slf4j
@Configuration
public class TimeZoneConfig {

    /**
     * 【关键修复】应用启动时强制设置JVM时区为UTC
     * 这确保了整个应用程序都使用UTC时区
     */
    @PostConstruct
    public void initTimeZone() {
        // 设置JVM默认时区为UTC
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 验证设置是否生效
        TimeZone currentTimeZone = TimeZone.getDefault();
        log.info("应用启动时区配置:");
        log.info("JVM默认时区: {}", currentTimeZone.getID());
        log.info("时区显示名称: {}", currentTimeZone.getDisplayName());
        log.info("UTC偏移量: {} 小时", currentTimeZone.getRawOffset() / (1000 * 60 * 60));

        // 验证Java 8时间API的默认时区
        log.info("系统默认ZoneId: {}", java.time.ZoneId.systemDefault());

        // 验证当前时间
        log.info("当前UTC时间: {}", java.time.LocalDateTime.now(ZoneOffset.UTC));
        log.info("系统时间: {}", java.time.LocalDateTime.now());

        if (!"UTC".equals(currentTimeZone.getID())) {
            log.warn("警告: JVM时区设置可能失败，当前时区为: {}", currentTimeZone.getID());
        } else {
            log.info("✓ JVM时区已成功设置为UTC");
        }
    }
}