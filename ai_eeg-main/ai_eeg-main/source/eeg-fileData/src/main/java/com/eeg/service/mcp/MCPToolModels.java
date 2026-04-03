package com.eeg.service.mcp;

import java.util.Map;

/**
 * MCP 工具系统的共享数据模型
 */
public class MCPToolModels {

    public record MCPTool(
            String name,
            String summary,
            String description,
            Map<String, ToolParameter> parameters,
            ToolExecutor executor
    ) {}

    public record ToolParameter(
            String type,
            String name,
            String description,
            boolean required,
            Object defaultValue
    ) {
        public ToolParameter(String type, String name, String description, boolean required) {
            this(type, name, description, required, null);
        }
    }

    @FunctionalInterface
    public interface ToolExecutor {
        Object execute(Long userId, Map<String, Object> arguments, Map<String, Object> context) throws Exception;
    }

    public static class TimeRange {
        public String startTime;
        public String endTime;
        public Long sessionId;
        public boolean hasError = false;
        public String errorMessage;
    }
}
