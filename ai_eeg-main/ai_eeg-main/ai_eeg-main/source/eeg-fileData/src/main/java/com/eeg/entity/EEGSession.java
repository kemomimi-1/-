//  脑电数据传输会话实体类
package com.eeg.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "eeg_sessions")
@NoArgsConstructor
@AllArgsConstructor
public class EEGSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_start_time", nullable = false)
    private LocalDateTime sessionStartTime;

    @Column(name = "session_end_time")
    private LocalDateTime sessionEndTime;

    @Column(name = "user_timezone", length = 50)
    private String userTimezone;

    @Column(name = "session_start_time_utc", nullable = false)
    private LocalDateTime sessionStartTimeUtc;

    @Column(name = "session_end_time_utc")
    private LocalDateTime sessionEndTimeUtc;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_status", nullable = false)
    private SessionStatus sessionStatus = SessionStatus.ACTIVE;

    // 原始数据流状态
    @Column(name = "raw_stream_start_time_utc")
    private LocalDateTime rawStreamStartTimeUtc;

    @Column(name = "raw_stream_end_time_utc")
    private LocalDateTime rawStreamEndTimeUtc;

    @Column(name = "raw_stream_last_packet_time_utc")
    private LocalDateTime rawStreamLastPacketTimeUtc;

    @Column(name = "raw_stream_total_packets", columnDefinition = "BIGINT DEFAULT 0")
    private Long rawStreamTotalPackets = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "raw_stream_status")
    private StreamStatus rawStreamStatus = StreamStatus.WAITING;

    // 滤波数据流状态
    @Column(name = "filt_stream_start_time_utc")
    private LocalDateTime filtStreamStartTimeUtc;

    @Column(name = "filt_stream_end_time_utc")
    private LocalDateTime filtStreamEndTimeUtc;

    @Column(name = "filt_stream_last_packet_time_utc")
    private LocalDateTime filtStreamLastPacketTimeUtc;

    @Column(name = "filt_stream_total_packets", columnDefinition = "BIGINT DEFAULT 0")
    private Long filtStreamTotalPackets = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "filt_stream_status")
    private StreamStatus filtStreamStatus = StreamStatus.WAITING;

    // 频谱数据流状态
    @Column(name = "band_stream_start_time_utc")
    private LocalDateTime bandStreamStartTimeUtc;

    @Column(name = "band_stream_end_time_utc")
    private LocalDateTime bandStreamEndTimeUtc;

    @Column(name = "band_stream_last_packet_time_utc")
    private LocalDateTime bandStreamLastPacketTimeUtc;

    @Column(name = "band_stream_total_packets", columnDefinition = "BIGINT DEFAULT 0")
    private Long bandStreamTotalPackets = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "band_stream_status")
    private StreamStatus bandStreamStatus = StreamStatus.WAITING;

    // 会话元数据
    @Column(name = "total_duration_seconds")
    private Long totalDurationSeconds;

    @Column(name = "raw_port")
    private Integer rawPort;

    @Column(name = "filt_port")
    private Integer filtPort;

    @Column(name = "band_port")
    private Integer bandPort;

    @Column(name = "notes", length = 1000)
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum SessionStatus {
        ACTIVE,      // 会话活跃中
        COMPLETED,   // 会话正常结束
        INTERRUPTED, // 会话被中断
        ERROR        // 会话异常
    }

    public enum StreamStatus {
        WAITING,     // 等待数据
        ACTIVE,      // 正在传输
        PAUSED,      // 暂停状态
        COMPLETED,   // 传输完成
        ERROR        // 传输错误
    }

    // 便利方法：检查会话是否活跃
    public boolean isActive() {
        return sessionStatus == SessionStatus.ACTIVE;
    }

    // 便利方法：检查是否有任何数据流在传输
    public boolean hasActiveStreams() {
        return rawStreamStatus == StreamStatus.ACTIVE ||
                filtStreamStatus == StreamStatus.ACTIVE ||
                bandStreamStatus == StreamStatus.ACTIVE;
    }

    // 便利方法：获取活跃的数据流数量
    public int getActiveStreamCount() {
        int count = 0;
        if (rawStreamStatus == StreamStatus.ACTIVE) count++;
        if (filtStreamStatus == StreamStatus.ACTIVE) count++;
        if (bandStreamStatus == StreamStatus.ACTIVE) count++;
        return count;
    }

    // 便利方法：计算会话持续时间
    public Long calculateDurationSeconds() {
        if (sessionStartTimeUtc == null) return 0L;
        LocalDateTime endTime = sessionEndTimeUtc != null ? sessionEndTimeUtc : LocalDateTime.now();
        return java.time.Duration.between(sessionStartTimeUtc, endTime).getSeconds();
    }
}