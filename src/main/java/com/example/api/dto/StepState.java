package com.example.api.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record StepState(
        String stepId,
        int loop,
        boolean finished,
        List<ToolCall> pendingServerCalls,
        String contextHash,
        ChatRequest req,
        Set<String> executedKeys
) {
    public static StepState init(ChatRequest req, String stepId) {
        return new StepState(stepId, 0, false,
                ToolCall.normalizeServerCalls(req.tool_calls()), null, req,
                new LinkedHashSet<>());
    }
    public StepState nextLoop() { return new StepState(stepId, loop + 1, finished, pendingServerCalls, contextHash, req, executedKeys); }
    public StepState withPending(List<ToolCall> p) { return new StepState(stepId, loop, finished, p, contextHash, req, executedKeys); }
    public StepState withContextHash(String h) { return new StepState(stepId, loop, finished, pendingServerCalls, h, req, executedKeys); }
    public StepState finish() { return new StepState(stepId, loop, true, List.of(), contextHash, req, executedKeys); }
    public boolean hasPendingServerTools() { return pendingServerCalls != null && !pendingServerCalls.isEmpty(); }
}
