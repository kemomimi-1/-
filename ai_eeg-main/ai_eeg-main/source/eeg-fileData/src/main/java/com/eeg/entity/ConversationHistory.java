// 对话历史实体类
package com.eeg.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 对话历史实体类 - 记录用户与AI之间的单轮对话
 * 支持对话会话概念，同一conversationSessionId的多条记录构成完整对话
 */
@Data
@Entity
@Table(name = "conversation_history", indexes = {
        @Index(name = "idx_user_session", columnList = "user_id, conversation_session_id, created_at DESC"),
        @Index(name = "idx_user_created", columnList = "user_id, created_at DESC"),
        @Index(name = "idx_session_created", columnList = "conversation_session_id, created_at ASC")
})
@NoArgsConstructor
@AllArgsConstructor
public class ConversationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 用户ID - 确保数据隔离的关键字段
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 对话会话ID - 同一会话ID的多条记录构成完整对话
     * 格式: user_{userId}_{timestamp} 或 UUID
     */
    @Column(name = "conversation_session_id", nullable = false, length = 100)
    private String conversationSessionId;

    /**
     * 用户查询内容 - 存储用户的原始问题
     */
    @Column(name = "user_query", columnDefinition = "TEXT", nullable = false)
    private String userQuery;

    /**
     * AI回复内容 - 存储AI的完整回答
     */
    @Column(name = "ai_response", columnDefinition = "LONGTEXT", nullable = false)
    private String aiResponse;

    /**
     * 对话发生的时间戳 - 业务时间，用于对话排序和查询
     */
    @Column(name = "conversation_timestamp", nullable = false)
    private LocalDateTime conversationTimestamp;

    /**
     * 关联的EEG会话ID - 可选，用于关联具体的数据传输会话
     */
    @Column(name = "eeg_session_id")
    private Long eegSessionId;

    /**
     * AI响应的token使用情况 - JSON格式存储，用于统计和分析
     */
    @Column(name = "response_tokens", columnDefinition = "JSON")
    private String responseTokens;

    /**
     * 查询处理时长（毫秒） - 用于性能分析
     */
    @Column(name = "processing_duration_ms")
    private Long processingDurationMs;

    /**
     * 是否使用了MCP工具 - 标记是否调用了工具
     */
    @Column(name = "used_mcp_tools")
    private Boolean usedMcpTools = false;

    /**
     * 使用的工具列表 - JSON格式存储工具调用信息
     */
    @Column(name = "tools_used", columnDefinition = "JSON")
    private String toolsUsed;

    /**
     * 查询类型分类 - 用于对话内容分类
     */
    @Column(name = "query_category", length = 50)
    private String queryCategory;

    /**
     * 用户满意度评分 - 可选，用户可对AI回答进行评分（1-5星）
     */
    @Column(name = "user_rating")
    private Integer userRating;

    /**
     * 对话备注 - 可选，用于用户添加备注或标签
     */
    @Column(name = "notes", length = 500)
    private String notes;

    /**
     * 是否收藏 - 用户可以收藏重要的对话会话
     */
    @Column(name = "is_bookmarked")
    private Boolean isBookmarked = false;

    /**
     * 对话会话标题 - 自动生成或用户自定义
     */
    @Column(name = "session_title", length = 200)
    private String sessionTitle;

    /**
     * 创建时间 - 记录插入数据库的时间
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间 - 记录最后修改的时间
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== 便利方法 ==========

    /**
     * 检查是否有关联的EEG会话
     */
    public boolean hasEegSession() {
        return eegSessionId != null && eegSessionId > 0;
    }

    /**
     * 检查是否使用了工具
     */
    public boolean hasUsedTools() {
        return usedMcpTools != null && usedMcpTools;
    }

    /**
     * 检查是否被收藏
     */
    public boolean isBookmarked() {
        return isBookmarked != null && isBookmarked;
    }

    /**
     * 检查是否有用户评分
     */
    public boolean hasUserRating() {
        return userRating != null && userRating >= 1 && userRating <= 5;
    }

    /**
     * 获取查询内容的摘要（前100个字符）
     */
    public String getQuerySummary() {
        if (userQuery == null) return "";
        return userQuery.length() > 100 ? userQuery.substring(0, 100) + "..." : userQuery;
    }

    /**
     * 获取回复内容的摘要（前200个字符）
     */
    public String getResponseSummary() {
        if (aiResponse == null) return "";
        return aiResponse.length() > 200 ? aiResponse.substring(0, 200) + "..." : aiResponse;
    }

    /**
     * 计算对话内容的总长度
     */
    public int getTotalContentLength() {
        int queryLength = userQuery != null ? userQuery.length() : 0;
        int responseLength = aiResponse != null ? aiResponse.length() : 0;
        return queryLength + responseLength;
    }

    /**
     * 生成默认会话标题（基于第一条用户查询）
     */
    public String generateDefaultTitle() {
        if (userQuery == null) return "新对话";

        String query = userQuery.trim();
        if (query.length() <= 30) {
            return query;
        }

        // 尝试在30字符内找到合适的断点
        int cutPoint = 27;
        while (cutPoint > 20 && !Character.isWhitespace(query.charAt(cutPoint))) {
            cutPoint--;
        }

        if (cutPoint <= 20) {
            cutPoint = 27; // 如果没找到合适断点，就强制截断
        }

        return query.substring(0, cutPoint) + "...";
    }

    /**
     * 对话类型枚举 - 用于分类不同类型的查询
     */
    public enum QueryCategory {
        DATA_QUERY("数据查询"),
        STATISTICS("统计分析"),
        SESSION_MANAGEMENT("会话管理"),
        TECHNICAL_SUPPORT("技术支持"),
        GENERAL_QUESTION("一般问题"),
        EEG_ANALYSIS("EEG分析"),
        UNKNOWN("未分类");

        private final String displayName;

        QueryCategory(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        /**
         * 根据查询内容自动分类
         */
        public static QueryCategory categorizeQuery(String query) {
            if (query == null) return UNKNOWN;

            String lowerQuery = query.toLowerCase();

            if (lowerQuery.contains("数据") || lowerQuery.contains("频谱") || lowerQuery.contains("最新")) {
                return DATA_QUERY;
            } else if (lowerQuery.contains("统计") || lowerQuery.contains("总共") || lowerQuery.contains("平均")) {
                return STATISTICS;
            } else if (lowerQuery.contains("会话") || lowerQuery.contains("session") || lowerQuery.contains("历史")) {
                return SESSION_MANAGEMENT;
            } else if (lowerQuery.contains("分析") || lowerQuery.contains("eeg") || lowerQuery.contains("脑电")) {
                return EEG_ANALYSIS;
            } else if (lowerQuery.contains("怎么") || lowerQuery.contains("如何") || lowerQuery.contains("帮助")) {
                return TECHNICAL_SUPPORT;
            } else {
                return GENERAL_QUESTION;
            }
        }
    }
}