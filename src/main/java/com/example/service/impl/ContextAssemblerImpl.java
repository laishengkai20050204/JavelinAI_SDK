package com.example.service.impl;

import com.example.api.dto.AssembledContext;
import com.example.api.dto.ChatMessage;
import com.example.api.dto.StepState;
import com.example.config.AiProperties;
import com.example.service.ContextAssembler;
import com.example.service.ConversationMemoryService;
import com.example.util.MsgTrace;
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
    private final AiProperties aiProperties;

    @Override
    public Mono<AssembledContext> assemble(StepState st) {

        // 0) 绑定 stepId -> (userId, conversationId)
        if (st.req() != null) {
            stepStore.bind(st.stepId(), st.req().userId(), st.req().conversationId());
        }
        final String userId = (st.req() != null) ? st.req().userId() : null;
        final String conversationId = (st.req() != null) ? st.req().conversationId() : null;

        // 2) 读取上下文（只读 FINAL；条数从配置读取，默认 12）
        int limit = 12;
        try {
            if (aiProperties != null && aiProperties.getMemory() != null) {
                int cfg = aiProperties.getMemory().getMaxMessages(); // int，不能与 null 比较
                limit = Math.max(1, cfg);
            }
        } catch (Exception ignore) {}

        // 1) 历史 FINAL（保留原行为，受 limit 约束）
        final List<Map<String, Object>> finalRows =
                (userId != null && conversationId != null)
                        ? memoryService.getContext(userId, conversationId, limit)
                        : List.of();

        final List<Map<String, Object>> stepRows =
                (st.stepId() != null && !st.stepId().isBlank())
                        ? memoryService.getContext(userId, conversationId, st.stepId(), limit)
                        : List.of();

        final List<Map<String, Object>> rows = new ArrayList<>(finalRows);
        if (!stepRows.isEmpty()) rows.addAll(stepRows);



        // 3) 单趟构造最终 messages（严格按 DB 顺序）
        final String SYSTEM_PROMPT =
                "You are a helpful assistant who writes concise, accurate answers. "
                        + "If tools are available and helpful, propose function-calling tool calls.";

        List<Map<String, Object>> modelMessages = new ArrayList<>();
        modelMessages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        // 可选：为了向后兼容你原有 AssembledContext 字段，顺便维护：
        final List<ChatMessage> plainTexts = new ArrayList<>();        // 非工具行的扁平文本（user/assistant）
        final List<Map<String, Object>> structured = new ArrayList<>(); // 仅工具相关（assistant(tool_calls)+tool）

        // 已出现过的决策（避免重复合成）
        Set<String> seenDecisionIds = new HashSet<>();

        for (Map<String, Object> r : rows) {
            String roleStr = String.valueOf(r.getOrDefault("role", "user"));
            Object content = r.get("content");
            String text = (content instanceof String s) ? s : (content == null ? "" : String.valueOf(content));
            String payloadJson = toJsonString(r.get("payload"));

            // 3.1 assistant 决策（assistant_decision → 直接透传为 assistant(tool_calls)）
            if ("assistant".equalsIgnoreCase(roleStr)) {
                boolean handled = false;
                if (payloadJson != null && !payloadJson.isBlank()) {
                    try {
                        var root = objectMapper.readTree(payloadJson);
                        if ("assistant_decision".equals(root.path("type").asText("")) && root.has("tool_calls")) {
                            List<Map<String, Object>> tcs = new ArrayList<>();
                            for (var n : root.path("tool_calls")) {
                                String id   = n.path("id").asText("call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
                                String name = n.path("function").path("name").asText("unknown_tool");
                                String args = n.path("function").path("arguments").asText("{}"); // 已是字符串
                                tcs.add(Map.of(
                                        "id", id,
                                        "type", "function",
                                        "function", Map.of("name", name, "arguments", args)
                                ));
                                seenDecisionIds.add(id);
                            }
                            Map<String,Object> assistantWithToolCalls = Map.of(
                                    "role", "assistant", "content", "", "tool_calls", tcs
                            );
                            modelMessages.add(assistantWithToolCalls);
                            structured.add(assistantWithToolCalls);
                            handled = true;
                        }
                    } catch (Exception ignore) {}
                }
                if (handled) continue;

                // 普通 assistant 文本：空文本就别加，避免空节点
                if (!text.isBlank()) {
                    modelMessages.add(Map.of("role", "assistant", "content", text));
                    plainTexts.add(new ChatMessage("assistant", text));
                }
                continue;
            }

            // 3.2 tool 行：若没出现过对应决策，就地补一条 assistant(tool_calls)，然后追加 tool
            if ("tool".equalsIgnoreCase(roleStr)) {
                String toolName = null, toolCallId = null, argsStr = "{}";

                if (payloadJson != null && !payloadJson.isBlank()) {
                    try {
                        var root = objectMapper.readTree(payloadJson);
                        toolName   = root.path("name").asText(null);
                        toolCallId = root.path("tool_call_id").asText(null);

                        // 权威参数（优先 payload.args；兜底 data._executedKey）
                        String persistedArgs = root.path("args").asText(null);
                        if (persistedArgs != null && !persistedArgs.isBlank()) {
                            try { objectMapper.readTree(persistedArgs); argsStr = persistedArgs; } catch (Exception ignore) {}
                        }
                        if ("{}".equals(argsStr)) {
                            String ek = root.path("data").path("_executedKey").asText(null);
                            if (ek != null) {
                                int idx = ek.indexOf("::");
                                if (idx >= 0 && idx + 2 < ek.length()) {
                                    String maybeJson = ek.substring(idx + 2);
                                    try { objectMapper.readTree(maybeJson); argsStr = maybeJson; } catch (Exception ignore) {}
                                }
                            }
                        }
                    } catch (Exception ignore) {}
                }
                if (toolCallId == null) toolCallId = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                if (toolName == null) toolName = "unknown_tool";

                // ★★★ 关键：构造 tool.content（DB content 为空就从 payload 兜底）
                String toolContent = (text == null) ? "" : text; // text = DB content
                if ((toolContent == null || toolContent.isBlank()) && payloadJson != null && !payloadJson.isBlank()) {
                    try {
                        var root = objectMapper.readTree(payloadJson);
                        // 1) data.payload.value
                        String v1 = root.path("data").path("payload").path("value").asText("");
                        // 2) data.payload（字符串）
                        String v2 = root.path("data").path("payload").isTextual()
                                ? root.path("data").path("payload").asText("") : "";
                        // 3) data.result / result
                        String v3 = root.path("data").path("result").asText("");
                        String v4 = root.path("result").asText("");
                        if (!v1.isBlank()) toolContent = v1;
                        else if (!v2.isBlank()) toolContent = v2;
                        else if (!v3.isBlank()) toolContent = v3;
                        else if (!v4.isBlank()) toolContent = v4;
                        else {
                            // 4) 兜底：把 data.payload 整体序列化成字符串
                            var dp = root.path("data").path("payload");
                            if (!dp.isMissingNode() && !dp.isNull()) {
                                toolContent = dp.isTextual() ? dp.asText() : objectMapper.writeValueAsString(dp);
                            }
                        }
                    } catch (Exception ignore) {}
                }

                // 若没有出现过决策：补一条 assistant(tool_calls)
                if (!seenDecisionIds.contains(toolCallId)) {
                    Map<String, Object> assistantWithToolCall = Map.of(
                            "role", "assistant",
                            "content", "",
                            "tool_calls", List.of(Map.of(
                                    "id", toolCallId,
                                    "type", "function",
                                    "function", Map.of("name", toolName, "arguments", argsStr)
                            ))
                    );
                    modelMessages.add(assistantWithToolCall);
                    structured.add(assistantWithToolCall);
                    seenDecisionIds.add(toolCallId);
                }

                // 追加 tool（用兜底后的 content）
                Map<String, Object> toolMsg = Map.of(
                        "role", "tool",
                        "tool_call_id", toolCallId,
                        "name", toolName,
                        "content", toolContent   // ★ 永远不是 null；优先 DB，失败再 payload 兜底
                );
                modelMessages.add(toolMsg);
                structured.add(toolMsg);

                continue;
            }

            // 3.3 其他（user / 其它角色）
            modelMessages.add(Map.of("role", roleStr, "content", text));
            plainTexts.add(new ChatMessage(roleStr, text));
        }

        // 4) 计算上下文哈希（基于 rows + “工具对”）
        String hash;
        try {
            String base = objectMapper.writeValueAsString(Map.of("rows", rows, "structured", structured));
            hash = Fingerprint.sha256(base);
        } catch (Exception e) {
            hash = Fingerprint.sha256((rows == null ? 0 : rows.size()) + ":" + (structured == null ? 0 : structured.size()));
        }

        log.trace("[TRACE M1] assembled modelMessages size={} last={} digest={}",
                modelMessages.size(),
                MsgTrace.lastLine(modelMessages),
                MsgTrace.digest(modelMessages));

        // 5) 返回（保持你原 AssembledContext 的语义）
        return Mono.just(new AssembledContext(plainTexts, hash, structured, modelMessages));
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
