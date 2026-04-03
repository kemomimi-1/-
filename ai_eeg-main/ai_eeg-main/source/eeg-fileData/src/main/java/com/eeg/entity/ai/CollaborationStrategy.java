package com.eeg.entity.ai;
import lombok.Data;
import java.util.List;
import java.util.ArrayList;
@Data
public class CollaborationStrategy {
    private CollaborationType type;
    private ExecutionMode executionMode;
    private int minTools;
    private int maxTools;
    private List<String> requiredTools = new ArrayList<>();
    private List<String> collaborationTypes = new ArrayList<>();
    public void setExpectedToolCount(int min, int max) {
        this.minTools = min;
        this.maxTools = max;
    }
    public void addRequiredTool(String tool) {
        this.requiredTools.add(tool);
    }
    public void addCollaborationType(String type) {
        this.collaborationTypes.add(type);
    }
    public int getExpectedToolCount() {
        return (minTools + maxTools) / 2;
    }
}