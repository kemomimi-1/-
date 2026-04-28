/**
 * AI流式输出服务 - 支持SSE流式返回AI回复
 * 独立于AIModelService，不影响现有非流式功能
 */
package com.eeg.service;

import com.eeg.config.AIModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class AIStreamService {

    private final AIModelConfig aiConfig;
    private final MCPToolRegistry mcpToolRegistry;
    private final ObjectMapper objectMapper;
    private final ConversationHistoryService conversationHistoryService;

    public AIStreamService(AIModelConfig aiConfig,
                           MCPToolRegistry mcpToolRegistry,
                           ObjectMapper objectMapper,
                           ConversationHistoryService conversationHistoryService) {
        this.aiConfig = aiConfig;
        this.mcpToolRegistry = mcpToolRegistry;
        this.objectMapper = objectMapper;
        this.conversationHistoryService = conversationHistoryService;
        log.info("✅ AIStreamService 初始化完成 - 流式输出功能就绪");
    }

    /**
     * 【核心方法】处理流式查询
     * @param userId         用户ID
     * @param userQuery      用户查询内容
     * @param context        增强上下文（由Controller构建）
     * @param sessionId      对话会话ID
     * @return               返回WebFlux标准的Flux流
     */
    public Flux<ServerSentEvent<String>> processStreamQuery(Long userId, String userQuery,
                                                            Map<String, Object> context,
                                                            String sessionId) {
        return Flux.create(sink -> {
            // 在后台线程执行，避免阻塞Netty的EventLoop
            Thread runner = new Thread(() -> {
                long startTime = System.currentTimeMillis();
                try {
                    // 1. 发送"思考中"状态，让用户知道系统在工作
                    sendEvent(sink, "thinking", "🤔 正在分析您的问题...");

                    // 2. 发送会话ID给前端
                    sendEvent(sink, "session", sessionId);

                    // 3. 构建消息列表（复用AIModelConfig的系统提示词）
                    List<Map<String, Object>> messages = buildMessages(userQuery, context, userId);

                    // 4. 获取MCP工具列表
                    List<Map<String, Object>> tools = Collections.emptyList();
                    if (aiConfig.isEnableMcpTools()) {
                        tools = mcpToolRegistry.getAllToolsForAI();
                        sendEvent(sink, "thinking", "📋 已加载 " + tools.size() + " 个智能分析工具");
                    }

                    // 5. 构建请求体（stream=true）
                    Map<String, Object> requestBody = buildStreamRequest(messages, tools);

                    // 6. 执行流式对话（支持多轮工具调用递归）
                    StreamResult result = executeStreamConversation(userId, requestBody, context, sink, 0);

                    // 7. 发送完成信号
                    sendEvent(sink, "done", "");

                    // 8. 保存对话记录
                    long duration = System.currentTimeMillis() - startTime;
                    saveConversation(sessionId, userId, userQuery, result.fullContent,
                            result.toolsUsed, duration);

                    sink.complete();
                    log.info("流式查询完成 - 用户ID: {}, 耗时: {}ms", userId, duration);

                } catch (Exception e) {
                    log.error("流式查询处理失败 - 用户ID: {}", userId, e);
                    try {
                        sendEvent(sink, "error", "处理失败: " + e.getMessage());
                        sink.complete();
                    } catch (Exception ex) {
                        sink.error(e);
                    }
                }
            }, "ai-stream-" + userId + "-" + System.currentTimeMillis());
            runner.start();
        });
    }

    /**
     * 执行流式AI对话 - 支持工具调用递归
     * 返回StreamResult，包含完整文本和使用的工具列表
     */
    private StreamResult executeStreamConversation(Long userId,
                                                   Map<String, Object> requestBody,
                                                   Map<String, Object> context,
                                                   FluxSink<ServerSentEvent<String>> sink,
                                                   int recursionDepth) throws Exception {
        if (recursionDepth >= aiConfig.getMaxToolCalls()) {
            sendEvent(sink, "thinking", "⚠️ 已达到最大工具调用次数限制");
            return new StreamResult("", Collections.emptyList());
        }

        String requestJson = objectMapper.writeValueAsString(requestBody);

        // 使用HttpURLConnection进行流式HTTP请求（简洁可靠）
        URL url = URI.create(aiConfig.getBaseUrl() + "/chat/completions").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + aiConfig.getApiKey());
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setDoOutput(true);
        conn.setConnectTimeout(aiConfig.getConnectionTimeout() * 1000);
        conn.setReadTimeout(aiConfig.getReadTimeout() * 1000);

        // 写入请求体
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestJson.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String errorBody = readStream(conn.getErrorStream());
            log.error("AI API返回错误 - 状态码: {}, 响应: {}", responseCode, errorBody);
            sendEvent(sink, "error", "AI服务错误 (HTTP " + responseCode + ")");
            return new StreamResult("", Collections.emptyList());
        }

        // 读取流式响应并实时推送给前端
        StringBuilder fullContent = new StringBuilder();
        List<ToolCallAccumulator> toolCallAccumulators = new ArrayList<>();
        String finishReason = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!line.startsWith("data:")) continue;

                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }

                try {
                    JsonNode chunk = objectMapper.readTree(data);
                    JsonNode choices = chunk.get("choices");
                    if (choices == null || choices.isEmpty()) continue;

                    JsonNode firstChoice = choices.get(0);
                    JsonNode delta = firstChoice.get("delta");
                    if (delta == null) continue;

                    // 检查 finish_reason
                    if (firstChoice.has("finish_reason") && !firstChoice.get("finish_reason").isNull()) {
                        finishReason = firstChoice.get("finish_reason").asText();
                    }

                    // ===== 处理文本内容增量 =====
                    if (delta.has("content") && !delta.get("content").isNull()) {
                        String contentDelta = delta.get("content").asText();
                        if (!contentDelta.isEmpty()) {
                            fullContent.append(contentDelta);
                            // 实时推送内容片段给前端
                            sendEvent(sink, "content", contentDelta);
                        }
                    }

                    // ===== 处理工具调用增量（流式中tool_calls是分片到达的）=====
                    if (delta.has("tool_calls")) {
                        JsonNode toolCallsNode = delta.get("tool_calls");
                        for (JsonNode tc : toolCallsNode) {
                            int index = tc.get("index").asInt();

                            // 确保有足够的accumulator
                            while (toolCallAccumulators.size() <= index) {
                                toolCallAccumulators.add(new ToolCallAccumulator());
                            }

                            ToolCallAccumulator acc = toolCallAccumulators.get(index);

                            if (tc.has("id") && !tc.get("id").isNull()) {
                                acc.id = tc.get("id").asText();
                            }
                            if (tc.has("function")) {
                                JsonNode fn = tc.get("function");
                                if (fn.has("name") && !fn.get("name").isNull()) {
                                    acc.functionName = fn.get("name").asText();
                                }
                                if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                                    acc.arguments.append(fn.get("arguments").asText());
                                }
                            }
                        }
                    }

                } catch (Exception e) {
                    log.warn("解析流式数据块失败: {}", data, e);
                }
            }
        } finally {
            conn.disconnect();
        }

        // ===== 检查是否有工具调用需要执行 =====
        if (!toolCallAccumulators.isEmpty() && "tool_calls".equals(finishReason)) {
            return handleToolCalls(userId, requestBody, context, sink,
                    toolCallAccumulators, fullContent.toString(), recursionDepth);
        }

        // 纯文本回复，已通过逐chunk推送完成
        return new StreamResult(fullContent.toString(), Collections.emptyList());
    }

    /**
     * 处理工具调用：执行工具 → 将结果发回AI → 继续流式输出
     */
    private StreamResult handleToolCalls(Long userId,
                                         Map<String, Object> requestBody,
                                         Map<String, Object> context,
                                         FluxSink<ServerSentEvent<String>> sink,
                                         List<ToolCallAccumulator> toolCallAccumulators,
                                         String previousContent,
                                         int recursionDepth) throws Exception {

        sendEvent(sink, "thinking",
                "🔧 AI 决定调用 " + toolCallAccumulators.size() + " 个工具来获取数据...");

        // 获取当前消息列表
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) requestBody.get("messages");

        // 1. 添加assistant消息（包含tool_calls引用）
        Map<String, Object> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", previousContent.isEmpty() ? null : previousContent);

        List<Map<String, Object>> toolCallsForMsg = new ArrayList<>();
        for (ToolCallAccumulator acc : toolCallAccumulators) {
            Map<String, Object> tc = new HashMap<>();
            tc.put("id", acc.id);
            tc.put("type", "function");
            tc.put("function", Map.of(
                    "name", acc.functionName,
                    "arguments", acc.arguments.toString()
            ));
            toolCallsForMsg.add(tc);
        }
        assistantMsg.put("tool_calls", toolCallsForMsg);
        messages.add(assistantMsg);

        // 2. 逐个执行工具，实时推送执行状态
        List<String> toolsUsedNames = new ArrayList<>();
        for (ToolCallAccumulator acc : toolCallAccumulators) {
            sendEvent(sink, "thinking", "🔧 正在执行: " + acc.functionName + "...");
            toolsUsedNames.add(acc.functionName);

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = objectMapper.readValue(
                        acc.arguments.toString(), Map.class);
                Object result = mcpToolRegistry.executeTool(
                        userId, acc.functionName, args, context);

                String resultJson = objectMapper.writeValueAsString(result);

                // 如果数据量过大（>4MB），自动截断以避免超出API限制
                if (resultJson.length() > 4 * 1024 * 1024) {
                    log.warn("工具结果数据量过大，自动截断 - 工具: {}, 原始大小: {} bytes",
                            acc.functionName, resultJson.length());
                    resultJson = objectMapper.writeValueAsString(Map.of(
                            "summary", "数据量过大，已自动截断为摘要",
                            "originalSize", resultJson.length() + " bytes",
                            "suggestion", "请缩小查询范围以获取详细数据"
                    ));
                }

                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", acc.id);
                toolMsg.put("content", resultJson);
                messages.add(toolMsg);

                sendEvent(sink, "thinking", "✅ " + acc.functionName + " 执行完成");

            } catch (Exception e) {
                log.error("工具调用失败: {}", acc.functionName, e);
                sendEvent(sink, "thinking",
                        "❌ " + acc.functionName + " 执行失败: " + e.getMessage());

                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", acc.id);
                toolMsg.put("content", objectMapper.writeValueAsString(
                        Map.of("error", "工具调用失败: " + e.getMessage())));
                messages.add(toolMsg);
            }
        }

        // 3. 推送工具使用信息给前端
        sendEvent(sink, "tools", objectMapper.writeValueAsString(toolsUsedNames));
        sendEvent(sink, "thinking", "🤖 所有工具执行完毕，正在生成分析回答...");

        // 4. 构建新的流式请求（包含工具结果，让AI总结）
        Map<String, Object> newRequest = new HashMap<>();
        newRequest.put("model", aiConfig.getModel());
        newRequest.put("messages", messages);
        newRequest.put("temperature", aiConfig.getTemperature());
        newRequest.put("max_tokens", aiConfig.getMaxTokens());
        newRequest.put("top_p", aiConfig.getTopP());
        newRequest.put("stream", true); // 继续流式输出

        // 如果未到递归上限，允许AI继续调用工具
        if (recursionDepth < aiConfig.getMaxToolCalls() - 1) {
            List<Map<String, Object>> availableTools = mcpToolRegistry.getAllToolsForAI();
            if (!availableTools.isEmpty()) {
                newRequest.put("tools", availableTools);
                newRequest.put("tool_choice", "auto");
            }
        }

        // 5. 递归执行下一轮流式对话
        StreamResult childResult = executeStreamConversation(
                userId, newRequest, context, sink, recursionDepth + 1);

        // 合并工具列表
        List<String> allTools = new ArrayList<>(toolsUsedNames);
        allTools.addAll(childResult.toolsUsed);
        return new StreamResult(childResult.fullContent, allTools);
    }

    // ==================== 辅助方法 ====================

    /**
     * 发送SSE事件给前端
     */
    private void sendEvent(FluxSink<ServerSentEvent<String>> sink, String type, String content) {
        try {
            Map<String, String> data = new HashMap<>();
            data.put("type", type);
            data.put("content", content);
            
            ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                    .data(objectMapper.writeValueAsString(data))
                    .build();
            sink.next(event);
        } catch (Exception e) {
            log.warn("发送SSE事件失败: type={}, error={}", type, e.getMessage());
        }
    }

    /**
     * 构建流式请求体
     */
    private Map<String, Object> buildStreamRequest(List<Map<String, Object>> messages,
                                                   List<Map<String, Object>> tools) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", aiConfig.getModel());
        request.put("messages", messages);
        request.put("temperature", aiConfig.getTemperature());
        request.put("max_tokens", aiConfig.getMaxTokens());
        request.put("top_p", aiConfig.getTopP());
        request.put("stream", true); // 【关键】启用流式输出

        if (aiConfig.isEnableMcpTools() && tools != null && !tools.isEmpty()) {
            request.put("tools", tools);
            request.put("tool_choice", "auto");
        }

        return request;
    }

    /**
     * 构建消息列表 - 复用AIModelConfig的系统提示词逻辑
     */
    private List<Map<String, Object>> buildMessages(String userQuery,
                                                    Map<String, Object> context,
                                                    Long userId) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // 1. 系统消息（使用场景化系统提示词）
        Map<String, Object> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        String scenarioContext = context != null ? (String) context.get("scenarioContext") : null;
        String systemPrompt = scenarioContext != null ?
                aiConfig.getContextualPrompt(scenarioContext) :
                aiConfig.getFullSystemPrompt();
        systemMessage.put("content", systemPrompt);
        messages.add(systemMessage);

        // 2. 用户上下文信息
        if (context != null && !context.isEmpty()) {
            StringBuilder ctxStr = new StringBuilder();
            ctxStr.append("当前用户上下文信息：\n");
            ctxStr.append("用户ID: ").append(userId).append("\n");
            ctxStr.append("当前时间: ").append(
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");

            // 传递会话信息
            if (context.containsKey("hasActiveSession")) {
                ctxStr.append("活跃会话状态: ").append(
                        Boolean.TRUE.equals(context.get("hasActiveSession")) ?
                                "有正在进行的会话" : "无活跃会话").append("\n");
            }
            if (context.containsKey("recentSessionsCount")) {
                ctxStr.append("近期会话数量: ").append(context.get("recentSessionsCount")).append("\n");
            }

            Map<String, Object> contextMessage = new HashMap<>();
            contextMessage.put("role", "system");
            contextMessage.put("content", ctxStr.toString());
            messages.add(contextMessage);
        }

        // 3. 用户查询
        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", userQuery);
        messages.add(userMessage);

        return messages;
    }

    /**
     * 保存对话记录到会话历史
     */
    private void saveConversation(String sessionId, Long userId, String userQuery,
                                  String aiContent, List<String> toolsUsed, long duration) {
        try {
            if (sessionId == null || aiContent == null || aiContent.isEmpty()) return;

            Map<String, Object> usage = new HashMap<>();
            usage.put("streamMode", true);
            usage.put("toolsCount", toolsUsed != null ? toolsUsed.size() : 0);

            conversationHistoryService.saveConversationToSession(
                    sessionId, userId, userQuery, aiContent,
                    null, usage, toolsUsed, duration
            );
            log.debug("流式对话记录已保存 - 会话ID: {}, 用户ID: {}", sessionId, userId);
        } catch (Exception e) {
            log.error("保存流式对话记录失败 - 会话ID: {}", sessionId, e);
        }
    }

    /**
     * 读取InputStream为字符串
     */
    private String readStream(InputStream is) {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "读取错误: " + e.getMessage();
        }
    }

    // ==================== 内部数据类 ====================

    /**
     * 工具调用累积器 - 流式响应中tool_calls是分片到达的，需要逐步拼接
     */
    private static class ToolCallAccumulator {
        String id = "";
        String functionName = "";
        StringBuilder arguments = new StringBuilder();
    }

    /**
     * 流式查询结果 - 包含完整文本和使用的工具列表
     */
    private static class StreamResult {
        final String fullContent;
        final List<String> toolsUsed;

        StreamResult(String fullContent, List<String> toolsUsed) {
            this.fullContent = fullContent;
            this.toolsUsed = toolsUsed;
        }
    }
}
