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

    // assistant 规划的所有 tool_calls（含 SERVER/CLIENT）
    private final ConcurrentMap<String, List<ToolCall>> plannedCalls = new ConcurrentHashMap<>();

    // 本 step 刚执行完的 SERVER 工具结果
    private final ConcurrentMap<String, List<ToolResult>> toolResults = new ConcurrentHashMap<>();

    // —— Client 工具流转治理 —— //
    // 计划好的 CLIENT 调用（尚未下发）
    private final ConcurrentMap<String, List<ToolCall>> deferredClientCalls = new ConcurrentHashMap<>();
    // 已下发给前端的 CLIENT 调用（用于对账/断线重发）
    private final ConcurrentMap<String, List<ToolCall>> dispatchedClientCalls = new ConcurrentHashMap<>();
    // 已吸收结果的 CLIENT callId 集合（幂等）
    private final ConcurrentMap<String, Set<String>> ingestedClientCallIds = new ConcurrentHashMap<>();

    // === 基础绑定 ===
    public void bind(String stepId, String userId, String conversationId) {
        if (stepId == null || userId == null || conversationId == null) return;
        map.put(stepId, new Key(userId, conversationId));
    }

    public Key get(String stepId) {
        return stepId == null ? null : map.get(stepId);
    }

    // === 规划与结果（保持原有语义） ===
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

    // === Client 调用：计划/下发/对账 ===
    public void saveClientCalls(String stepId, List<ToolCall> calls) {
        if (stepId == null || calls == null || calls.isEmpty()) return;
        // 合并去重（按 id），保持稳定顺序
        deferredClientCalls.merge(stepId, new ArrayList<>(calls), (oldL, newL) -> {
            LinkedHashMap<String, ToolCall> m = new LinkedHashMap<>();
            for (ToolCall c : oldL) if (c != null && c.id() != null) m.put(c.id(), c);
            for (ToolCall c : newL) if (c != null && c.id() != null) m.putIfAbsent(c.id(), c);
            return new ArrayList<>(m.values());
        });
    }

    /**
     * 取出并标记为“已下发”（用于与客户端结果对账；断线重连可从 dispatched 中复发）
     */
    public List<ToolCall> pollClientCalls(String stepId) {
        if (stepId == null) return List.of();
        List<ToolCall> planned = deferredClientCalls.remove(stepId);
        if (planned == null || planned.isEmpty()) return List.of();
        dispatchedClientCalls.put(stepId, new ArrayList<>(planned));
        return planned;
    }

    /**
     * 已吸收一个客户端结果（幂等）。ClientResultIngestor 正常吸收后应调用。
     */
    public void markClientResultIngested(String stepId, String callId) {
        if (stepId == null || callId == null || callId.isBlank()) return;
        ingestedClientCallIds
                .computeIfAbsent(stepId, k -> ConcurrentHashMap.newKeySet())
                .add(callId);
    }

    /**
     * 返回当前 step 尚未被吸收结果的 client 调用（优先以已下发集合为准；若还未下发，则看 deferred）
     * 可用于断线重连时“复发同一批未完成的 callId”
     */
    public List<ToolCall> unsatisfiedClientCalls(String stepId) {
        if (stepId == null) return List.of();
        List<ToolCall> base = dispatchedClientCalls.getOrDefault(
                stepId,
                deferredClientCalls.getOrDefault(stepId, List.of())
        );
        if (base.isEmpty()) return List.of();
        Set<String> done = ingestedClientCallIds.getOrDefault(stepId, Collections.emptySet());
        List<ToolCall> out = new ArrayList<>();
        for (ToolCall c : base) {
            if (c != null && c.id() != null && !done.contains(c.id())) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 列出当前 step 的 client 调用（只读视图）：若已下发则给已下发，否则给待下发
     */
    public List<ToolCall> listClientCalls(String stepId) {
        if (stepId == null) return List.of();
        List<ToolCall> d = dispatchedClientCalls.get(stepId);
        if (d != null) return Collections.unmodifiableList(d);
        List<ToolCall> p = deferredClientCalls.get(stepId);
        return p == null ? List.of() : Collections.unmodifiableList(p);
    }

    // === 清理 ===
    public void clear(String stepId) {
        if (stepId == null) return;
        map.remove(stepId);
        plannedCalls.remove(stepId);
        toolResults.remove(stepId);
        deferredClientCalls.remove(stepId);
        dispatchedClientCalls.remove(stepId);
        ingestedClientCallIds.remove(stepId);
    }

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
                deferredClientCalls.remove(stepId);
                dispatchedClientCalls.remove(stepId);
                ingestedClientCallIds.remove(stepId);
            }
        }
    }

    public void clearAll() {
        map.clear();
        plannedCalls.clear();
        toolResults.clear();
        deferredClientCalls.clear();
        dispatchedClientCalls.clear();
        ingestedClientCallIds.clear();
    }
}
