package com.example.service.impl;

import com.example.api.dto.AssembledContext;
import com.example.api.dto.ChatMessage;
import com.example.api.dto.StepState;
import com.example.config.AiProperties;
import com.example.service.ContextAssembler;
import com.example.service.ConversationMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.util.Fingerprint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContextAssemblerImpl implements ContextAssembler {

    private final ConversationMemoryService memoryService;
    private final ObjectMapper objectMapper;
    private final StepContextStore stepStore;
    private final Set<String> userDraftWritten = ConcurrentHashMap.newKeySet();
    private final AiProperties aiProperties;

    @Override
    public Mono<AssembledContext> assemble(StepState st) {

        if (st.req() != null) {
            stepStore.bind(st.stepId(), st.req().userId(), st.req().conversationId());
        }
        // 1) 绑定 stepId -> (userId, conversationId)
        String userId = st.req() != null ? st.req().userId() : null;
        String conversationId = st.req() != null ? st.req().conversationId() : null;
        // 2) 【新增】把“用户问题”作为 DRAFT 落库（每个 step 只写一次）
        if (userId != null && conversationId != null && st.req() != null) {
            String stepId = st.stepId();
            String q = st.req().q();
            if (q != null && !q.isBlank() && userDraftWritten.add(stepId)) {
                try {
                    Integer max = memoryService.findMaxSeq(userId, conversationId, stepId);
                    int seq = (max == null ? 0 : max) + 1;
                    memoryService.upsertMessage(
                            userId,
                            conversationId,
                            "user",          // 角色
                            q,               // 内容（纯文本）
                            null,            // payloadJson：用户问题一般为空
                            stepId,
                            seq,
                            "DRAFT"          // 先写草稿，轮末再 promote
                    );
                } catch (Exception e) {
                    // 不影响后续流程
                    log.warn("[memory] persist user draft failed: userId={} convId={} stepId={} err={}",
                            userId, conversationId, st.stepId(), e.toString());
                }
            }
        }

// 3) 读取上下文（只读 FINAL）
        int limit = 12;
        if (aiProperties != null && aiProperties.getMemory() != null) {
            limit = Math.max(1, aiProperties.getMemory().getMaxMessages());
        }

        List<Map<String, Object>> rows =
                (userId != null && conversationId != null)
                        ? memoryService.getContext(userId, conversationId, limit)
                        : List.of();

// 4) 转成 ChatMessage（role+content） + 同时提取“结构化的工具消息”
        List<ChatMessage> msgs = new ArrayList<>(rows.size());
        List<Map<String, Object>> structured = new ArrayList<>();

        for (Map<String, Object> r : rows) {
            String roleStr = String.valueOf(r.getOrDefault("role", "user"));
            Object content = r.get("content");
            String text = (content instanceof String s) ? s : (content == null ? "" : String.valueOf(content));

            if ("tool".equalsIgnoreCase(roleStr)) {
                // 从 payload 里还原 tool_calls + tool
                String payloadJson = toJsonString(r.get("payload"));
                String toolName = null, toolCallId = null, argsStr = "{}";

                if (payloadJson != null && !payloadJson.isBlank()) {
                    try {
                        var root = objectMapper.readTree(payloadJson);
                        toolName = nullTo(root.path("name").asText(null), null);
                        toolCallId = nullTo(root.path("tool_call_id").asText(null), null);

                        // 尝试从 data._executedKey 里提取稳定参数
                        String executedKey = root.path("data").path("_executedKey").asText(null);
                        if (executedKey != null) {
                            int idx = executedKey.indexOf("::");
                            if (idx >= 0 && idx + 2 < executedKey.length()) {
                                String maybeJson = executedKey.substring(idx + 2);
                                try {
                                    objectMapper.readTree(maybeJson); // 校验
                                    argsStr = maybeJson;              // ★ OpenAI 风格要求字符串
                                } catch (Exception ignore) { /* fallback keep "{}" */ }
                            }
                        }
                    } catch (Exception ignore) { /* 没 payload 也能工作 */ }
                }

                if (toolCallId == null) {
                    toolCallId = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                }
                if (toolName == null) {
                    toolName = "unknown_tool";
                }

                Map<String, Object> assistantWithToolCall = Map.of(
                        "role", "assistant",
                        "content", "",
                        "tool_calls", List.of(Map.of(
                                "id", toolCallId,
                                "type", "function",
                                "function", Map.of(
                                        "name", toolName,
                                        "arguments", argsStr // 必须是“字符串”
                                )
                        ))
                );

                Map<String, Object> toolMsg = Map.of(
                        "role", "tool",
                        "tool_call_id", toolCallId,
                        "name", toolName,
                        "content", text == null ? "" : text
                );

                structured.add(assistantWithToolCall);
                structured.add(toolMsg);
                // 工具行本身不要再加入 msgs，避免“扁平文本 + 结构化”重复
                continue;
            }

            // 非工具行照旧加入扁平文本消息
            msgs.add(new ChatMessage(roleStr, text));
        }

// 5) 安全计算 hash（把 rows + structured 一起做指纹，避免 NPE）
        String hash;
        try {
            String base = objectMapper.writeValueAsString(Map.of(
                    "rows", rows,
                    "structured", structured
            ));
            hash = Fingerprint.sha256(base);
        } catch (Exception e) {
            hash = Fingerprint.sha256((rows == null ? 0 : rows.size()) + ":" + (structured == null ? 0 : structured.size()));
        }

        // 6) 返回（你的 AssembledContext 需要有第三个字段 structured）
        return Mono.just(new AssembledContext(msgs, hash, structured));

    }

    private String toJsonString(Object payload) {
        if (payload == null) return null;
        if (payload instanceof String s) return s;
        try { return objectMapper.writeValueAsString(payload); }
        catch (Exception e) { return null; }
    }

    private static <T> T nullTo(T val, T fallback) {
        return val != null ? val : fallback;
    }

}
