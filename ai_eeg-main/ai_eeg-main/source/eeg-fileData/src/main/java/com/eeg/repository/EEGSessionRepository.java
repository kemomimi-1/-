//  更新的脑电会话数据仓库
package com.eeg.repository;

import com.eeg.entity.EEGSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EEGSessionRepository extends JpaRepository<EEGSession, Long> {

    // 修复：使用created_at排序获取真正最新的会话
    @Query("SELECT s FROM EEGSession s WHERE s.userId = :userId " +
            "ORDER BY s.createdAt DESC, s.id DESC")
    List<EEGSession> findUserMostRecentSessions(@Param("userId") Long userId,
                                                org.springframework.data.domain.Pageable pageable);

    // 修复：会话历史查询也使用created_at排序，确保按真实创建顺序
    @Query("SELECT s FROM EEGSession s WHERE s.userId = :userId " +
            "ORDER BY s.createdAt DESC, s.id DESC")
    List<EEGSession> findUserRecentSessions(@Param("userId") Long userId,
                                            org.springframework.data.domain.Pageable pageable);

    // 修复：最新完成会话使用session_end_time_utc排序，但添加created_at作为备选
    @Query("SELECT s FROM EEGSession s WHERE s.userId = :userId " +
            "AND s.sessionStatus = 'COMPLETED' " +
            "ORDER BY s.sessionEndTimeUtc DESC, s.createdAt DESC, s.id DESC")
    List<EEGSession> findUserLatestCompletedSessions(@Param("userId") Long userId,
                                                     org.springframework.data.domain.Pageable pageable);


    // 查找用户的活跃会话
    Optional<EEGSession> findByUserIdAndSessionStatus(Long userId, EEGSession.SessionStatus status);

    // 查找用户的所有会话，按创建时间倒序
    List<EEGSession> findByUserIdOrderByCreatedAtDesc(Long userId);

    // 查找用户在指定时间范围内的会话
    @Query("SELECT s FROM EEGSession s WHERE s.userId = :userId " +
            "AND s.sessionStartTimeUtc >= :startTime " +
            "AND (s.sessionEndTimeUtc IS NULL OR s.sessionEndTimeUtc <= :endTime) " +
            "ORDER BY s.sessionStartTimeUtc DESC")
    List<EEGSession> findUserSessionsByTimeRange(@Param("userId") Long userId,
                                                 @Param("startTime") LocalDateTime startTime,
                                                 @Param("endTime") LocalDateTime endTime);


    // 统计用户的会话数量
    long countByUserId(Long userId);

    // 统计用户在指定状态的会话数量
    long countByUserIdAndSessionStatus(Long userId, EEGSession.SessionStatus status);

    // 查找所有活跃会话（用于系统监控）
    List<EEGSession> findBySessionStatus(EEGSession.SessionStatus status);

    // 查找超时的活跃会话（用于清理）
    @Query("SELECT s FROM EEGSession s WHERE s.sessionStatus = 'ACTIVE' " +
            "AND s.updatedAt < :timeoutThreshold")
    List<EEGSession> findTimeoutActiveSessions(@Param("timeoutThreshold") LocalDateTime timeoutThreshold);

    // 查找用户在指定时间点正在进行的会话
    @Query("SELECT s FROM EEGSession s WHERE s.userId = :userId " +
            "AND s.sessionStartTimeUtc <= :timePoint " +
            "AND (s.sessionEndTimeUtc IS NULL OR s.sessionEndTimeUtc >= :timePoint)")
    Optional<EEGSession> findUserSessionAtTime(@Param("userId") Long userId,
                                               @Param("timePoint") LocalDateTime timePoint);

    // 查找指定数据流类型有数据的会话
    @Query("SELECT s FROM EEGSession s WHERE s.userId = :userId " +
            "AND (:streamType = 'raw' AND s.rawStreamTotalPackets > 0 " +
            "OR :streamType = 'filt' AND s.filtStreamTotalPackets > 0 " +
            "OR :streamType = 'band' AND s.bandStreamTotalPackets > 0) " +
            "ORDER BY s.sessionStartTimeUtc DESC")
    List<EEGSession> findUserSessionsWithStreamData(@Param("userId") Long userId,
                                                    @Param("streamType") String streamType);

    // 获取用户会话的统计信息 - 修复版本
    @Query(value = "SELECT " +
            "COUNT(*) as totalSessions, " +
            "COUNT(CASE WHEN session_status = 'COMPLETED' THEN 1 END) as completedSessions, " +
            "COUNT(CASE WHEN session_status = 'ACTIVE' THEN 1 END) as activeSessions, " +
            "AVG(COALESCE(total_duration_seconds, 0)) as avgDurationSeconds, " +
            "SUM(COALESCE(raw_stream_total_packets, 0)) as totalRawPackets, " +
            "SUM(COALESCE(filt_stream_total_packets, 0)) as totalFiltPackets, " +
            "SUM(COALESCE(band_stream_total_packets, 0)) as totalBandPackets " +
            "FROM eeg_sessions WHERE user_id = :userId",
            nativeQuery = true)
    Object[] getUserSessionStatistics(@Param("userId") Long userId);

    // 备用方法：分别查询各项统计
    @Query("SELECT COUNT(s) FROM EEGSession s WHERE s.userId = :userId")
    Long getTotalSessionsCount(@Param("userId") Long userId);

    @Query("SELECT COUNT(s) FROM EEGSession s WHERE s.userId = :userId AND s.sessionStatus = 'COMPLETED'")
    Long getCompletedSessionsCount(@Param("userId") Long userId);

    @Query("SELECT COUNT(s) FROM EEGSession s WHERE s.userId = :userId AND s.sessionStatus = 'ACTIVE'")
    Long getActiveSessionsCount(@Param("userId") Long userId);

    @Query("SELECT AVG(s.totalDurationSeconds) FROM EEGSession s WHERE s.userId = :userId AND s.totalDurationSeconds IS NOT NULL")
    Double getAvgDurationSeconds(@Param("userId") Long userId);

    @Query("SELECT SUM(s.rawStreamTotalPackets) FROM EEGSession s WHERE s.userId = :userId")
    Long getTotalRawPackets(@Param("userId") Long userId);

    @Query("SELECT SUM(s.filtStreamTotalPackets) FROM EEGSession s WHERE s.userId = :userId")
    Long getTotalFiltPackets(@Param("userId") Long userId);

    @Query("SELECT SUM(s.bandStreamTotalPackets) FROM EEGSession s WHERE s.userId = :userId")
    Long getTotalBandPackets(@Param("userId") Long userId);

    // 【启动清理】将所有遗留的 ACTIVE 会话标记为 INTERRUPTED
    // 用于 Spring Boot 重启后清理上次未正常结束的会话
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE EEGSession s SET s.sessionStatus = com.eeg.entity.EEGSession.SessionStatus.INTERRUPTED, " +
            "s.sessionEndTimeUtc = :endTime " +
            "WHERE s.sessionStatus = com.eeg.entity.EEGSession.SessionStatus.ACTIVE")
    int markAllActiveSessionsAsInterrupted(@Param("endTime") java.time.LocalDateTime endTime);
}