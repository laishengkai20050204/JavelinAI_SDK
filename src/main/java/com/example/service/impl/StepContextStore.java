package com.example.service.impl;

import com.example.api.dto.ToolCall;
import com.example.api.dto.ToolResult;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class StepContextStore {
    public static record Key(String userId, String conversationId) {}

    private final ConcurrentMap<String, Key> map = new ConcurrentHashMap<>();
    // 暂存：本 step 的“assistant 规划的 tool_calls”
    private final ConcurrentMap<String, List<ToolCall>> plannedCalls = new ConcurrentHashMap<>();
    // 暂存：本 step 刚执行完的工具结果
    private final ConcurrentMap<String, List<ToolResult>> toolResults = new ConcurrentHashMap<>();

    private final Map<String, List<ToolCall>> deferredClientCalls = new ConcurrentHashMap<>();

    public void bind(String stepId, String userId, String conversationId) {
        if (stepId == null || userId == null || conversationId == null) return;
        map.put(stepId, new Key(userId, conversationId));
    }

    public Key get(String stepId) {
        return stepId == null ? null : map.get(stepId);
    }

    public void savePlannedCalls(String stepId, List<ToolCall> calls) {
        if (stepId == null || calls == null || calls.isEmpty()) return;
        plannedCalls.put(stepId, new ArrayList<>(calls));
    }

    public List<ToolCall> drainPlannedCalls(String stepId) {
        return stepId == null ? List.of() : plannedCalls.remove(stepId);
    }

    public void saveToolResults(String stepId, List<ToolResult> results) {
        if (stepId == null || results == null || results.isEmpty()) return;
        toolResults.put(stepId, new ArrayList<>(results));
    }

    public List<ToolResult> drainToolResults(String stepId) {
        return stepId == null ? List.of() : toolResults.remove(stepId);
    }

    // ★ NEW: 释放某个 stepId 的所有临时状态
    public void clear(String stepId) {
        if (stepId == null) return;
        map.remove(stepId);
        plannedCalls.remove(stepId);
        toolResults.remove(stepId);
    }

    // ★ NEW: 可选，按 userId+conversationId 批量清理（例如用户中断会话时）
    public void clearByUserConv(String userId, String conversationId) {
        if (userId == null || conversationId == null) return;
        for (Iterator<Map.Entry<String, Key>> it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Key> e = it.next();
            Key k = e.getValue();
            if (userId.equals(k.userId()) && conversationId.equals(k.conversationId())) {
                String stepId = e.getKey();
                it.remove();
                plannedCalls.remove(stepId);
                toolResults.remove(stepId);
            }
        }
    }

    // ★ NEW: 可选，测试或全局重置用
    public void clearAll() {
        map.clear();
        plannedCalls.clear();
        toolResults.clear();
    }

    public void saveClientCalls(String stepId, List<ToolCall> calls) {
        if (calls != null && !calls.isEmpty()) {
            deferredClientCalls.put(stepId, calls);
        }
    }

    public List<ToolCall> pollClientCalls(String stepId) {
        return Optional.ofNullable(deferredClientCalls.remove(stepId)).orElseGet(List::of);
    }
}
