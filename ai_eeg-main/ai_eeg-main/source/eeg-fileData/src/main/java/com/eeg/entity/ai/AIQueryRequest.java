package com.eeg.entity.ai;
import lombok.Data;
import java.util.Map;
@Data
public class AIQueryRequest {
    private String query;
    private String sessionId;
    private String eegSessionId;
    private Map<String, Object> context;
    private String analysisType;
}