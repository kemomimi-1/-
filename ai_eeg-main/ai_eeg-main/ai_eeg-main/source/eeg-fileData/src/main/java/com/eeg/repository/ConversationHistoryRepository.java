//  对话历史数据访问层（修复版）
package com.eeg.repository;

import com.eeg.entity.ConversationHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying; // 添加这个导入
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional; // 添加这个导入

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 对话历史数据访问层 - 聚焦核心对话功能
 * 所有查询都基于用户ID进行数据隔离，确保用户数据完全隔离
 */
@Repository
public interface ConversationHistoryRepository extends JpaRepository<ConversationHistory, Long> {

    // ========== 基础查询方法 ==========

    /**
     * 查询用户的所有对话会话（按最后更新时间倒序，去重会话ID）
     */
    @Query("SELECT ch FROM ConversationHistory ch WHERE ch.userId = :userId " +
            "AND ch.id IN (SELECT MAX(ch2.id) FROM ConversationHistory ch2 " +
            "WHERE ch2.userId = :userId GROUP BY ch2.conversationSessionId) " +
            "ORDER BY ch.updatedAt DESC")
    Page<ConversationHistory> findUserConversationSessions(@Param("userId") Long userId, Pageable pageable);

    /**
     * 获取特定对话会话的所有消息记录（按时间正序）
     */
    @Query("SELECT ch FROM ConversationHistory ch WHERE ch.userId = :userId " +
            "AND ch.conversationSessionId = :sessionId " +
            "ORDER BY ch.createdAt ASC")
    List<ConversationHistory> findSessionMessages(@Param("userId") Long userId,
                                                  @Param("sessionId") String sessionId);

    /**
     * 查询特定对话记录（确保只能访问自己的对话）
     */
    Optional<ConversationHistory> findByIdAndUserId(Long id, Long userId);

    /**
     * 检查对话会话是否属于用户
     */
    boolean existsByConversationSessionIdAndUserId(String sessionId, Long userId);

    /**
     * 获取用户的对话会话总数
     */
    @Query("SELECT COUNT(DISTINCT ch.conversationSessionId) FROM ConversationHistory ch WHERE ch.userId = :userId")
    long countUserConversationSessions(@Param("userId") Long userId);

    /**
     * 统计用户的对话总数
     */
    long countByUserId(Long userId);

    // ========== 收藏功能 ==========

    /**
     * 查询用户收藏的对话会话
     */
    @Query("SELECT ch FROM ConversationHistory ch WHERE ch.userId = :userId " +
            "AND ch.isBookmarked = true " +
            "AND ch.id IN (SELECT MAX(ch2.id) FROM ConversationHistory ch2 " +
            "WHERE ch2.userId = :userId AND ch2.isBookmarked = true " +
            "GROUP BY ch2.conversationSessionId) " +
            "ORDER BY ch.updatedAt DESC")
    Page<ConversationHistory> findBookmarkedSessions(@Param("userId") Long userId, Pageable pageable);

    /**
     * 更新整个会话的收藏状态 - 修复版
     */
    @Modifying  // 添加这个注解
    @Transactional  // 添加这个注解
    @Query("UPDATE ConversationHistory ch SET ch.isBookmarked = :bookmarked " +
            "WHERE ch.userId = :userId AND ch.conversationSessionId = :sessionId")
    int updateSessionBookmarkStatus(@Param("userId") Long userId,
                                    @Param("sessionId") String sessionId,
                                    @Param("bookmarked") boolean bookmarked);

    /**
     * 统计用户的收藏对话会话数量
     */
    @Query("SELECT COUNT(DISTINCT ch.conversationSessionId) FROM ConversationHistory ch " +
            "WHERE ch.userId = :userId AND ch.isBookmarked = true")
    long countBookmarkedSessions(@Param("userId") Long userId);

    // ========== 删除方法 ==========

    /**
     * 删除用户的整个对话会话
     */
    void deleteByUserIdAndConversationSessionId(Long userId, String sessionId);

    /**
     * 删除用户的所有对话记录
     */
    void deleteByUserId(Long userId);

    /**
     * 删除用户指定时间之前的对话记录
     */
    void deleteByUserIdAndCreatedAtBefore(Long userId, LocalDateTime beforeTime);

    // ========== 统计查询 ==========

    /**
     * 获取用户最近的对话活动
     */
    @Query("SELECT ch FROM ConversationHistory ch WHERE ch.userId = :userId " +
            "ORDER BY ch.createdAt DESC")
    List<ConversationHistory> findRecentActivity(@Param("userId") Long userId, Pageable pageable);

    /**
     * 获取用户对话的平均处理时长
     */
    @Query("SELECT AVG(ch.processingDurationMs) FROM ConversationHistory ch " +
            "WHERE ch.userId = :userId AND ch.processingDurationMs IS NOT NULL")
    Double getAverageProcessingTime(@Param("userId") Long userId);

    /**
     * 统计用户使用工具的对话数量
     */
    long countByUserIdAndUsedMcpToolsTrue(Long userId);

    /**
     * 获取用户的会话标题更新
     */
    @Query("SELECT ch.conversationSessionId, ch.sessionTitle FROM ConversationHistory ch " +
            "WHERE ch.userId = :userId AND ch.sessionTitle IS NOT NULL " +
            "GROUP BY ch.conversationSessionId, ch.sessionTitle")
    List<Object[]> findSessionTitles(@Param("userId") Long userId);

    /**
     * 更新会话标题 - 修复版
     */
    @Modifying  // 添加这个注解
    @Transactional  // 添加这个注解
    @Query("UPDATE ConversationHistory ch SET ch.sessionTitle = :title " +
            "WHERE ch.userId = :userId AND ch.conversationSessionId = :sessionId")
    int updateSessionTitle(@Param("userId") Long userId,
                           @Param("sessionId") String sessionId,
                           @Param("title") String title);

    // ========== 会话相关的查询 ==========

    /**
     * 获取会话的第一条消息（用于生成标题）
     */
    @Query("SELECT ch FROM ConversationHistory ch WHERE ch.userId = :userId " +
            "AND ch.conversationSessionId = :sessionId " +
            "ORDER BY ch.createdAt ASC")
    List<ConversationHistory> findFirstMessageInSession(@Param("userId") Long userId,
                                                        @Param("sessionId") String sessionId,
                                                        Pageable pageable);

    /**
     * 获取会话的最后一条消息
     */
    @Query("SELECT ch FROM ConversationHistory ch WHERE ch.userId = :userId " +
            "AND ch.conversationSessionId = :sessionId " +
            "ORDER BY ch.createdAt DESC")
    List<ConversationHistory> findLastMessageInSession(@Param("userId") Long userId,
                                                       @Param("sessionId") String sessionId,
                                                       Pageable pageable);

    /**
     * 统计会话中的消息数量
     */
    long countByUserIdAndConversationSessionId(Long userId, String sessionId);
}