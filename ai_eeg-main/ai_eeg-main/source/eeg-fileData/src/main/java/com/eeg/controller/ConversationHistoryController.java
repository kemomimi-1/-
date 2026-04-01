// 对话历史控制器
package com.eeg.controller;

import com.eeg.entity.ConversationHistory;
import com.eeg.service.ConversationHistoryService;
import com.eeg.service.ConversationHistoryService.ConversationSession;
import com.eeg.service.ConversationHistoryService.ConversationStatistics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.Data;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 对话历史控制器 - 聚焦核心对话会话管理功能
 * 提供类似主流AI产品的对话会话体验
 */
@Slf4j
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationHistoryController {

    private final ConversationHistoryService conversationHistoryService;

    // ========== 对话会话管理核心接口 ==========

    /**
     * 新建对话会话 - 核心功能
     */
    @PostMapping("/new")
    public ResponseEntity<Object> createNewConversation(HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("用户未登录"));
        }

        try {
            log.info("用户 {} 创建新对话会话", userId);

            ConversationSession newSession = conversationHistoryService.createNewConversationSession(userId);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("session", newSession);
            responseData.put("message", "新对话会话已创建");

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (Exception e) {
            log.error("创建新对话会话失败 - 用户: {}", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("创建新对话失败: " + e.getMessage()));
        }
    }

    /**
     * 获取用户的对话会话列表 - 核心功能
     */
    @GetMapping
    public ResponseEntity<Object> getConversationSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpSession httpSession) {

        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("用户未登录"));
        }

        // 参数验证
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;

        try {
            log.info("获取用户 {} 对话会话列表 - 页码: {}, 大小: {}", userId, page, size);

            Page<ConversationSession> sessions = conversationHistoryService
                    .getUserConversationSessions(userId, page, size);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("sessions", sessions.getContent());
            responseData.put("pagination", createPaginationInfo(sessions));
            responseData.put("totalSessions", sessions.getTotalElements());

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (Exception e) {
            log.error("获取用户 {} 对话会话列表失败", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取对话列表失败: " + e.getMessage()));
        }
    }

    /**
     * 获取特定会话的所有消息记录 - 核心功能
     */
    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<Object> getSessionMessages(
            @PathVariable String sessionId,
            HttpSession httpSession) {

        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("用户未登录"));
        }

        try {
            log.info("获取会话 {} 的消息记录 - 用户: {}", sessionId, userId);

            List<ConversationHistory> messages = conversationHistoryService.getSessionMessages(userId, sessionId);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("sessionId", sessionId);
            responseData.put("messages", messages);
            responseData.put("messageCount", messages.size());

            // 添加会话基本信息
            if (!messages.isEmpty()) {
                ConversationHistory firstMessage = messages.get(0);
                ConversationHistory lastMessage = messages.get(messages.size() - 1);

                responseData.put("sessionInfo", Map.of(
                        "title", firstMessage.getSessionTitle() != null ?
                                firstMessage.getSessionTitle() : firstMessage.generateDefaultTitle(),
                        "createdAt", firstMessage.getCreatedAt(),
                        "updatedAt", lastMessage.getUpdatedAt(),
                        "isBookmarked", lastMessage.isBookmarked()
                ));
            }

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (Exception e) {
            log.error("获取会话 {} 消息失败 - 用户: {}", sessionId, userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取会话消息失败: " + e.getMessage()));
        }
    }

    /**
     * 获取单条对话详情
     */
    @GetMapping("/message/{messageId}")
    public ResponseEntity<Object> getMessageDetails(
            @PathVariable Long messageId,
            HttpSession httpSession) {

        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("用户未登录"));
        }

        try {
            Optional<ConversationHistory> messageOpt = conversationHistoryService
                    .getConversationById(messageId, userId);

            if (messageOpt.isEmpty()) {
                return ResponseEntity.status(404).body(createErrorResponse("消息不存在或无权访问"));
            }

            ConversationHistory message = messageOpt.get();
            Map<String, Object> responseData = createDetailedMessageInfo(message);

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (Exception e) {
            log.error("获取消息 {} 详情失败 - 用户: {}", messageId, userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取消息详情失败: " + e.getMessage()));
        }
    }

    // ========== 会话管理功能 ==========

    /**
     * 更新会话标题
     */
    @PutMapping("/{sessionId}/title")
    public ResponseEntity<Object> updateSessionTitle(
            @PathVariable String sessionId,
            @RequestBody UpdateTitleRequest request,
            HttpSession httpSession) {

        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("用户未登录"));
        }

        if (request == null || request.getTitle() == null || request.getTitle().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(createErrorResponse("标题不能为空"));
        }

        try {
            conversationHistoryService.updateSessionTitle(userId, sessionId, request.getTitle().trim());

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("sessionId", sessionId);
            responseData.put("title", request.getTitle().trim());
            responseData.put("message", "标题已更新");

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (Exception e) {
            log.error("更新会话 {} 标题失败 - 用户: {}", sessionId, userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("更新标题失败: " + e.getMessage()));
        }
    }

    /**
     * 切换会话收藏状态 - 核心功能
     */
    @PostMapping("/{sessionId}/bookmark")
    public ResponseEntity<Object> toggleSessionBookmark(
            @PathVariable String sessionId,
            HttpSession httpSession) {

        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("用户未登录"));
        }

        try {
            boolean isBookmarked = conversationHistoryService.toggleSessionBookmark(userId, sessionId);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("sessionId", sessionId);
            responseData.put("isBookmarked", isBookmarked);
            responseData.put("action", isBookmarked ? "收藏" : "取消收藏");
            responseData.put("message", isBookmarked ? "会话已收藏" : "已取消收藏");

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (Exception e) {
            log.error("切换会话 {} 收藏状态失败 - 用户: {}", sessionId, userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("操作失败: " + e.getMessage()));
        }
    }

    /**
     * 获取收藏的对话会话 - 核心功能
     */
    @GetMapping("/bookmarked")
    public ResponseEntity<Object> getBookmarkedSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpSession httpSession) {

        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("用户未登录"));
        }

        try {
            Page<ConversationSession> sessions = conversationHistoryService
                    .getBookmarkedSessions(userId, page, size);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("sessions", sessions.getContent());
            responseData.put("pagination", createPaginationInfo(sessions));
            responseData.put("filterType", "收藏的对话");

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (Exception e) {
            log.error("获取用户 {} 收藏会话失败", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取收藏会话失败: " + e.getMessage()));
        }
    }

    /**
     * 删除对话会话 - 核心功能
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Object> deleteSession(
            @PathVariable String sessionId,
            HttpSession httpSession) {

        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("用户未登录"));
        }

        try {
            conversationHistoryService.deleteConversationSession(userId, sessionId);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("sessionId", sessionId);
            responseData.put("message", "对话已删除");

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (Exception e) {
            log.error("删除会话失败 - 会话: {}, 用户: {}", sessionId, userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("删除会话失败: " + e.getMessage()));
        }
    }

    /**
     * 批量删除对话会话
     */
    @DeleteMapping("/batch")
    public ResponseEntity<Object> deleteSessions(
            @RequestBody BatchDeleteRequest request,
            HttpSession httpSession) {

        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("用户未登录"));
        }

        if (request == null || request.getSessionIds() == null || request.getSessionIds().isEmpty()) {
            return ResponseEntity.badRequest().body(createErrorResponse("请选择要删除的会话"));
        }

        try {
            conversationHistoryService.deleteConversationSessions(userId, request.getSessionIds());

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("deletedCount", request.getSessionIds().size());
            responseData.put("message", "批量删除成功");

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (Exception e) {
            log.error("批量删除会话失败 - 用户: {}", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("批量删除失败: " + e.getMessage()));
        }
    }

    // ========== 统计信息接口 ==========

    /**
     * 获取用户对话统计信息 - 简化版
     */
    @GetMapping("/statistics")
    public ResponseEntity<Object> getConversationStatistics(HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("用户未登录"));
        }

        try {
            ConversationStatistics stats = conversationHistoryService.getUserConversationStatistics(userId);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("totalSessions", stats.totalSessions);
            responseData.put("totalMessages", stats.totalMessages);
            responseData.put("bookmarkedSessions", stats.bookmarkedSessions);
            responseData.put("toolUsageMessages", stats.toolUsageMessages);
            responseData.put("averageProcessingTime", Math.round(stats.averageProcessingTime * 100.0) / 100.0);
            responseData.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            // 添加百分比统计
            if (stats.totalSessions > 0) {
                responseData.put("bookmarkRate", Math.round((stats.bookmarkedSessions * 100.0 / stats.totalSessions) * 100.0) / 100.0);
            } else {
                responseData.put("bookmarkRate", 0.0);
            }

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (Exception e) {
            log.error("获取用户 {} 对话统计失败", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取统计信息失败: " + e.getMessage()));
        }
    }

    /**
     * 获取用户最近的对话活动
     */
    @GetMapping("/recent-activity")
    public ResponseEntity<Object> getRecentActivity(
            @RequestParam(defaultValue = "10") int limit,
            HttpSession httpSession) {

        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(401).body(createErrorResponse("用户未登录"));
        }

        try {
            List<ConversationHistory> activities = conversationHistoryService.getRecentActivity(userId, limit);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("activities", activities);
            responseData.put("count", activities.size());
            responseData.put("limit", limit);

            return ResponseEntity.ok(createSuccessResponse(responseData));

        } catch (Exception e) {
            log.error("获取用户 {} 最近活动失败", userId, e);
            return ResponseEntity.badRequest().body(createErrorResponse("获取最近活动失败: " + e.getMessage()));
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 创建成功响应
     */
    private Object createSuccessResponse(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("timestamp", System.currentTimeMillis());
        response.put("service", "ConversationHistory");
        response.put("data", data);
        return response;
    }

    /**
     * 创建错误响应
     */
    private Object createErrorResponse(String errorMessage) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", errorMessage);
        response.put("timestamp", System.currentTimeMillis());
        response.put("service", "ConversationHistory");
        return response;
    }

    /**
     * 创建分页信息
     */
    private Map<String, Object> createPaginationInfo(Page<?> page) {
        Map<String, Object> pagination = new HashMap<>();
        pagination.put("currentPage", page.getNumber());
        pagination.put("pageSize", page.getSize());
        pagination.put("totalPages", page.getTotalPages());
        pagination.put("totalElements", page.getTotalElements());
        pagination.put("hasNext", page.hasNext());
        pagination.put("hasPrevious", page.hasPrevious());
        pagination.put("isFirst", page.isFirst());
        pagination.put("isLast", page.isLast());
        return pagination;
    }

    /**
     * 创建详细的消息信息
     */
    private Map<String, Object> createDetailedMessageInfo(ConversationHistory message) {
        Map<String, Object> details = new HashMap<>();
        details.put("id", message.getId());
        details.put("sessionId", message.getConversationSessionId());
        details.put("userQuery", message.getUserQuery());
        details.put("aiResponse", message.getAiResponse());
        details.put("conversationTimestamp", message.getConversationTimestamp());
        details.put("queryCategory", message.getQueryCategory());
        details.put("userRating", message.getUserRating());
        details.put("notes", message.getNotes());
        details.put("usedMcpTools", message.hasUsedTools());
        details.put("toolsUsed", message.getToolsUsed());
        details.put("eegSessionId", message.getEegSessionId());
        details.put("processingDurationMs", message.getProcessingDurationMs());
        details.put("totalContentLength", message.getTotalContentLength());
        details.put("createdAt", message.getCreatedAt());
        details.put("updatedAt", message.getUpdatedAt());
        return details;
    }

    // ========== 请求数据类 ==========

    @Data
    public static class UpdateTitleRequest {
        private String title;
    }

    @Data
    public static class BatchDeleteRequest {
        private List<String> sessionIds;
    }
}