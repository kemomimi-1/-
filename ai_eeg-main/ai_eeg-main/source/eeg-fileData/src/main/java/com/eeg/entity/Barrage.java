// Barrage.java - 修复版本
package com.eeg.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "barrage_messages")
@Data
public class Barrage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private MentalState primaryState;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AlertLevel alertLevel;

    // 【修复】频谱数据快照 - 移除scale参数，只使用precision
    @Column(nullable = false)
    private Double alphaValue;

    @Column(nullable = false)
    private Double betaValue;

    @Column(nullable = false)
    private Double thetaValue;

    @Column(nullable = false)
    private Double deltaValue;

    @Column(nullable = false)
    private Double gammaValue;

    // 分析时间范围
    @Column(nullable = false)
    private LocalDateTime dataStartTime;

    @Column(nullable = false)
    private LocalDateTime dataEndTime;

    // 弹幕创建时间
    @Column(nullable = false)
    private LocalDateTime createdAt;

    // 分析指标
    @Column(nullable = false)
    private Integer sampleCount;

    // 【修复】置信度分数 - 移除scale参数
    @Column
    private Double confidenceScore;

    @Column(length = 100)
    private String dominantFrequency;

    @Column(length = 200)
    private String recommendation;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // 精神状态枚举
    public enum MentalState {
        DEEP_RELAXATION("深度放松"),
        RELAXED("放松状态"),
        FOCUSED("专注状态"),
        ALERT("警觉状态"),
        STRESSED("紧张状态"),
        DROWSY("困倦状态"),
        MEDITATIVE("冥想状态"),
        CREATIVE("创造性状态"),
        HYPERACTIVE("过度活跃"),
        UNBALANCED("状态失衡");

        private final String description;

        MentalState(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    // 警告级别枚举
    public enum AlertLevel {
        NORMAL("正常"),
        ATTENTION("注意"),
        WARNING("警告"),
        CRITICAL("严重");

        private final String description;

        AlertLevel(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}