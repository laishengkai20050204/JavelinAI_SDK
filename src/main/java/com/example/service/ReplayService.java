package com.example.service;

import com.example.api.dto.StepEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ReplayService {

    private final ConversationMemoryService memory;
    private final ObjectMapper om;

    public Flux<StepEvent> replay(String userId, String convId, String stepId, int limit) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> rows =
                    StringUtils.hasText(stepId)
                            ? memory.getContextUptoStep(userId, convId, stepId, limit)
                            : memory.getContext(userId, convId, limit);  // 你已有的“回到最近 FINAL”为止的方法
            return rows;
        }).flatMapMany(rows -> Flux.fromIterable(toEvents(rows)));
    }

    private List<StepEvent> toEvents(List<Map<String,Object>> rows) {
        List<StepEvent> out = new ArrayList<>();
        out.add(StepEvent.started("replay-" + System.nanoTime(), 0));

        for (Map<String,Object> r : rows) {
            String role = String.valueOf(r.getOrDefault("role", ""));
            String content = String.valueOf(r.getOrDefault("content", ""));
            Object payload = r.get("payload");

            if ("assistant".equals(role) && isAssistantDecision(payload)) {
                Map<String,Object> p = asMap(payload);
                out.add(StepEvent.step(Map.of(
                        "type","decision",
                        "tool_calls", p.getOrDefault("tool_calls", List.of())
                )));
                continue;
            }
            if ("tool".equals(role)) {
                Map<String,Object> p = asMap(payload);
                out.add(StepEvent.step(Map.of(
                        "type","tool",
                        "name", p.get("name"),
                        "tool_call_id", p.get("tool_call_id"),
                        "reused", p.get("reused"),
                        "status", p.get("status"),
                        "args", p.get("args"),
                        "data", p.get("data"),
                        "text", com.example.util.ToolPayloads.unwrap(p.get("data"), om)
                )));
                continue;
            }
            if (("user".equals(role) || "assistant".equals(role)) && StringUtils.hasText(content)) {
                out.add(StepEvent.step(Map.of(
                        "type","message",
                        "role", role,
                        "text", content
                )));
            }
        }

        out.add(StepEvent.finished("replay-end", 0));
        return out;
    }

    private boolean isAssistantDecision(Object payload) {
        Map<String,Object> p = asMap(payload);
        return "assistant_decision".equals(String.valueOf(p.get("type")));
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> asMap(Object o) {
        if (o instanceof Map<?,?> m) return (Map<String,Object>) m;
        if (o instanceof String s && s.trim().startsWith("{")) {
            try { return om.readValue(s, Map.class); } catch (Exception ignore) {}
        }
        return Map.of();
    }
}
