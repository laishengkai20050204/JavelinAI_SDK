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
        Set<String> executedKeys,
        FinishReason finishReason
) {
    public static StepState init(ChatRequest req, String stepId) {
        return new StepState(stepId, 0, false,
                ToolCall.normalizeServerCalls(req.tool_calls()), null, req,
                new LinkedHashSet<>(), null);
    }
    public StepState nextLoop() { return new StepState(stepId, loop + 1, finished, pendingServerCalls, contextHash, req, executedKeys, null); }

    public StepState withPending(List<ToolCall> p) { return new StepState(stepId, loop, finished, p, contextHash, req, executedKeys, finishReason); }

    public StepState withContextHash(String h) { return new StepState(stepId, loop, finished, pendingServerCalls, h, req, executedKeys, finishReason); }

    public StepState finish(FinishReason reason) {
        // 只有终结态才把 finished=true；WAIT_CLIENT 则保持 false
        return new StepState(stepId, loop, reason.isTerminal(), List.of(), contextHash, req, executedKeys, reason);
    }

    public boolean isWaitingClient() { return finishReason == FinishReason.WAIT_CLIENT; }

    public StepState finish() { return new StepState(stepId, loop, true, List.of(), contextHash, req, executedKeys, finishReason); }

    public boolean finished() { return finishReason != null && finishReason.isTerminal(); }

    public boolean hasPendingServerTools() { return pendingServerCalls != null && !pendingServerCalls.isEmpty(); }
}
