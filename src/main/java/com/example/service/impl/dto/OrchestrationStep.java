package com.example.service.impl.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class OrchestrationStep {
    private String stepId;
    private boolean finished;
    private Integer remainingLoops;
    private Context context;
    private List<ToolResultDTO> serverResults;
    private List<ToolCallDTO> pendingClientCalls;
    private String assistant_summary;
    private String finalAnswer;
    private String error;

    @Data
    @Builder
    public static class Context {
        private List<Map<String, Object>> messages;
        private List<String> memoryHints;
    }
}
