package com.example.service.impl;

import com.example.api.dto.AssembledContext;
import com.example.api.dto.ToolResult;
import com.example.service.ConversationMemoryService;
import com.example.service.ContinuationService;
import com.example.util.ToolPayloads;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContinuationServiceImpl implements ContinuationService {

    private final ConversationMemoryService memoryService;
    private final ObjectMapper objectMapper;
    private final StepContextStore stepStore;

    @Override
    public Mono<Void> appendToolResultsToMemory(String stepId, List<ToolResult> results) {
        StepContextStore.Key key = stepStore.get(stepId);
        if (key == null || results == null || results.isEmpty()) return Mono.empty();

        String userId = key.userId();
        String conversationId = key.conversationId();

        try {
            int seq = safeNextSeq(userId, conversationId, stepId);

            for (ToolResult r : results) {
                // 1) 统一成 Map，便于读取 payload/args/_executedKey
                Map<String, Object> data = coerceToMap(r.data());

                // 2) 权威参数字符串（优先 data.args，其次从 _executedKey 里抠）
                String argsStr = extractArgsString(data);

                // 3) 可读字符串，写进 DB.content（模型看的就是这个）
                String content = ToolPayloads.extractReadableText(data, objectMapper);
                if (content == null) content = "";
                if (content == null) content = "";

                // 4) 组织 payload（要带上 args & callId）
                String callId = r.callId();                  // ★ 用 callId
                if (callId == null || callId.isBlank()) {
                    // 防御：尝试从 data 里兜底；再不行就生成一个
                    Object tid = data.get("tool_call_id");
                    callId = (tid != null && !String.valueOf(tid).isBlank())
                            ? String.valueOf(tid)
                            : "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("name", r.name());
                payload.put("tool_call_id", callId);         // ★ 关键：与模型/决策的 id 对上
                payload.put("reused", r.reused());
                payload.put("status", r.status());
                payload.put("args", argsStr);                // ★ 关键：独立落库，组装层直接用
                payload.put("data", data);                   // 原始对象保留（含 _executedKey / payload）

                memoryService.upsertMessage(
                        userId, conversationId,
                        "tool",
                        content,
                        toJson(payload),
                        stepId, seq++,
                        "DRAFT"
                );
            }

            // 下一轮要拼回 messages 的话
            stepStore.saveToolResults(stepId, results);

            // 立刻/收尾转正都可，幂等
//            memoryService.promoteDraftsToFinal(userId, conversationId, stepId);

        } catch (Exception e) {
            log.warn("[memory] appendToolResultsToMemory failed: stepId={}, err={}", stepId, e.toString());
        }
        return Mono.empty();
    }

    /** data 可能是 Map / POJO / String，统一成 Map 方便读写 */
    @SuppressWarnings("unchecked")
    private Map<String,Object> coerceToMap(Object data) {
        if (data instanceof Map<?,?> m) {
            return new LinkedHashMap<>((Map<String,Object>) m);
        }
        if (data instanceof String s) {
            try { return objectMapper.readValue(s, Map.class); }
            catch (Exception ignore) { return Map.of("payload", s); } // 字符串就当成 payload
        }
        if (data == null) return new LinkedHashMap<>();
        // 其他对象转 JSON 再转 Map
        try {
            String json = objectMapper.writeValueAsString(data);
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("payload", String.valueOf(data));
        }
    }

    /** 提取权威参数字符串（优先 data.args，其次从 _executedKey 的 'name::{"..."}' 里抠出 JSON） */
    private String extractArgsString(Map<String,Object> data) {
        Object a = data.get("args");
        if (a != null && !String.valueOf(a).isBlank()) {
            String s = String.valueOf(a);
            // 校验必须是 JSON 字符串
            try { objectMapper.readTree(s); return s; } catch (Exception ignore) {}
        }
        Object ek = data.get("_executedKey");
        if (ek != null) {
            String s = String.valueOf(ek);
            int i = s.indexOf("::");
            if (i >= 0 && i + 2 < s.length()) {
                String maybeJson = s.substring(i + 2);
                try { objectMapper.readTree(maybeJson); return maybeJson; } catch (Exception ignore) {}
            }
        }
        return "{}";
    }

    /** 生成写入 DB.content 的“可读字符串” */
    private String extractReadableTextFromData(Map<String,Object> data) {
        // 常见形态：data.payload = {"type":"text","value":"..."} 或 直接是字符串
        Object p = data.get("payload");
        if (p == null) return null;

        if (p instanceof String s) return s;

        if (p instanceof Map<?,?> pm) {
            Object v = ((Map<?,?>) pm).get("value");
            if (v != null) return String.valueOf(v);
            try { return objectMapper.writeValueAsString(pm); } catch (Exception ignore) { return String.valueOf(pm); }
        }

        try { return objectMapper.writeValueAsString(p); } catch (Exception ignore) { return String.valueOf(p); }
    }



    @Override
    public Mono<String> generateAssistant(AssembledContext ctx) {
        // 简单占位：返回上下文哈希
        return Mono.just("【占位】本轮上下文哈希: " + (ctx == null ? "NA" : ctx.hash()));
    }

    @Override
    public Mono<Void> appendAssistantToMemory(String stepId, String text) {
        StepContextStore.Key key = stepStore.get(stepId);
        if (key == null) {
            return Mono.empty();
        }
        String userId = key.userId();
        String conversationId = key.conversationId();

        int seq = safeNextSeq(userId, conversationId, stepId);
        memoryService.upsertMessage(userId, conversationId,
                "assistant", text == null ? "" : text, null,
                stepId, seq, "DRAFT"); // 先落草稿
        return Mono.empty();
    }

    // ---- helpers ----

    private int safeNextSeq(String userId, String conversationId, String stepId) {
        Integer max = memoryService.findMaxSeq(userId, conversationId, stepId);
        return (max == null ? 0 : max) + 1;
    }

    private String toJson(@Nullable Object o) {
        try { return objectMapper.writeValueAsString(o); }
        catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private String extractReadableText(@Nullable Object data) {
        if (data == null) return "";
        if (data instanceof String s) return s;
        if (data instanceof Map<?, ?> m) {
            for (String k : List.of("value","text","content","message","delta")) {
                Object v = m.get(k);
                if (v instanceof String sv && !sv.isBlank()) return sv;
            }
            Object inner = m.get("payload");
            if (inner instanceof Map<?, ?> im) {
                for (String k : List.of("value","text","content","message","delta")) {
                    Object v = im.get(k);
                    if (v instanceof String sv && !sv.isBlank()) return sv;
                }
            }
            return toJson(m);
        }
        if (data instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder();
            for (Object x : it) {
                String part = extractReadableText(x);
                if (part != null && !part.isBlank()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(part);
                }
            }
            return sb.toString();
        }
        return String.valueOf(data);
    }
}
