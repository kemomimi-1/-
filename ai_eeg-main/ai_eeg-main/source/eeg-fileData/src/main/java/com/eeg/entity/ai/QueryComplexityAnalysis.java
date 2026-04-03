package com.eeg.entity.ai;
import lombok.Data;
import java.util.List;
@Data
public class QueryComplexityAnalysis {
    private ComplexityLevel level;
    private int score;
    private String description;
    private List<String> keywords;
}