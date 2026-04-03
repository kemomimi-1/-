//  MCP工具集成服务
package com.eeg.service;

import com.eeg.config.AIModelConfig;
import com.eeg.entity.ai.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * AI大模型服务类
 * 核心功能：与通义千问的交互、MCP工具调用协调、智能工具选择、错误处理
 */
@Slf4j
@Service
public class AIModelService {

    private final AIModelConfig aiConfig;
    private final MCPToolRegistry mcpToolRegistry;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    // 【新增】大数据处理配置
    private static final int LARGE_DATA_THRESHOLD_MB = 4; // 4MB阈值



    @SuppressWarnings("null")
    public AIModelService(AIModelConfig aiConfig, MCPToolRegistry mcpToolRegistry, ObjectMapper objectMapper) {
        this.aiConfig = aiConfig;
        this.mcpToolRegistry = mcpToolRegistry;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(aiConfig.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + aiConfig.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "EEG-AI-Assistant-Enhanced/3.2")
                .build();

        log.info("增强版AIModelService初始化完成 - 模型: {}, 工具可用: {}, MCP集成版本: v3.2",
                aiConfig.getModel(), aiConfig.isEnableMcpTools(), "Enhanced-Full-Integration");

        // 验证MCP工具集成状态
        validateMCPToolsIntegration();
    }

    /**
     * 验证MCP工具集成状态
     */
        private void validateMCPToolsIntegration() {
        try {
            List<Map<String, Object>> availableTools = mcpToolRegistry.getAllToolsForAI();
            log.info("MCP工具集成验证成功 - 动态加载可用工具数量: {}", availableTools.size());

            if (!availableTools.isEmpty()) {
                log.info("✅ MCP工具集成基本验证通过");
            } else {
                log.warn("⚠️ 未检测到任何已注册的MCP工具");
            }
        } catch (Exception e) {
            log.error("❌ MCP工具加载失败", e);
        }
    }

    /**
     * 增强版：处理用户查询并智能协调MCP工具
     */
    public Mono<AIResponse> processUserQuery(Long userId, String userQuery, Map<String, Object> context) {
        return Mono.fromCallable(() -> {
            log.info("开始处理用户查询 - 用户ID: {}, 查询: {}, MCP工具集成: 启用", userId, userQuery);

            try {
                // 1. 构建增强版消息列表 - 包含完整的工具介绍
                List<Map<String, Object>> messages = buildEnhancedMessages(userQuery, context, userId);

                // 2. 获取完整的MCP工具列表
                List<Map<String, Object>> availableTools = mcpToolRegistry.getAllToolsForAI();
                log.info("获取MCP工具列表成功 - 可用工具数量: {}", availableTools.size());

                // 3. 验证工具可用性
                if (availableTools.isEmpty()) {
                    log.warn("警告：没有可用的MCP工具，AI将在无工具模式下运行");
                }

                // 4. 构建增强版请求
                Map<String, Object> requestBody = buildEnhancedChatCompletionRequest(messages, availableTools);

                // 5. 发送请求并处理响应 - 支持多轮工具调用
                return processEnhancedAIConversation(userId, requestBody, context, 0);

            } catch (Exception e) {
                log.error("处理用户查询时出错 - 用户ID: {}", userId, e);
                return new AIResponse(false, "处理查询时出错: " + e.getMessage(), null, null);
            }
        }).flatMap(response -> Mono.just(response));
    }

    /**
     * 增强版：处理AI对话流程 - 修复多工具协作问题
     */
    @SuppressWarnings("null")
    private AIResponse processEnhancedAIConversation(Long userId, Map<String, Object> requestBody,
                                                     Map<String, Object> context, int recursionDepth) {
        if (recursionDepth >= aiConfig.getMaxToolCalls()) {
            log.warn("达到最大工具调用次数限制: {} - 用户ID: {}", aiConfig.getMaxToolCalls(), userId);
            return new AIResponse(false, "已达到最大工具调用次数限制", null, null);
        }

        // 网络异常重试机制 - 增强版
        int maxRetries = aiConfig.getMaxRetries();
        long baseDelay = 2000; // 2秒基础延迟

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("AI请求尝试 {}/{} - 递归深度: {} - 用户ID: {}", attempt, maxRetries, recursionDepth, userId);

                // 根据尝试次数调整超时时间
                int timeoutSeconds = aiConfig.getTimeout() + (attempt - 1) * 30;

                // 发送请求到通义千问 - 增强版错误处理
                String response = webClient.post()
                        .uri("/chat/completions")
                        .bodyValue(requestBody)
                        .retrieve()
                        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                clientResponse -> clientResponse.bodyToMono(String.class)
                                        .defaultIfEmpty("Unknown API error")
                                        .flatMap(errorBody -> {
                                            log.error("AI API调用错误 - 状态码: {}, 响应: {}",
                                                    clientResponse.statusCode(), errorBody);
                                            return Mono.error(new RuntimeException("API调用失败: " + errorBody));
                                        }))
                        .bodyToMono(String.class)
                        .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                        .onErrorResume(java.util.concurrent.TimeoutException.class,
                                ex -> {
                                    log.warn("AI请求超时 - 用户ID: {}, 超时时间: {}s", userId, timeoutSeconds);
                                    return Mono.error(new RuntimeException("请求超时", ex));
                                })
                        .block();

                if (response == null) {
                    throw new RuntimeException("AI服务未返回响应");
                }

                log.info("AI请求成功 - 用户ID: {}, 尝试次数: {}, 响应长度: {} 字符",
                        userId, attempt, response.length());

                // 解析响应
                JsonNode responseNode = objectMapper.readTree(response);

                // 检查是否有错误
                if (responseNode.has("error")) {
                    JsonNode error = responseNode.get("error");
                    String errorMsg = error.get("message").asText();
                    String errorType = error.has("type") ? error.get("type").asText() : "unknown";
                    log.error("AI API返回错误 - 类型: {}, 消息: {}", errorType, errorMsg);
                    return new AIResponse(false, "AI服务错误: " + errorMsg, null, null);
                }

                JsonNode choices = responseNode.get("choices");
                if (choices == null || choices.size() == 0) {
                    return new AIResponse(false, "AI响应格式错误: 没有choices", null, null);
                }

                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");

                // 检查是否需要调用工具
                JsonNode toolCalls = message.get("tool_calls");
                if (toolCalls != null && toolCalls.size() > 0) {
                    log.info("AI请求调用 {} 个MCP工具 - 用户ID: {}, 递归深度: {}",
                            toolCalls.size(), userId, recursionDepth);
                    return processToolCallsAndContinue(userId, toolCalls, requestBody, context,
                            responseNode.get("usage"), recursionDepth);
                } else {
                    // 直接文本回答
                    String content = message.get("content").asText();
                    log.info("AI返回直接回答 - 用户ID: {}, 内容长度: {} 字符, 递归深度: {}",
                            userId, content.length(), recursionDepth);
                    return new AIResponse(true, content, null, responseNode.get("usage"));
                }

            } catch (RuntimeException e) {
                if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                    log.warn("AI请求超时 - 用户ID: {}, 尝试 {}/{}, 超时时间: {}秒",
                            userId, attempt, maxRetries, aiConfig.getTimeout() + (attempt - 1) * 30);

                    if (attempt == maxRetries) {
                        return new AIResponse(false,
                                "AI服务请求超时，已重试 " + maxRetries + " 次。请稍后再试或检查网络连接。", null, null);
                    }
                } else {
                    log.error("AI请求运行时异常 - 用户ID: {}, 尝试 {}/{}: {}",
                            userId, attempt, maxRetries, e.getMessage());
                    if (attempt == maxRetries) {
                        return new AIResponse(false, "AI服务调用失败: " + e.getMessage(), null, null);
                    }
                }

            } catch (java.io.IOException e) {
                log.warn("网络连接异常 - 用户ID: {}, 尝试 {}/{}: {}", userId, attempt, maxRetries, e.getMessage());

                if (attempt == maxRetries) {
                    return new AIResponse(false,
                            "网络连接异常，已重试 " + maxRetries + " 次。错误详情: " + e.getMessage() +
                                    "。请检查网络连接或稍后再试。", null, null);
                }

            } catch (Exception e) {
                log.error("AI交互处理异常 - 用户ID: {}, 尝试 {}/{}", userId, attempt, maxRetries, e);

                if (attempt == maxRetries) {
                    return new AIResponse(false, "AI交互处理失败: " + e.getMessage(), null, null);
                }
            }

            // 重试延迟：指数退避策略
            if (attempt < maxRetries) {
                long delay = baseDelay * (long) Math.pow(2, attempt - 1);
                try {
                    log.info("等待 {}ms 后重试... - 用户ID: {}", delay, userId);
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new AIResponse(false, "重试被中断", null, null);
                }
            }
        }

        return new AIResponse(false, "所有重试尝试均失败", null, null);
    }

    /**
     * 修复版：处理工具调用并继续对话 - 解决多工具协作问题
     */
    private AIResponse processToolCallsAndContinue(Long userId, JsonNode toolCalls,
                                                   Map<String, Object> originalRequest,
                                                   Map<String, Object> context,
                                                   JsonNode usage, int recursionDepth) {
        List<ToolCallResult> toolResults = new ArrayList<>();

        try {
            log.info("开始执行MCP工具调用 - 用户ID: {}, 工具数量: {}, 递归深度: {}",
                    userId, toolCalls.size(), recursionDepth);

            // 执行所有工具调用
            for (JsonNode toolCall : toolCalls) {
                String toolId = toolCall.get("id").asText();
                JsonNode function = toolCall.get("function");
                String functionName = function.get("name").asText();
                String argumentsStr = function.get("arguments").asText();

                log.info("执行MCP工具调用 - 用户ID: {}, 工具ID: {}, 工具名: {}, 参数: {}",
                        userId, toolId, functionName, argumentsStr);

                try {
                    // 解析参数
                    @SuppressWarnings("unchecked")
                    Map<String, Object> arguments = objectMapper.readValue(argumentsStr, Map.class);

                    // 执行MCP工具 - 通过MCPToolRegistry
                    Object toolResult = mcpToolRegistry.executeTool(userId, functionName, arguments, context);

                    toolResults.add(new ToolCallResult(toolId, functionName, arguments, toolResult));
                    log.info("MCP工具调用成功 - 用户ID: {}, 工具ID: {}, 工具名: {}", userId, toolId, functionName);

                } catch (Exception e) {
                    log.error("MCP工具调用失败 - 用户ID: {}, 工具ID: {}, 工具名: {}", userId, toolId, functionName, e);
                    Map<String, Object> errorResult = Map.of(
                            "error", "工具调用失败: " + e.getMessage(),
                            "toolName", functionName,
                            "toolId", toolId,
                            "userId", userId
                    );
                    toolResults.add(new ToolCallResult(toolId, functionName, Map.of(), errorResult));
                }
            }

            // 【关键修复】：将工具结果发送回AI进行总结 - 确保递归继续
            return sendToolResultsToAI(userId, originalRequest, toolResults, context, usage, recursionDepth + 1);

        } catch (Exception e) {
            log.error("处理MCP工具调用时出错 - 用户ID: {}", userId, e);
            return new AIResponse(false, "工具调用处理失败: " + e.getMessage(), toolResults, usage);
        }
    }

    /**
     * 修复版：发送工具结果给AI进行总结 - 自动处理大数据量
     */
    private AIResponse sendToolResultsToAI(Long userId, Map<String, Object> originalRequest,
                                           List<ToolCallResult> toolResults, Map<String, Object> context,
                                           JsonNode previousUsage, int recursionDepth) {
        try {
            log.info("发送MCP工具结果给AI进行总结 - 用户ID: {}, 工具结果数: {}, 递归深度: {}",
                    userId, toolResults.size(), recursionDepth);

            // 【关键修复】：检测数据量并智能处理
            List<ToolCallResult> processedResults = intelligentDataProcessing(userId, toolResults, context);

            // 获取原始消息列表
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) originalRequest.get("messages");

            // 添加助手的工具调用消息
            Map<String, Object> assistantMessage = new HashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", null);

            List<Map<String, Object>> toolCallsForMessage = new ArrayList<>();
            for (ToolCallResult result : toolResults) { // 使用原始工具调用信息
                Map<String, Object> toolCall = new HashMap<>();
                toolCall.put("id", result.toolId());
                toolCall.put("type", "function");

                Map<String, Object> function = new HashMap<>();
                function.put("name", result.functionName());
                function.put("arguments", objectMapper.writeValueAsString(result.arguments()));
                toolCall.put("function", function);

                toolCallsForMessage.add(toolCall);
            }
            assistantMessage.put("tool_calls", toolCallsForMessage);
            messages.add(assistantMessage);

            // 添加处理后的工具结果消息
            for (ToolCallResult result : processedResults) { // 使用处理后的结果
                Map<String, Object> toolMessage = new HashMap<>();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", result.toolId());

                String toolResultJson = convertToolResultToJson(result.result());
                toolMessage.put("content", toolResultJson);
                messages.add(toolMessage);

                log.debug("添加处理后工具结果到消息 - 用户ID: {}, 工具ID: {}, 结果长度: {} 字符",
                        userId, result.toolId(), toolResultJson.length());
            }

            // 构建新的请求
            Map<String, Object> summaryRequest = new HashMap<>();
            summaryRequest.put("model", aiConfig.getModel());
            summaryRequest.put("messages", messages);
            summaryRequest.put("temperature", aiConfig.getTemperature());
            summaryRequest.put("max_tokens", aiConfig.getMaxTokens());
            summaryRequest.put("top_p", aiConfig.getTopP());
            summaryRequest.put("stream", false);

            // 允许AI继续调用工具（如果递归深度允许）
            if (recursionDepth < (aiConfig.getMaxToolCalls() - 1)) {
                try {
                    List<Map<String, Object>> availableTools = mcpToolRegistry.getAllToolsForAI();
                    if (availableTools != null && !availableTools.isEmpty()) {
                        summaryRequest.put("tools", availableTools);
                        summaryRequest.put("tool_choice", "auto");
                    }
                } catch (Exception e) {
                    log.warn("获取可用工具失败，强制AI给出最终答案", e);
                }
            }

            log.info("发送工具结果给AI进行总结 - 用户ID: {}, 消息数量: {}, 递归深度: {}",
                    userId, messages.size(), recursionDepth);

            return processEnhancedAIConversation(userId, summaryRequest, context, recursionDepth);

        } catch (Exception e) {
            log.error("发送MCP工具结果给AI失败 - 用户ID: {}", userId, e);
            return new AIResponse(false, "工具结果处理失败: " + e.getMessage(), toolResults, previousUsage);
        }
    }


    /**
     * 改进版：转换工具结果为JSON字符串 - 支持大数据检测
     */
    @SuppressWarnings("null")
    private String convertToolResultToJson(Object result) {
        try {
            if (result == null) {
                return "null";
            }

            String jsonString;
            if (result instanceof String) {
                String str = (String) result;
                if (str.trim().startsWith("{") || str.trim().startsWith("[")) {
                    jsonString = str;
                } else {
                    jsonString = objectMapper.writeValueAsString(Map.of("result", str));
                }
            } else {
                jsonString = objectMapper.writeValueAsString(result);
            }

            // 检测JSON大小
            int jsonSize = jsonString.getBytes("UTF-8").length;
            if (jsonSize > LARGE_DATA_THRESHOLD_MB * 1024 * 1024) {
                log.warn("转换的JSON结果超过{}MB: {} 字节", LARGE_DATA_THRESHOLD_MB, jsonSize);
            }

            return jsonString;

        } catch (Exception e) {
            log.warn("转换工具结果为JSON失败，使用字符串形式: {}", e.getMessage());
            return result.toString();
        }
    }

    /**
     * 增强版：构建消息列表 - 包含完整的MCP工具介绍
     */
    private List<Map<String, Object>> buildEnhancedMessages(String userQuery, Map<String, Object> context, Long userId) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // 1. 核心系统消息 - 使用增强版的完整提示词
        Map<String, Object> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");

        // 根据上下文获取适当的系统提示词
        String scenarioContext = (String) context.get("scenarioContext");
        String contextualPrompt = scenarioContext != null ?
                aiConfig.getContextualPrompt(scenarioContext) :
                aiConfig.getFullSystemPrompt();

        systemMessage.put("content", contextualPrompt);
        messages.add(systemMessage);

        // 2. MCP工具集成状态消息
        Map<String, Object> toolIntegrationMessage = new HashMap<>();
        toolIntegrationMessage.put("role", "system");
        toolIntegrationMessage.put("content", buildMCPToolsStatusMessage(context));
        messages.add(toolIntegrationMessage);

        // 3. 用户上下文信息消息
        if (context != null && !context.isEmpty()) {
            Map<String, Object> contextMessage = new HashMap<>();
            contextMessage.put("role", "system");
            contextMessage.put("content", "当前用户上下文信息：\n" + formatEnhancedContextForAI(context, userId));
            messages.add(contextMessage);
        }

        // 4. 工具使用指导消息 - 增强版
        Map<String, Object> toolGuidanceMessage = new HashMap<>();
        toolGuidanceMessage.put("role", "system");
        toolGuidanceMessage.put("content", buildEnhancedToolGuidance(userQuery, context));
        messages.add(toolGuidanceMessage);

        // 5. 用户查询消息
        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", userQuery);
        messages.add(userMessage);

        log.debug("构建增强版消息列表完成 - 用户ID: {}, 消息数量: {}", userId, messages.size());
        return messages;
    }

    /**
     * 构建MCP工具状态消息
     */
    private String buildMCPToolsStatusMessage(Map<String, Object> context) {
        StringBuilder statusMessage = new StringBuilder();
        statusMessage.append("【MCP工具集成状态报告】\n\n");

        // 检查工具集成状态
        Boolean mcpToolsReady = (Boolean) context.get("mcpToolsReady");
        if (Boolean.TRUE.equals(mcpToolsReady)) {
            statusMessage.append("✅ MCP工具集成状态: 完全集成并可用\n");
            statusMessage.append("📊 工具集成版本: v3.2-Enhanced-Full-Integration\n");

            // 添加工具分类信息
            @SuppressWarnings("unchecked")
            Map<String, Object> toolsIntegration = (Map<String, Object>) context.get("mcpToolsIntegrated");
            if (toolsIntegration != null) {
                Integer totalTools = (Integer) toolsIntegration.get("totalToolsCount");
                if (totalTools != null) {
                    statusMessage.append("🔧 可用工具总数: ").append(totalTools).append(" 个\n");
                }

                @SuppressWarnings("unchecked")
                Map<String, List<String>> categories = (Map<String, List<String>>) toolsIntegration.get("toolCategories");
                if (categories != null) {
                    statusMessage.append("\n📋 工具分类详情:\n");
                    categories.forEach((category, tools) -> {
                        statusMessage.append("• ").append(category).append(": ").append(tools.size()).append(" 个工具\n");
                    });
                }
            }

            statusMessage.append("\n✨ 所有14个核心EEG分析工具已准备就绪，可以根据用户需求智能选择和协作使用。");
        } else {
            statusMessage.append("⚠️ MCP工具集成状态: 部分可用或不可用\n");
            statusMessage.append("🔧 当前将在有限工具模式下运行\n");
        }

        return statusMessage.toString();
    }

    /**
     * 增强版：为AI格式化上下文信息
     */
    private String formatEnhancedContextForAI(Map<String, Object> context, Long userId) {
        StringBuilder contextStr = new StringBuilder();
        contextStr.append("用户ID: ").append(userId).append("\n");
        contextStr.append("当前时间: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");

        // 处理会话上下文
        if (context.containsKey("hasActiveSession")) {
            boolean hasActive = (Boolean) context.get("hasActiveSession");
            contextStr.append("活跃会话状态: ").append(hasActive ? "有正在进行的会话" : "无活跃会话").append("\n");

            if (hasActive && context.containsKey("activeSession")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> activeSession = (Map<String, Object>) context.get("activeSession");
                contextStr.append("活跃会话详情: ID=").append(activeSession.get("id"))
                        .append(", 持续时间=").append(activeSession.get("realTimeDuration")).append("秒\n");
            }
        }

        if (context.containsKey("recentSessionsCount")) {
            Integer count = (Integer) context.get("recentSessionsCount");
            contextStr.append("近期会话数量: ").append(count).append("\n");
        }

        // 处理用户统计信息
        if (context.containsKey("userStats")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> stats = (Map<String, Object>) context.get("userStats");
            contextStr.append("用户统计: 总会话数 ").append(stats.get("totalSessions"))
                    .append(", 已完成 ").append(stats.get("completedSessions"))
                    .append(", 数据质量 ").append(stats.get("dataQuality")).append("\n");
        }

        // 修复：处理查询复杂度分析 - 正确处理对象类型
        if (context.containsKey("queryComplexityAnalysis")) {
            Object complexityObj = context.get("queryComplexityAnalysis");
            if (complexityObj instanceof QueryComplexityAnalysis) {
                QueryComplexityAnalysis complexity =
                        (QueryComplexityAnalysis) complexityObj;
                contextStr.append("查询复杂度: ").append(complexity.getLevel())
                        .append(" (").append(complexity.getDescription()).append(")\n");
            }
        }

        // 修复：处理协作策略 - 正确处理对象类型
        if (context.containsKey("collaborationStrategy")) {
            Object strategyObj = context.get("collaborationStrategy");
            if (strategyObj instanceof CollaborationStrategy) {
                CollaborationStrategy strategy =
                        (CollaborationStrategy) strategyObj;
                contextStr.append("建议协作策略: ").append(strategy.getType())
                        .append(", 预期工具数: ").append(strategy.getExpectedToolCount()).append("\n");
            }
        }

        // 处理对话会话ID
        if (context.containsKey("conversationSessionId")) {
            contextStr.append("对话会话ID: ").append(context.get("conversationSessionId")).append("\n");
        }

        // 添加其他重要上下文，但避免过于详细
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            String key = entry.getKey();
            if (!Set.of("hasActiveSession", "activeSession", "recentSessionsCount", "userStats",
                    "queryComplexityAnalysis", "collaborationStrategy", "conversationSessionId",
                    "userId", "currentTime", "mcpToolsReady", "mcpToolsIntegrated").contains(key)) {
                Object value = entry.getValue();
                if (value != null && !(value instanceof Map) && !(value instanceof List)) {
                    contextStr.append(key).append(": ").append(value).append("\n");
                }
            }
        }

        return contextStr.toString();
    }
    /**
     * 增强版：构建工具使用指导
     */
            private String buildEnhancedToolGuidance(String userQuery, Map<String, Object> context) {
        StringBuilder guidance = new StringBuilder();
        guidance.append("【MCP工具智能协作系统指令】\\n\\n");

        if (context.containsKey("toolSelectionGuidance")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> contextGuidance = (Map<String, Object>) context.get("toolSelectionGuidance");
            
            if (contextGuidance.containsKey("strategyExplanation")) {
                guidance.append("🎯 协作策略定位: ").append(contextGuidance.get("strategyExplanation")).append("\\n");
            }
            
            if (contextGuidance.containsKey("recommendedTools")) {
                @SuppressWarnings("unchecked")
                List<String> tools = (List<String>) contextGuidance.get("recommendedTools");
                guidance.append("推荐优先考虑以下工具链以形成最佳上下文: \\n");
                for (String tool : tools) {
                    guidance.append("- ").append(tool).append("\\n");
                }
            }
        } else {
            guidance.append("请根据用户请求语义，动态分析并自行决定使用哪些MCP工具。\\n");
        }

        guidance.append("\\n⚠️ 注意事项:\\n");
        guidance.append("- 你是主动的思考者，请务必先思考需要哪些补充信息，再精确调用对应的MCP工具。\\n");
        guidance.append("- 可以串行或并行调用多个工具，直到获得充足上下文后再回答。\\n");
        guidance.append("- 如果现有工具无法提供直接答案，请使用 executeCustomQuery 构建InxluxQL。\\n");

        return guidance.toString();
    }

    /**
     * 增强版：构建聊天完成请求
     */
    private Map<String, Object> buildEnhancedChatCompletionRequest(List<Map<String, Object>> messages,
                                                                   List<Map<String, Object>> tools) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", aiConfig.getModel());
        request.put("messages", messages);
        request.put("temperature", aiConfig.getTemperature());
        request.put("max_tokens", aiConfig.getMaxTokens());
        request.put("top_p", aiConfig.getTopP());
        request.put("stream", false);

        // 添加工具（如果启用MCP工具且有可用工具）
        if (aiConfig.isEnableMcpTools() && tools != null && !tools.isEmpty()) {
            request.put("tools", tools);
            request.put("tool_choice", "auto"); // 让AI自动决定是否使用工具
            log.debug("已启用MCP工具集成 - 可用工具数: {}", tools.size());
        } else {
            log.debug("MCP工具未启用或无可用工具");
        }

        return request;
    }

    // 在数据量大超过AI大模型6MB限制
    /**
     * 【核心新增方法】：智能数据处理 - 自动处理大数据量
     */
    private List<ToolCallResult> intelligentDataProcessing(Long userId, List<ToolCallResult> originalResults,
                                                           Map<String, Object> context) {
        List<ToolCallResult> processedResults = new ArrayList<>();

        for (ToolCallResult result : originalResults) {
            try {
                // 检测数据大小
                String resultJson = convertToolResultToJson(result.result());
                int dataSize = resultJson.getBytes("UTF-8").length;

                log.info("检测工具结果数据大小 - 工具: {}, 大小: {} 字节", result.functionName(), dataSize);

                // 定义大数据阈值：4MB (考虑到API限制是6MB，留出安全边界)
                int largeDateThreshold = 4 * 1024 * 1024; // 4MB

                if (dataSize > largeDateThreshold) {
                    log.warn("检测到大数据量工具结果 - 工具: {}, 大小: {} MB, 启动智能摘要处理",
                            result.functionName(), dataSize / (1024.0 * 1024.0));

                    // 使用摘要工具处理大数据
                    Object summarizedResult = processLargeDataWithSummary(userId, result, context);
                    processedResults.add(new ToolCallResult(result.toolId(), result.functionName(),
                            result.arguments(), summarizedResult));
                } else {
                    // 小数据量直接使用原始结果
                    processedResults.add(result);
                }

            } catch (Exception e) {
                log.error("处理工具结果时出错 - 工具: {}", result.functionName(), e);
                // 出错时使用原始结果，但添加错误标记
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("processingError", "数据处理时出错: " + e.getMessage());
                errorResult.put("originalDataAvailable", false);
                errorResult.put("suggestion", "请尝试使用更小的数据范围重新查询");

                processedResults.add(new ToolCallResult(result.toolId(), result.functionName(),
                        result.arguments(), errorResult));
            }
        }

        return processedResults;
    }

    /**
     * 使用摘要工具处理大数据
     */
    private Object processLargeDataWithSummary(Long userId, ToolCallResult largeDataResult,
                                               Map<String, Object> context) {
        try {
            String toolName = largeDataResult.functionName();
            Object originalResult = largeDataResult.result();

            log.info("开始处理大数据工具结果 - 工具: {}, 用户ID: {}", toolName, userId);

            // 根据工具类型选择合适的摘要策略
            if (toolName.contains("Raw") || toolName.contains("Filtered") || toolName.contains("EEG")) {
                return summarizeEEGTimeSeriesData(originalResult, largeDataResult.arguments());
            } else if (toolName.contains("Band") || toolName.contains("Power")) {
                return summarizeBandPowerData(originalResult, largeDataResult.arguments());
            } else {
                // 通用摘要处理
                return createGenericDataSummary(originalResult, toolName);
            }

        } catch (Exception e) {
            log.error("大数据摘要处理失败", e);

            // 返回失败摘要，包含关键信息
            return Map.of(
                    "dataSummaryError", "无法处理大数据量结果",
                    "errorDetails", e.getMessage(),
                    "recommendation", "请尝试减小查询范围，比如减少时间窗口或通道数量",
                    "toolName", largeDataResult.functionName(),
                    "originalDataSize", "超过4MB"
            );
        }
    }
    /**
     * EEG时间序列数据摘要
     */
    private Map<String, Object> summarizeEEGTimeSeriesData(Object originalData, Map<String, Object> arguments) {
        Map<String, Object> summary = new HashMap<>();

        try {
            if (originalData instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = (Map<String, Object>) originalData;

                summary.put("dataType", "EEG_TimeSeries_Summary");
                summary.put("originalQueryParams", arguments);
                summary.put("summaryGeneratedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

                // 提取关键统计信息
                if (dataMap.containsKey("retrievedSampleCount")) {
                    summary.put("totalSamples", dataMap.get("retrievedSampleCount"));
                }

                if (dataMap.containsKey("timeSeriesAnalysis")) {
                    summary.put("statisticalAnalysis", dataMap.get("timeSeriesAnalysis"));
                }

                // 提取数据样本
                if (dataMap.containsKey("dataSample")) {
                    summary.put("dataSample", dataMap.get("dataSample"));
                }

                // 提取查询透明度信息
                if (dataMap.containsKey("queryTransparency")) {
                    summary.put("queryInfo", dataMap.get("queryTransparency"));
                }

                // 生成关键指标摘要
                summary.put("keySummary", Map.of(
                        "status", "大数据量已智能摘要处理",
                        "dataReduction", "原始数据已压缩为关键统计指标",
                        "availableInfo", "样本统计、时间序列分析、数据质量指标",
                        "recommendation", "如需详细数据，请缩小查询范围"
                ));

            } else {
                summary.put("error", "无法解析原始数据结构");
            }

        } catch (Exception e) {
            summary.put("summaryError", "EEG数据摘要生成失败: " + e.getMessage());
        }

        return summary;
    }

    /**
     * 频段功率数据摘要
     */
    private Map<String, Object> summarizeBandPowerData(Object originalData, Map<String, Object> arguments) {
        Map<String, Object> summary = new HashMap<>();

        try {
            if (originalData instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = (Map<String, Object>) originalData;

                summary.put("dataType", "BandPower_Summary");
                summary.put("originalQueryParams", arguments);

                // 保留关键分析结果
                if (dataMap.containsKey("statisticalAnalysis")) {
                    summary.put("frequencyBandAnalysis", dataMap.get("statisticalAnalysis"));
                }

                if (dataMap.containsKey("organizedByTimePoint")) {
                    // 只保留前几个时间点作为样本
                    @SuppressWarnings("unchecked")
                    List<Object> timePoints = (List<Object>) dataMap.get("organizedByTimePoint");
                    if (timePoints != null && !timePoints.isEmpty()) {
                        summary.put("timePointSample", timePoints.stream()
                                .limit(5)  // 只保留前5个时间点
                                .collect(Collectors.toList()));
                        summary.put("totalTimePoints", timePoints.size());
                    }
                }

                // 保留数据完整性信息
                if (dataMap.containsKey("dataCompletenessRatio")) {
                    summary.put("dataCompleteness", dataMap.get("dataCompletenessRatio"));
                }

                summary.put("summaryNote", "大数据量已压缩为关键频段分析和代表性时间点样本");
            }

        } catch (Exception e) {
            summary.put("summaryError", "频段数据摘要生成失败: " + e.getMessage());
        }

        return summary;
    }

    /**
     * 通用数据摘要
     */
    private Map<String, Object> createGenericDataSummary(Object originalData, String toolName) {
        Map<String, Object> summary = new HashMap<>();

        summary.put("dataType", "Generic_Large_Data_Summary");
        summary.put("toolName", toolName);
        summary.put("status", "数据量过大，已生成摘要");
        summary.put("recommendation", "请使用更具体的查询参数来获取详细数据");
        summary.put("summaryGeneratedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        try {
            // 尝试提取基本信息
            if (originalData instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = (Map<String, Object>) originalData;

                // 保留基本元数据
                for (String key : Arrays.asList("success", "dataType", "totalRecords", "timestamp", "error")) {
                    if (dataMap.containsKey(key)) {
                        summary.put(key, dataMap.get(key));
                    }
                }
            }

        } catch (Exception e) {
            summary.put("summaryError", "通用摘要生成失败: " + e.getMessage());
        }

        return summary;
    }


    // ========== 流式输出方法 ==========

    /**
     * 流式处理用户查询 - 返回 SSE 事件流，实现真正的逐字推送
     * 事件类型：thinking（思考状态）、chunk（文本片段）、done（完成）
     */
    public Flux<ServerSentEvent<String>> processUserQueryStream(Long userId, String userQuery, Map<String, Object> context) {
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicLong streamStartTime = new AtomicLong(System.currentTimeMillis());

        CompletableFuture.runAsync(() -> {
            try {
                log.info("[流式] 开始处理用户查询 - 用户ID: {}, 查询: {}", userId, userQuery);

                // 发送思考开始事件
                emitSSE(sink, "thinking", Map.of("status", "started"));

                // 构建消息和工具列表
                List<Map<String, Object>> messages = buildEnhancedMessages(userQuery, context, userId);
                List<Map<String, Object>> availableTools = mcpToolRegistry.getAllToolsForAI();

                // 构建请求体
                Map<String, Object> requestBody = buildEnhancedChatCompletionRequest(messages, availableTools);
                // 强制开启流式
                requestBody.put("stream", true);

                // 执行流式对话（支持工具调用递归）
                processStreamConversation(userId, requestBody, context, 0, sink, streamStartTime);

            } catch (Exception e) {
                log.error("[流式] 处理用户查询时出错 - 用户ID: {}", userId, e);
                emitSSE(sink, "error", Map.of("message", "处理查询时出错: " + e.getMessage()));
                sink.tryEmitComplete();
            }
        });

        return sink.asFlux();
    }

    /**
     * 流式对话处理核心 - 发送请求到AI并实时解析SSE响应流
     * 支持流式文本输出和流式工具调用聚合
     */
    @SuppressWarnings("null")
    private void processStreamConversation(Long userId, Map<String, Object> requestBody,
                                           Map<String, Object> context, int recursionDepth,
                                           Sinks.Many<ServerSentEvent<String>> sink,
                                           AtomicLong streamStartTime) {
        if (recursionDepth >= aiConfig.getMaxToolCalls()) {
            emitSSE(sink, "error", Map.of("message", "已达到最大工具调用次数限制"));
            emitDone(sink, streamStartTime);
            return;
        }

        int maxRetries = aiConfig.getMaxRetries();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                int timeoutSeconds = aiConfig.getTimeout() + (attempt - 1) * 30;

                log.info("[流式] AI请求尝试 {}/{} - 递归深度: {} - 用户ID: {}", attempt, maxRetries, recursionDepth, userId);

                // 使用 bodyToFlux 获取真正的流式响应
                Flux<String> responseStream = webClient.post()
                        .uri("/chat/completions")
                        .bodyValue(requestBody)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .retrieve()
                        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                clientResponse -> clientResponse.bodyToMono(String.class)
                                        .defaultIfEmpty("Unknown API error")
                                        .flatMap(errorBody -> {
                                            log.error("[流式] AI API调用错误 - 状态码: {}, 响应: {}",
                                                    clientResponse.statusCode(), errorBody);
                                            return Mono.error(new RuntimeException("API调用失败: " + errorBody));
                                        }))
                        .bodyToFlux(String.class)
                        .timeout(java.time.Duration.ofSeconds(timeoutSeconds));

                // 状态变量：用于聚合流式工具调用
                StringBuilder contentBuffer = new StringBuilder();
                List<Map<String, Object>> toolCallsBuffer = new ArrayList<>();
                boolean[] thinkingDone = {false};
                long apiCallStart = System.currentTimeMillis();

                // 阻塞消费流（在异步线程中安全）
                responseStream.toStream().forEach(line -> {
                    // 跳过空行和非data行
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) return;

                    // 处理 SSE 格式：去掉 "data: " 前缀
                    String jsonStr = trimmed;
                    if (trimmed.startsWith("data: ")) {
                        jsonStr = trimmed.substring(6).trim();
                    } else if (trimmed.startsWith("data:")) {
                        jsonStr = trimmed.substring(5).trim();
                    }

                    // 检查流结束标志
                    if ("[DONE]".equals(jsonStr)) {
                        return;
                    }

                    try {
                        JsonNode chunk = objectMapper.readTree(jsonStr);
                        JsonNode choices = chunk.get("choices");
                        if (choices == null || choices.isEmpty()) return;

                        JsonNode delta = choices.get(0).get("delta");
                        if (delta == null) return;

                        // 首次收到delta时，发送思考完成事件
                        if (!thinkingDone[0]) {
                            thinkingDone[0] = true;
                            double duration = (System.currentTimeMillis() - apiCallStart) / 1000.0;
                            emitSSE(sink, "thinking", Map.of("status", "done", "duration", duration));
                        }

                        // 处理文本内容
                        if (delta.has("content") && !delta.get("content").isNull()) {
                            String text = delta.get("content").asText();
                            if (!text.isEmpty()) {
                                contentBuffer.append(text);
                                emitSSE(sink, "chunk", Map.of("text", text));
                            }
                        }

                        // 处理流式工具调用（聚合分片的工具参数）
                        if (delta.has("tool_calls")) {
                            for (JsonNode tc : delta.get("tool_calls")) {
                                int index = tc.has("index") ? tc.get("index").asInt() : 0;

                                // 扩展buffer到足够大小
                                while (toolCallsBuffer.size() <= index) {
                                    Map<String, Object> emptyTool = new HashMap<>();
                                    emptyTool.put("id", "");
                                    emptyTool.put("name", "");
                                    emptyTool.put("arguments", "");
                                    toolCallsBuffer.add(emptyTool);
                                }

                                Map<String, Object> toolEntry = toolCallsBuffer.get(index);

                                // 聚合工具ID
                                if (tc.has("id") && !tc.get("id").isNull()) {
                                    toolEntry.put("id", tc.get("id").asText());
                                }
                                // 聚合函数名称
                                if (tc.has("function")) {
                                    JsonNode fn = tc.get("function");
                                    if (fn.has("name") && !fn.get("name").isNull()) {
                                        toolEntry.put("name", fn.get("name").asText());
                                    }
                                    if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                                        // 流式参数拼接
                                        toolEntry.put("arguments",
                                                toolEntry.get("arguments").toString() + fn.get("arguments").asText());
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[流式] 解析数据行时跳过: {}", e.getMessage());
                    }
                });

                // 流结束后：判断是否需要执行工具调用
                if (!toolCallsBuffer.isEmpty()) {
                    log.info("[流式] 检测到 {} 个工具调用，开始执行 - 用户ID: {}", toolCallsBuffer.size(), userId);
                    processStreamToolCalls(userId, toolCallsBuffer, requestBody, context, recursionDepth, sink, streamStartTime);
                } else {
                    // 普通文本回答，直接完成
                    emitDone(sink, streamStartTime);
                }
                return; // 成功，退出重试循环

            } catch (Exception e) {
                log.error("[流式] AI请求异常 - 用户ID: {}, 尝试 {}/{}: {}", userId, attempt, maxRetries, e.getMessage());
                if (attempt == maxRetries) {
                    emitSSE(sink, "error", Map.of("message", "AI服务请求失败: " + e.getMessage()));
                    emitDone(sink, streamStartTime);
                }
                // 重试延迟
                try { Thread.sleep(2000L * (long) Math.pow(2, attempt - 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    /**
     * 处理流式工具调用 - 执行工具后递归继续流式对话
     */
    private void processStreamToolCalls(Long userId, List<Map<String, Object>> toolCallsBuffer,
                                        Map<String, Object> originalRequest, Map<String, Object> context,
                                        int recursionDepth, Sinks.Many<ServerSentEvent<String>> sink,
                                        AtomicLong streamStartTime) {
        try {
            List<ToolCallResult> toolResults = new ArrayList<>();

            // 执行每个工具调用
            for (Map<String, Object> tc : toolCallsBuffer) {
                String toolId = tc.get("id").toString();
                String functionName = tc.get("name").toString();
                String argumentsStr = tc.get("arguments").toString();

                log.info("[流式] 执行工具: {} - 用户ID: {}", functionName, userId);
                emitSSE(sink, "thinking", Map.of("status", "started", "message", "正在执行工具: " + functionName));

                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> arguments = objectMapper.readValue(argumentsStr, Map.class);
                    Object toolResult = mcpToolRegistry.executeTool(userId, functionName, arguments, context);
                    toolResults.add(new ToolCallResult(toolId, functionName, arguments, toolResult));
                    log.info("[流式] 工具 {} 执行成功", functionName);
                } catch (Exception e) {
                    log.error("[流式] 工具 {} 执行失败: {}", functionName, e.getMessage());
                    toolResults.add(new ToolCallResult(toolId, functionName, Map.of(), Map.of("error", e.getMessage())));
                }
            }

            // 智能数据处理（压缩大数据）
            List<ToolCallResult> processedResults = intelligentDataProcessing(userId, toolResults, context);

            // 将工具结果追加到消息列表中
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) originalRequest.get("messages");

            // 添加 assistant 的工具调用消息
            Map<String, Object> assistantMessage = new HashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", null);
            List<Map<String, Object>> toolCallsForMsg = new ArrayList<>();
            for (ToolCallResult r : toolResults) {
                Map<String, Object> tcMsg = new HashMap<>();
                tcMsg.put("id", r.toolId());
                tcMsg.put("type", "function");
                Map<String, Object> fn = new HashMap<>();
                fn.put("name", r.functionName());
                fn.put("arguments", objectMapper.writeValueAsString(r.arguments()));
                tcMsg.put("function", fn);
                toolCallsForMsg.add(tcMsg);
            }
            assistantMessage.put("tool_calls", toolCallsForMsg);
            messages.add(assistantMessage);

            // 添加 tool 角色的结果消息
            for (ToolCallResult r : processedResults) {
                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", r.toolId());
                toolMsg.put("content", convertToolResultToJson(r.result()));
                messages.add(toolMsg);
            }

            // 构建新请求并递归继续流式对话
            Map<String, Object> nextRequest = new HashMap<>();
            nextRequest.put("model", aiConfig.getModel());
            nextRequest.put("messages", messages);
            nextRequest.put("temperature", aiConfig.getTemperature());
            nextRequest.put("max_tokens", aiConfig.getMaxTokens());
            nextRequest.put("top_p", aiConfig.getTopP());
            nextRequest.put("stream", true);

            // 如果递归深度允许，继续提供工具
            if (recursionDepth + 1 < aiConfig.getMaxToolCalls() - 1) {
                List<Map<String, Object>> tools = mcpToolRegistry.getAllToolsForAI();
                if (tools != null && !tools.isEmpty()) {
                    nextRequest.put("tools", tools);
                    nextRequest.put("tool_choice", "auto");
                }
            }

            emitSSE(sink, "thinking", Map.of("status", "done", "duration", 0));

            // 递归继续流式对话
            processStreamConversation(userId, nextRequest, context, recursionDepth + 1, sink, streamStartTime);

        } catch (Exception e) {
            log.error("[流式] 处理工具调用失败 - 用户ID: {}: {}", userId, e.getMessage());
            emitSSE(sink, "error", Map.of("message", "工具调用处理失败: " + e.getMessage()));
            emitDone(sink, streamStartTime);
        }
    }

    /**
     * 发送一个 SSE 事件到前端
     */
    private void emitSSE(Sinks.Many<ServerSentEvent<String>> sink, String eventType, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            sink.tryEmitNext(ServerSentEvent.<String>builder()
                    .event(eventType)
                    .data(json)
                    .build());
        } catch (Exception e) {
            log.warn("[流式] 发送SSE事件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送完成事件并关闭流
     */
    private void emitDone(Sinks.Many<ServerSentEvent<String>> sink, AtomicLong streamStartTime) {
        double totalTime = (System.currentTimeMillis() - streamStartTime.get()) / 1000.0;
        emitSSE(sink, "done", Map.of("totalTime", totalTime));
        sink.tryEmitComplete();
    }

    // ========== 数据类定义 ==========

    /**
     * AI响应数据类
     */
    public record AIResponse(
            boolean success,
            String content,
            List<ToolCallResult> toolResults,
            Object usage
    ) {}

    /**
     * 工具调用结果数据类
     */
    public record ToolCallResult(
            String toolId,
            String functionName,
            Map<String, Object> arguments,
            Object result
    ) {}
}