//  对话历史业务逻辑层（简化版）
package com.eeg.service;

import com.eeg.entity.ConversationHistory;
import com.eeg.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 对话历史业务逻辑层 - 聚焦核心对话会话管理功能
 * 支持对话会话概念，提供类似主流AI产品的对话体验
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationHistoryService {

    private final ConversationHistoryRepository conversationRepository;
    private final ObjectMapper objectMapper;

    // ========== 对话会话管理 ==========

    /**
     * 新建对话会话 - 核心功能，创建新的对话会话
     */
    @Transactional
    public ConversationSession createNewConversationSession(Long userId) {
        try {
            // 生成会话ID：user_{userId}_{timestamp}
            String sessionId = generateSessionId(userId);

            log.info("为用户 {} 创建新对话会话 - 会话ID: {}", userId, sessionId);

            ConversationSession session = new ConversationSession();
            session.setSessionId(sessionId);
            session.setUserId(userId);
            session.setTitle("新对话");
            session.setCreatedAt(LocalDateTime.now());
            session.setMessageCount(0);

            return session;

        } catch (Exception e) {
            log.error("创建新对话会话失败 - 用户ID: {}", userId, e);
            throw new RuntimeException("创建新对话会话失败: " + e.getMessage());
        }
    }

    /**
     * 保存对话记录到会话 - 核心方法，由AI查询控制器调用
     */
    @Transactional
    public ConversationHistory saveConversationToSession(String sessionId, Long userId, String userQuery,
                                                         String aiResponse, Long eegSessionId,
                                                         Object responseTokens, List<String> toolsUsed,
                                                         Long processingDurationMs) {
        try {
            log.info("保存对话到会话 {} - 用户ID: {}, 查询长度: {} 字符, 回复长度: {} 字符",
                    sessionId, userId, userQuery.length(), aiResponse.length());

            // 验证会话权限
            if (!isSessionOwnedByUser(sessionId, userId)) {
                throw new RuntimeException("无权访问该对话会话");
            }

            ConversationHistory conversation = new ConversationHistory();
            conversation.setUserId(userId);
            conversation.setConversationSessionId(sessionId);
            conversation.setUserQuery(userQuery);
            conversation.setAiResponse(aiResponse);
            conversation.setConversationTimestamp(LocalDateTime.now());
            conversation.setEegSessionId(eegSessionId);
            conversation.setProcessingDurationMs(processingDurationMs);

            // 设置工具使用信息
            boolean usedTools = toolsUsed != null && !toolsUsed.isEmpty();
            conversation.setUsedMcpTools(usedTools);
            if (usedTools) {
                conversation.setToolsUsed(objectMapper.writeValueAsString(toolsUsed));
            }

            // 设置响应token信息
            if (responseTokens != null) {
                conversation.setResponseTokens(objectMapper.writeValueAsString(responseTokens));
            }

            // 自动分类查询类型
            ConversationHistory.QueryCategory category = ConversationHistory.QueryCategory.categorizeQuery(userQuery);
            conversation.setQueryCategory(category.name());

            // 如果是会话的第一条消息，生成会话标题
            List<ConversationHistory> existingMessages = conversationRepository.findSessionMessages(userId, sessionId);
            if (existingMessages.isEmpty()) {
                String title = conversation.generateDefaultTitle();
                conversation.setSessionTitle(title);
                log.info("设置会话标题: {}", title);
            }

            ConversationHistory savedConversation = conversationRepository.save(conversation);

            log.info("对话记录已保存 - ID: {}, 会话: {}, 用户: {}, 类别: {}, 使用工具: {}",
                    savedConversation.getId(), sessionId, userId, category.getDisplayName(), usedTools);

            return savedConversation;

        } catch (JsonProcessingException e) {
            log.error("序列化对话数据时出错", e);
            throw new RuntimeException("保存对话记录失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("保存对话到会话 {} 时出错", sessionId, e);
            throw new RuntimeException("保存对话记录失败: " + e.getMessage());
        }
    }

    /**
     * 获取用户的对话会话列表 - 分页查询
     */
    public Page<ConversationSession> getUserConversationSessions(Long userId, int page, int size) {
        try {
            Pageable pageable = PageRequest.of(page, size);
            Page<ConversationHistory> latestMessages = conversationRepository
                    .findUserConversationSessions(userId, pageable);

            log.debug("获取用户 {} 对话会话列表 - 页码: {}, 大小: {}, 总会话数: {}",
                    userId, page, size, latestMessages.getTotalElements());

            return latestMessages.map(this::convertToConversationSession);

        } catch (Exception e) {
            log.error("获取用户 {} 对话会话列表失败", userId, e);
            throw new RuntimeException("获取对话会话列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取特定会话的所有消息记录
     */
    public List<ConversationHistory> getSessionMessages(Long userId, String sessionId) {
        try {
            // 验证权限
            if (!isSessionOwnedByUser(sessionId, userId)) {
                throw new RuntimeException("无权访问该对话会话");
            }

            List<ConversationHistory> messages = conversationRepository.findSessionMessages(userId, sessionId);

            log.debug("获取会话 {} 的消息记录 - 用户: {}, 消息数: {}", sessionId, userId, messages.size());

            return messages;

        } catch (Exception e) {
            log.error("获取会话 {} 消息失败 - 用户: {}", sessionId, userId, e);
            throw new RuntimeException("获取会话消息失败: " + e.getMessage());
        }
    }

    /**
     * 获取单条对话记录详情
     */
    public Optional<ConversationHistory> getConversationById(Long conversationId, Long userId) {
        try {
            return conversationRepository.findByIdAndUserId(conversationId, userId);
        } catch (Exception e) {
            log.error("获取对话 {} 详情失败 - 用户: {}", conversationId, userId, e);
            throw new RuntimeException("获取对话详情失败: " + e.getMessage());
        }
    }

    // ========== 收藏功能 ==========

    /**
     * 切换会话收藏状态
     */
    @Transactional
    public boolean toggleSessionBookmark(Long userId, String sessionId) {
        try {
            // 验证权限
            if (!isSessionOwnedByUser(sessionId, userId)) {
                throw new RuntimeException("无权访问该对话会话");
            }

            // 获取会话的最新消息以确定当前收藏状态
            List<ConversationHistory> messages = conversationRepository
                    .findLastMessageInSession(userId, sessionId, PageRequest.of(0, 1));

            if (messages.isEmpty()) {
                throw new RuntimeException("会话不存在");
            }

            boolean currentBookmarked = messages.get(0).isBookmarked();
            boolean newBookmarkStatus = !currentBookmarked;

            // 更新整个会话的收藏状态
            conversationRepository.updateSessionBookmarkStatus(userId, sessionId, newBookmarkStatus);

            log.info("用户 {} {} 会话 {}",
                    userId, newBookmarkStatus ? "收藏了" : "取消收藏了", sessionId);

            return newBookmarkStatus;

        } catch (Exception e) {
            log.error("切换会话 {} 收藏状态失败 - 用户: {}", sessionId, userId, e);
            throw new RuntimeException("操作失败: " + e.getMessage());
        }
    }

    /**
     * 获取用户收藏的对话会话
     */
    public Page<ConversationSession> getBookmarkedSessions(Long userId, int page, int size) {
        try {
            Pageable pageable = PageRequest.of(page, size);
            Page<ConversationHistory> bookmarkedMessages = conversationRepository
                    .findBookmarkedSessions(userId, pageable);

            return bookmarkedMessages.map(this::convertToConversationSession);

        } catch (Exception e) {
            log.error("获取用户 {} 收藏会话失败", userId, e);
            throw new RuntimeException("获取收藏会话失败: " + e.getMessage());
        }
    }

    // ========== 会话管理 ==========

    /**
     * 更新会话标题
     */
    @Transactional
    public void updateSessionTitle(Long userId, String sessionId, String title) {
        try {
            // 验证权限
            if (!isSessionOwnedByUser(sessionId, userId)) {
                throw new RuntimeException("无权访问该对话会话");
            }

            // 限制标题长度
            if (title != null && title.length() > 200) {
                title = title.substring(0, 200);
            }

            conversationRepository.updateSessionTitle(userId, sessionId, title);

            log.info("用户 {} 更新会话 {} 标题为: {}", userId, sessionId, title);

        } catch (Exception e) {
            log.error("更新会话标题失败 - 会话: {}, 用户: {}", sessionId, userId, e);
            throw new RuntimeException("更新标题失败: " + e.getMessage());
        }
    }

    /**
     * 删除整个对话会话
     */
    @Transactional
    public void deleteConversationSession(Long userId, String sessionId) {
        try {
            // 验证权限
            if (!isSessionOwnedByUser(sessionId, userId)) {
                throw new RuntimeException("无权访问该对话会话");
            }

            long messageCount = conversationRepository.countByUserIdAndConversationSessionId(userId, sessionId);
            conversationRepository.deleteByUserIdAndConversationSessionId(userId, sessionId);

            log.info("用户 {} 删除了会话 {} - 包含 {} 条消息", userId, sessionId, messageCount);

        } catch (Exception e) {
            log.error("删除会话失败 - 会话: {}, 用户: {}", sessionId, userId, e);
            throw new RuntimeException("删除会话失败: " + e.getMessage());
        }
    }

    /**
     * 批量删除对话会话
     */
    @Transactional
    public void deleteConversationSessions(Long userId, List<String> sessionIds) {
        try {
            int deletedCount = 0;
            for (String sessionId : sessionIds) {
                if (isSessionOwnedByUser(sessionId, userId)) {
                    conversationRepository.deleteByUserIdAndConversationSessionId(userId, sessionId);
                    deletedCount++;
                }
            }

            log.info("用户 {} 批量删除了 {} 个会话", userId, deletedCount);

        } catch (Exception e) {
            log.error("批量删除会话失败 - 用户: {}", userId, e);
            throw new RuntimeException("批量删除失败: " + e.getMessage());
        }
    }

    // ========== 统计功能 ==========

    /**
     * 获取用户对话统计信息
     */
    public ConversationStatistics getUserConversationStatistics(Long userId) {
        try {
            log.info("获取用户 {} 的对话统计信息", userId);

            long totalSessions = conversationRepository.countUserConversationSessions(userId);
            long totalMessages = conversationRepository.countByUserId(userId);
            long bookmarkedSessions = conversationRepository.countBookmarkedSessions(userId);
            long toolUsageMessages = conversationRepository.countByUserIdAndUsedMcpToolsTrue(userId);

            Double avgProcessingTime = conversationRepository.getAverageProcessingTime(userId);

            return new ConversationStatistics(
                    totalSessions,
                    totalMessages,
                    bookmarkedSessions,
                    toolUsageMessages,
                    avgProcessingTime != null ? avgProcessingTime : 0.0
            );

        } catch (Exception e) {
            log.error("获取用户 {} 对话统计失败", userId, e);
            throw new RuntimeException("获取统计信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取用户最近的对话活动
     */
    public List<ConversationHistory> getRecentActivity(Long userId, int limit) {
        try {
            Pageable pageable = PageRequest.of(0, Math.min(limit, 50));
            return conversationRepository.findRecentActivity(userId, pageable);
        } catch (Exception e) {
            log.error("获取用户 {} 最近活动失败", userId, e);
            throw new RuntimeException("获取最近活动失败: " + e.getMessage());
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 生成会话ID
     */
    private String generateSessionId(Long userId) {
        long timestamp = System.currentTimeMillis();
        return String.format("user_%d_%d", userId, timestamp);
    }

    /**
     * 验证会话是否属于用户
     */
    /**
     * 验证会话是否属于用户 - 修复版
     * 对于新创建的会话，通过会话ID格式验证权限
     */
    private boolean isSessionOwnedByUser(String sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return false;
        }

        try {
            // 首先检查数据库中是否已存在该会话的记录
            boolean existsInDb = conversationRepository.existsByConversationSessionIdAndUserId(sessionId, userId);

            if (existsInDb) {
                return true;
            }

            // 如果数据库中不存在，检查是否是新创建的会话ID
            // 新会话ID格式：user_{userId}_{timestamp}
            if (sessionId.startsWith("user_" + userId + "_")) {
                try {
                    // 验证时间戳部分是否为有效数字
                    String timestampPart = sessionId.substring(("user_" + userId + "_").length());
                    Long.parseLong(timestampPart);

                    // 可选：验证时间戳是否在合理范围内（例如最近1小时内创建）
                    long timestamp = Long.parseLong(timestampPart);
                    long currentTime = System.currentTimeMillis();
                    long oneHourAgo = currentTime - (60 * 60 * 1000); // 1小时前

                    if (timestamp >= oneHourAgo && timestamp <= currentTime + (5 * 60 * 1000)) { // 允许5分钟的时钟偏差
                        log.debug("验证新创建的会话ID: {} - 用户: {}", sessionId, userId);
                        return true;
                    }
                } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                    log.warn("会话ID格式无效: {} - 用户: {}", sessionId, userId);
                    return false;
                }
            }

            return false;

        } catch (Exception e) {
            log.warn("验证会话权限时出错 - 会话: {}, 用户: {}", sessionId, userId, e);
            return false;
        }
    }

    /**
     * 转换为会话对象
     */
    private ConversationSession convertToConversationSession(ConversationHistory latestMessage) {
        String sessionId = latestMessage.getConversationSessionId();

        // 获取会话消息统计
        long messageCount = conversationRepository
                .countByUserIdAndConversationSessionId(latestMessage.getUserId(), sessionId);

        // 获取第一条消息以确定创建时间
        List<ConversationHistory> firstMessages = conversationRepository
                .findFirstMessageInSession(latestMessage.getUserId(), sessionId, PageRequest.of(0, 1));

        LocalDateTime createdAt = firstMessages.isEmpty() ?
                latestMessage.getCreatedAt() : firstMessages.get(0).getCreatedAt();

        ConversationSession session = new ConversationSession();
        session.setSessionId(sessionId);
        session.setUserId(latestMessage.getUserId());
        session.setTitle(latestMessage.getSessionTitle() != null ?
                latestMessage.getSessionTitle() : latestMessage.generateDefaultTitle());
        session.setCreatedAt(createdAt);
        session.setUpdatedAt(latestMessage.getUpdatedAt());
        session.setMessageCount(messageCount);
        session.setBookmarked(latestMessage.isBookmarked());
        session.setLastMessage(latestMessage.getQuerySummary());

        return session;
    }

    // ========== 数据类定义 ==========

    /**
     * 对话会话信息
     */
    public static class ConversationSession {
        public String sessionId;
        public Long userId;
        public String title;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        public long messageCount;
        public boolean bookmarked;
        public String lastMessage;

        // Getters and Setters
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

        public long getMessageCount() { return messageCount; }
        public void setMessageCount(long messageCount) { this.messageCount = messageCount; }

        public boolean isBookmarked() { return bookmarked; }
        public void setBookmarked(boolean bookmarked) { this.bookmarked = bookmarked; }

        public String getLastMessage() { return lastMessage; }
        public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }
    }

    /**
     * 对话统计信息
     */
    public static class ConversationStatistics {
        public final long totalSessions;
        public final long totalMessages;
        public final long bookmarkedSessions;
        public final long toolUsageMessages;
        public final double averageProcessingTime;

        public ConversationStatistics(long totalSessions, long totalMessages, long bookmarkedSessions,
                                      long toolUsageMessages, double averageProcessingTime) {
            this.totalSessions = totalSessions;
            this.totalMessages = totalMessages;
            this.bookmarkedSessions = bookmarkedSessions;
            this.toolUsageMessages = toolUsageMessages;
            this.averageProcessingTime = averageProcessingTime;
        }
    }
}