// BarrageRepository.java - 简化版删除功能
package com.eeg.repository;

import com.eeg.entity.Barrage;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BarrageRepository extends JpaRepository<Barrage, Long> {

    // 获取用户最近的弹幕
    List<Barrage> findByUserIdOrderByCreatedAtDesc(Long userId, PageRequest pageRequest);

    // 获取用户指定时间范围内的弹幕
    @Query("SELECT b FROM Barrage b WHERE b.userId = :userId " +
            "AND b.createdAt BETWEEN :startTime AND :endTime " +
            "ORDER BY b.createdAt DESC")
    List<Barrage> findUserBarragesByTimeRange(@Param("userId") Long userId,
                                              @Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime);

    // 获取用户最新的弹幕
    @Query("SELECT b FROM Barrage b WHERE b.userId = :userId " +
            "ORDER BY b.createdAt DESC LIMIT 1")
    Barrage findLatestBarrageByUserId(@Param("userId") Long userId);

    // 统计用户弹幕数量
    long countByUserId(Long userId);

    // 获取用户不同精神状态的统计
    @Query("SELECT b.primaryState, COUNT(b) FROM Barrage b WHERE b.userId = :userId " +
            "AND b.createdAt >= :since GROUP BY b.primaryState")
    List<Object[]> getUserStateStatistics(@Param("userId") Long userId,
                                          @Param("since") LocalDateTime since);

    // 【新增】根据ID和用户ID查找弹幕 - 确保只能删除自己的弹幕
    Optional<Barrage> findByIdAndUserId(Long id, Long userId);

    // 【新增】根据用户ID和弹幕ID删除弹幕 - 安全删除，只删除用户自己的弹幕
    @Modifying
    @Transactional
    @Query("DELETE FROM Barrage b WHERE b.id = :id AND b.userId = :userId")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}