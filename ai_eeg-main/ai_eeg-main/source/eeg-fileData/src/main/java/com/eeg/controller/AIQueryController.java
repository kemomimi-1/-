// 增强版：完整MCP工具集成控制器
package com.eeg.controller;

import com.eeg.service.AIModelService;
import com.eeg.service.AIModelService.AIResponse;
import com.eeg.service.ConversationHistoryService;
import com.eeg.service.ConversationHistoryService.ConversationSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import com.eeg.service.ai.AIQueryStrategyService;
import com.eeg.service.ai.AIContextBuilder;
import com.eeg.entity.ai.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import java.util.stream.Collectors;


/**
 * 增强版AI查询控制器 - 完整的MCP工具集成和智能协作
 * 核心功能：智能工具协作、对话会话管理、上下文保持、工具使用指导
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AIQueryController {

    private final AIModelService aiModelService;
    private final ConversationHistoryService conversationHistoryService;
    private final AIQueryStrategyService aiQueryStrategyService;
    private final AIContextBuilder aiContextBuilder;
    private final ObjectMapper objectMapper;

    
    
    /**
     * 核心AI查询处理接口 - 增强版MCP工具集成
     */
    @PostMapping("/query")
    public Mono<ResponseEntity<Object>> processUserQuery(@RequestBody AIQueryRequest request,
                                                         HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return Mono.just(ResponseEntity.status(401).body(createErrorResponse("用户未登录", null)));
        }

        if (request == null || request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(createErrorResponse("查询内容不能为空", userId)));
        }

        String userQuery = request.getQuery().trim();
        String sessionId = request.getSessionId();
        long startTime = System.currentTimeMillis();

        log.info("收到AI查询请求 - 用户ID: {}, 会话ID: {}, 查询长度: {} 字符",
                userId, sessionId, userQuery.length());
        log.debug("查询内容: {}", userQuery);

        try {
            // 处理会话ID - 如果没有提供，创建新会话
            if (sessionId == null || sessionId.trim().isEmpty()) {
                log.info("未提供会话ID，创建新对话会话 - 用户: {}", userId);
                ConversationSession newSession = conversationHistoryService.createNewConversationSession(userId);
                sessionId = newSession.getSessionId();
                log.info("新会话已创建 - 会话ID: {}", sessionId);
            }

            final String finalSessionId = sessionId;

            // 构建增强版智能协作上下文 - 包含完整的MCP工具集成信息
            Map<String, Object> context = aiContextBuilder.buildEnhancedIntelligentContext(userId, request, userQuery);
            context.put("conversationSessionId", finalSessionId);

            log.info("增强版智能协作上下文构建完成 - 包含 {} 个字段，协作策略: {}，会话ID: {}，MCP工具集成: {}",
                    context.size(), context.get("collaborationStrategy"), finalSessionId, context.get("mcpToolsReady"));

            // 调用AI服务处理查询
            return aiModelService.processUserQuery(userId, userQuery, context)
                    .map(aiResponse -> {
                        long endTime = System.currentTimeMillis();
                        long processingDuration = endTime - startTime;

                        if (aiResponse.success()) {
                            // 分析工具协作情况
                            Map<String, Object> collaborationStats = aiContextBuilder.analyzeEnhancedToolCollaboration(aiResponse);

                            log.info("AI查询成功 - 用户ID: {}, 会话ID: {}, 处理时长: {}ms, 工具协作统计: {}",
                                    userId, finalSessionId, processingDuration, collaborationStats);

                            // 保存对话记录到会话
                            try {
                                saveEnhancedConversationToSession(finalSessionId, userId, userQuery, aiResponse,
                                        context, processingDuration, collaborationStats);
                            } catch (Exception e) {
                                log.error("保存对话记录失败 - 会话ID: {}, 用户ID: {}, 但AI查询正常完成",
                                        finalSessionId, userId, e);
                            }

                            return ResponseEntity.ok(createEnhancedSuccessResponse(aiResponse, userId, finalSessionId,
                                    userQuery, collaborationStats));
                        } else {
                            log.warn("AI查询失败 - 用户ID: {}, 会话ID: {}, 错误: {}",
                                    userId, finalSessionId, aiResponse.content());

                            try {
                                saveFailedConversationToSession(finalSessionId, userId, userQuery,
                                        aiResponse.content(), processingDuration);
                            } catch (Exception e) {
                                log.error("保存失败对话记录出错 - 会话ID: {}, 用户ID: {}", finalSessionId, userId, e);
                            }

                            return ResponseEntity.badRequest()
                                    .body(createErrorResponse("AI处理失败: " + aiResponse.content(), userId));
                        }
                    })
                    .onErrorResume(error -> {
                        long endTime = System.currentTimeMillis();
                        long processingDuration = endTime - startTime;

                        log.error("AI查询处理异常 - 用户ID: {}, 会话ID: {}, 查询: {}, 处理时长: {}ms",
                                userId, finalSessionId, userQuery, processingDuration, error);

                        try {
                            saveFailedConversationToSession(finalSessionId, userId, userQuery,
                                    "服务器内部错误: " + error.getMessage(), processingDuration);
                        } catch (Exception e) {
                            log.error("保存异常对话记录出错 - 会话ID: {}, 用户ID: {}", finalSessionId, userId, e);
                        }

                        return Mono.just(ResponseEntity.internalServerError()
                                .body(createErrorResponse("服务器内部错误: " + error.getMessage(), userId)));
                    });

        } catch (Exception e) {
            long processingDuration = System.currentTimeMillis() - startTime;
            log.error("AI查询预处理失败 - 用户ID: {}, 处理时长: {}ms", userId, processingDuration, e);

            return Mono.just(ResponseEntity.internalServerError()
                    .body(createErrorResponse("请求处理失败: " + e.getMessage(), userId)));
        }
    }


    /**
     * 流式AI查询端点 - 返回SSE事件流，实现真正的逐字输出
     * 事件类型：thinking（思考状态）、chunk（文本片段）、done（完成）
     * 流结束后自动保存对话到历史会话
     */
    @PostMapping(value = "/query-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> queryStream(@RequestBody AIQueryRequest request,
                                                      HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("userId");
        if (userId == null) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error").data("{\"message\":\"用户未登录\"}").build());
        }

        if (request == null || request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error").data("{\"message\":\"查询内容不能为空\"}").build());
        }

        String userQuery = request.getQuery().trim();
        String sessionId = request.getSessionId();
        long startTime = System.currentTimeMillis();

        log.info("收到流式AI查询 - 用户ID: {}, 查询长度: {} 字符", userId, userQuery.length());

        try {
            // 处理会话ID - 如果没有提供，创建新会话
            if (sessionId == null || sessionId.trim().isEmpty()) {
                ConversationSession newSession = conversationHistoryService.createNewConversationSession(userId);
                sessionId = newSession.getSessionId();
                log.info("流式查询创建新会话 - 会话ID: {}", sessionId);
            }

            final String finalSessionId = sessionId;

            // 构建上下文
            Map<String, Object> context = aiContextBuilder.buildEnhancedIntelligentContext(userId, request, userQuery);
            context.put("conversationSessionId", finalSessionId);

            // 用于累积完整AI回复内容的缓冲区
            StringBuilder contentBuffer = new StringBuilder();

            // 调用流式处理，并拦截事件以保存会话
            return aiModelService.processUserQueryStream(userId, userQuery, context)
                    .doOnNext(event -> {
                        // 拦截 chunk 事件，累积完整内容
                        if ("chunk".equals(event.event()) && event.data() != null) {
                            try {
                                var node = objectMapper.readTree(event.data());
                                if (node.has("text")) {
                                    contentBuffer.append(node.get("text").asText());
                                }
                            } catch (Exception e) {
                                // JSON解析失败，忽略
                            }
                        }
                    })
                    .map(event -> {
                        // 在 done 事件中注入 sessionId 并同步保存会话（确保写入完成再通知前端）
                        if ("done".equals(event.event()) && event.data() != null) {
                            long duration = System.currentTimeMillis() - startTime;
                            String fullContent = contentBuffer.toString();
                            if (!fullContent.isEmpty()) {
                                try {
                                    conversationHistoryService.saveConversationToSession(
                                            finalSessionId, userId, userQuery, fullContent,
                                            null, null, null, duration
                                    );
                                    log.info("流式对话已保存到会话 {} - 用户ID: {}, 内容长度: {}, 耗时: {}ms",
                                            finalSessionId, userId, fullContent.length(), duration);
                                } catch (Exception e) {
                                    log.error("保存流式对话记录失败 - 会话ID: {}, 用户ID: {}", finalSessionId, userId, e);
                                }
                            }

                            try {
                                var node = objectMapper.readTree(event.data());
                                Map<String, Object> doneData = objectMapper.convertValue(node,
                                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                                doneData.put("sessionId", finalSessionId);
                                return ServerSentEvent.<String>builder()
                                        .event("done")
                                        .data(objectMapper.writeValueAsString(doneData))
                                        .build();
                            } catch (Exception e) {
                                // 注入失败，返回原事件
                            }
                        }
                        return event;
                    });

        } catch (Exception e) {
            log.error("流式查询预处理失败 - 用户ID: {}", userId, e);
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error").data("{\"message\":\"请求处理失败: " + e.getMessage() + "\"}").build());
        }
    }


    /**
     * 保存增强版对话记录到会话
     */
    private void saveEnhancedConversationToSession(String sessionId, Long userId, String userQuery, AIResponse aiResponse,
                                                   Map<String, Object> context, long processingDuration,
                                                   Map<String, Object> collaborationStats) {
        try {
            Long eegSessionId = aiContextBuilder.extractEegSessionId(context);
            List<String> toolsUsed = aiContextBuilder.extractToolsUsed(aiResponse);

            // 安全处理 usage 对象
            Map<String, Object> enhancedUsage = new HashMap<>();
            if (aiResponse.usage() != null) {
                try {
                    if (aiResponse.usage() instanceof com.fasterxml.jackson.databind.JsonNode) {
                        Map<String, Object> usageMap = objectMapper.convertValue(
                                aiResponse.usage(),
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
                        );
                        enhancedUsage.putAll(usageMap);
                    } else if (aiResponse.usage() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> usageMap = (Map<String, Object>) aiResponse.usage();
                        enhancedUsage.putAll(usageMap);
                    } else {
                        enhancedUsage.put("rawUsage", aiResponse.usage().toString());
                    }
                } catch (Exception e) {
                    log.warn("转换usage数据时出错，使用字符串形式存储: {}", e.getMessage());
                    enhancedUsage.put("rawUsage", aiResponse.usage().toString());
                    enhancedUsage.put("conversionError", e.getMessage());
                }
            }

            // 添加协作统计信息
            enhancedUsage.put("collaborationStats", collaborationStats);
            enhancedUsage.put("toolCollaborationEfficiency", collaborationStats.get("collaborationEfficiency"));
            enhancedUsage.put("mcpToolsIntegrationSuccess", true);

            conversationHistoryService.saveConversationToSession(
                    sessionId,
                    userId,
                    userQuery,
                    aiResponse.content(),
                    eegSessionId,
                    enhancedUsage,
                    toolsUsed,
                    processingDuration
            );

            log.debug("增强版对话记录已保存到会话 {} - 用户ID: {}, 协作类型: {}, 工具数: {}",
                    sessionId, userId, collaborationStats.get("collaborationType"), collaborationStats.get("toolCount"));

        } catch (Exception e) {
            log.error("保存增强版对话记录到会话 {} 时出错 - 用户ID: {}", sessionId, userId, e);
            // 不要抛出异常，避免影响主要的AI查询响应
        }
    }

    /**
     * 保存失败对话记录到会话
     */
    private void saveFailedConversationToSession(String sessionId, Long userId, String userQuery,
                                                 String errorMessage, long processingDuration) {
        try {
            conversationHistoryService.saveConversationToSession(
                    sessionId,
                    userId,
                    userQuery,
                    "【系统错误】" + errorMessage,
                    null,
                    null,
                    null,
                    processingDuration
            );

            log.debug("失败对话记录已保存到会话 {} - 用户ID: {}", sessionId, userId);

        } catch (Exception e) {
            log.error("保存失败对话记录到会话 {} 时出错 - 用户ID: {}", sessionId, userId, e);
        }
    }

    /**
     * 创建增强版成功响应
     */
    private Object createEnhancedSuccessResponse(AIResponse aiResponse, Long userId, String sessionId,
                                                 String originalQuery, Map<String, Object> collaborationStats) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("timestamp", System.currentTimeMillis());
        response.put("service", "EEG_AI_Assistant_v3_Enhanced_MCP_Integration");
        response.put("userId", userId);
        response.put("sessionId", sessionId);
        response.put("originalQuery", originalQuery);
        response.put("content", aiResponse.content());
        response.put("usage", aiResponse.usage());
        response.put("aiProcessingComplete", true);
        response.put("mcpToolsIntegrated", true);

        // 协作分析信息
        response.put("collaborationAnalysis", collaborationStats);

        if (aiResponse.toolResults() != null && !aiResponse.toolResults().isEmpty()) {
            List<Map<String, Object>> toolAnalysis = aiResponse.toolResults().stream()
                    .map(tr -> {
                        Map<String, Object> toolInfo = new HashMap<>();
                        toolInfo.put("toolName", tr.functionName());
                        toolInfo.put("toolTier", aiQueryStrategyService.getToolTier(tr.functionName()));
                        toolInfo.put("success", !tr.result().toString().contains("error"));
                        toolInfo.put("category", aiQueryStrategyService.getToolCategory(tr.functionName()));
                        return toolInfo;
                    })
                    .collect(Collectors.toList());

            response.put("toolsUsed", toolAnalysis);
            response.put("totalToolsCount", aiResponse.toolResults().size());
        }

        return response;
    }

    /**
     * 创建错误响应
     */
    private Object createErrorResponse(String errorMessage, Long userId) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", errorMessage);
        response.put("timestamp", System.currentTimeMillis());
        response.put("service", "EEG_AI_Assistant_v3_Enhanced_MCP_Integration");
        response.put("userId", userId);
        response.put("mcpToolsIntegrated", false);
        return response;
    }

    /**
     * 获取AI能力描述接口 - 增强版
     */
    @GetMapping("/capabilities")
    public ResponseEntity<Object> getAICapabilities(HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("userId");

        try {
            Map<String, Object> capabilities = buildEnhancedCapabilitiesResponse();
            log.info("返回增强版AI能力描述 - 用户ID: {}", userId);
            return ResponseEntity.ok(createCapabilitiesSuccessResponse(capabilities, userId));

        } catch (Exception e) {
            log.error("获取增强版AI能力描述失败", e);
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("获取能力描述失败: " + e.getMessage(), userId));
        }
    }

    /**
     * 构建增强版能力描述响应
     */
    private Map<String, Object> buildEnhancedCapabilitiesResponse() {
        Map<String, Object> capabilities = new HashMap<>();

        capabilities.put("name", "EEG AI智能协作助手");
        capabilities.put("version", "v3.2-Enhanced-MCP-Integration");
        capabilities.put("description", "基于通义千问-Max和完整MCP工具协作的专业脑电数据分析AI助手，支持对话会话管理和15个核心分析工具");
        capabilities.put("provider", "通义千问-Max + 完整MCP工具协作协议v3.2");

        // 核心能力
        capabilities.put("coreCapabilities", List.of(
                "对话会话管理 - 支持新建对话、继续历史对话、会话收藏和删除",
                "智能工具协作决策 - 根据查询复杂度自动选择最优工具组合",
                "自适应查询分析 - 深度理解用户需求并制定执行策略",
                "多维度EEG数据分析 - 时域、频域、空间、质量全方位分析",
                "大数据智能处理 - 自动选择最优数据处理和分析策略",
                "实时协作监控 - 实时评估工具协作效率和质量",
                "AI自主SQL执行 - 处理复杂自定义查询需求",
                "专业神经科学解释 - 基于科学知识的数据解读和建议",
                "完整MCP工具集成 - 15个专业工具完全集成和协作"
        ));

        // 会话管理功能
        capabilities.put("sessionManagement", Map.of(
                "newConversation", "创建新的对话会话",
                "continueConversation", "在历史会话中继续对话",
                "sessionBookmark", "收藏重要的对话会话",
                "sessionDelete", "删除不需要的对话会话",
                "sessionHistory", "查看和管理历史对话",
                "contextAware", "保持对话上下文和连续性",
                "enhancedIntegration", "完整的MCP工具集成支持"
        ));

        // 14个核心工具详情  后面需要加入第十五个工具时间点查询工具
        capabilities.put("mcpToolsComplete", Map.of(
                "PRIMARY_TOOLS", Map.of(
                        "getActiveSessionContext", "智能实时会话状态分析",
                        "queryLatestBandPowerData", "专业频域数据查询和分析",
                        "generateComprehensiveSessionSummary", "大数据智能摘要分析"
                ),
                "SECONDARY_TOOLS", Map.of(
                        "getSessionDetails", "精确会话信息获取",
                        "monitorSignalQuality", "实时信号质量监测和评估",
                        "getUserStatistics", "用户数据统计和洞察"
                ),
                "AUXILIARY_TOOLS", Map.of(
                        "queryRawEEGData", "原始EEG时序数据查询",
                        "queryFilteredEEGData", "滤波处理数据查询",
                        "assessSessionDataVolume", "数据量智能评估和策略推荐"
                ),
                "SPECIALIZED_TOOLS", Map.of(
                        "compareSessionDataQuality", "多会话质量对比分析",
                        "querySessionsByConditions", "条件筛选和会话分析",
                        "getSessionTechnicalSpecs", "详细技术规格获取",
                        "getSessionHistory", "完整历史记录管理"
                ),
                "AI_AUTONOMOUS", Map.of(
                        "executeCustomQuery", "AI自主SQL查询执行"
                )
        ));

        // 智能协作模式
        capabilities.put("collaborationModes", Map.of(
                "SINGLE_TOOL_DIRECT", "单工具直接执行 - 适用于简单精确查询",
                "DUAL_TOOL_COMBO", "双工具协作 - 适用于关联分析需求",
                "MULTI_TOOL_ANALYSIS", "多工具分析 - 适用于复杂多维分析",
                "MULTI_TOOL_RESEARCH", "多工具研究 - 适用于深度科研需求",
                "ENHANCED_INTEGRATION", "增强集成模式 - 支持所有15个工具的智能协作"
        ));

        // 技术规格
        capabilities.put("technicalSpecs", Map.ofEntries(
                Map.entry("aiModel", "通义千问-Max"),
                Map.entry("protocol", "智能MCP工具协作协议v3.2-Enhanced"),
                Map.entry("coreToolsCount", 15),
                Map.entry("maxToolCollaboration", 10),
                Map.entry("dataSource", "MySQL + InfluxDB 3.2.1"),
                Map.entry("realTimeProcessing", true),
                Map.entry("intelligentDecision", true),
                Map.entry("collaborationOptimization", true),
                Map.entry("sessionSupport", true),
                Map.entry("enhancedIntegration", true),
                Map.entry("responseFormat", "结构化JSON + 专业EEG解释 + 协作分析 + 会话管理"),
                Map.entry("conversationHistory", "智能记录所有对话和协作统计，支持完整的会话管理"),
                Map.entry("mcpToolsIntegrated", "完整集成所有15个专业EEG分析工具")
        ));

        return capabilities;
    }



    /**
     * 创建能力描述的成功响应
     */
    private Object createCapabilitiesSuccessResponse(Map<String, Object> capabilities, Long userId) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("timestamp", System.currentTimeMillis());
        response.put("service", "EEG_AI_Assistant_v3_Enhanced_MCP_Integration");
        response.put("userId", userId);
        response.put("content", capabilities);
        response.put("mcpToolsIntegrated", true);
        return response;
    }


    }