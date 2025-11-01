package com.example.service.impl;

import com.example.ai.SpringAiChatGateway;
import com.example.api.dto.*;
import com.example.config.AiProperties;
import com.example.config.EffectiveProps;
import com.example.service.ConversationMemoryService;
import com.example.service.DecisionService;
import com.example.tools.support.JsonCanonicalizer;
import com.example.util.Fingerprint;
import com.example.util.MsgTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class DecisionServiceSpringAi implements DecisionService {

    private final SpringAiChatGateway gateway;
    private final AiProperties props;
    private final EffectiveProps effectiveProps;
    private final ObjectMapper mapper;
    private final ConversationMemoryService memoryService;
    private final Map<String, Set<String>> decisionSeen = new ConcurrentHashMap<>();

    @Override
    public Mono<ModelDecision> decide(StepState st, AssembledContext ctx) {
        // 由 ContextAssemblerImpl 统一产出的最终 messages
        List<Map<String, Object>> messages =
                (ctx != null && ctx.modelMessages() != null) ? ctx.modelMessages() : List.of();

        String m2Digest = MsgTrace.digest(messages);
        log.trace("[TRACE M2] before gateway size={} last={} digest={}",
                messages.size(), MsgTrace.lastLine(messages), m2Digest);

        Map<String, Object> payload = new HashMap<>();
        // Prefer runtime model if provided; fallback handled by EffectiveProps
        payload.put("model", effectiveProps.model());
        payload.put("messages", messages);
        payload.put("_flattened", true);

        // —— 铅封：把 digest 放进 payload
        payload.put("_tamperSeal", m2Digest);

        // 其他字段照旧...
        if (StringUtils.hasText(st.req().toolChoice())) payload.put("toolChoice", st.req().toolChoice());
        if (st.req().clientTools() != null) payload.put("clientTools", st.req().clientTools());
        payload.put("userId", st.req().userId());
        payload.put("conversationId", st.req().conversationId());
        payload.put("stepId", st.stepId());

        AiProperties.Mode mode = effectiveProps.mode();

        // 3) 调网关 → 解析（带调试块抽取 + tool_calls 回退逻辑）
        return gateway.call(payload, mode)
                .map(json -> {
                    try {
                        JsonNode root = mapper.readTree(json);

                        // 3.1 可选：把三块 provider 调试块打印出来（不破坏业务）
                        JsonNode dbgReq = root.path("_provider_request");
                        JsonNode dbgRaw = root.path("_provider_raw");
                        JsonNode dbgAsst = root.path("_provider_assistant");
                        if ((dbgReq != null && !dbgReq.isMissingNode())
                                || (dbgRaw != null && !dbgRaw.isMissingNode())
                                || (dbgAsst != null && !dbgAsst.isMissingNode())) {
                            try {
                                if (dbgReq != null && !dbgReq.isMissingNode()) {
                                    String s = truncate(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dbgReq), 4000);
                                    log.trace("[DECIDE:PROVIDER:_request] {}", s);
                                }
                                if (dbgRaw != null && !dbgRaw.isMissingNode()) {
                                    String s = truncate(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dbgRaw), 4000);
                                    log.trace("[DECIDE:PROVIDER:_raw] {}", s);
                                }
                                if (dbgAsst != null && !dbgAsst.isMissingNode()) {
                                    String s = truncate(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dbgAsst), 4000);
                                    log.trace("[DECIDE:PROVIDER:_assistant] {}", s);
                                }
                            } catch (Exception ignore) {}
                        }

                        // 3.2 解析 tool_calls：优先 choices[0].message.tool_calls，缺失则回退到 _provider_assistant.tool_calls
                        List<ToolCall> calls = parseToolCallsWithFallback(root, clientToolNames(st));

                        // 3.3 额外解析“决策阶段文本草稿”（无工具时就地复用，避免二次调用）
                        String draft = extractAssistantDraft(root); // 见下方方法

                        if (!calls.isEmpty()
                                && st != null && st.req() != null
                                && org.springframework.util.StringUtils.hasText(st.req().userId())
                                && org.springframework.util.StringUtils.hasText(st.req().conversationId())) {

                            String fp = fingerprintToolCalls(calls);

                            // 同一 step 内：不同决策（不同指纹）会多次写入；相同决策跳过
                            if (markDecisionOnce(st.stepId(), fp)) {
                                persistAssistantDecisionDraft(
                                        memoryService,
                                        mapper,
                                        st.req().userId(),
                                        st.req().conversationId(),
                                        st.stepId(),
                                        calls
                                );
                                log.debug("[memory] decision draft persisted (new) step={} fp={}", st.stepId(), fp.substring(0, 12));
                            } else {
                                log.debug("[memory] decision draft skipped (duplicate) step={} fp={}", st.stepId(), fp.substring(0, 12));
                            }
                        }

                        return new ModelDecision(calls, (draft != null && !draft.isBlank()) ? draft : null);

                    } catch (Exception e) {
                        return ModelDecision.empty();
                    }
                });

    }

    private String extractAssistantDraft(JsonNode root) {
        String c1 = root.path("choices").path(0).path("message").path("content").asText("");
        if (org.springframework.util.StringUtils.hasText(c1)) return c1;
        String c2 = root.path("_provider_assistant").path("content").asText("");
        return org.springframework.util.StringUtils.hasText(c2) ? c2 : null;
    }


    /** 先从 choices[0].message.tool_calls 取；没有则回退到 _provider_assistant.tool_calls */
    private List<ToolCall> parseToolCallsWithFallback(JsonNode root, Set<String> clientNames) {
        // primary: choices[0].message.tool_calls
        JsonNode primary = root.path("choices").path(0).path("message").path("tool_calls");
        List<ToolCall> out = nodeToToolCalls(primary, clientNames);
        if (!out.isEmpty()) return out;

        // fallback: _provider_assistant.tool_calls
        JsonNode fallback = root.path("_provider_assistant").path("tool_calls");
        out = nodeToToolCalls(fallback, clientNames);
        return out;
    }

    private List<ToolCall> nodeToToolCalls(JsonNode toolCalls, Set<String> clientNames) {
        if (toolCalls == null || !toolCalls.isArray() || toolCalls.size() == 0) return List.of();
        List<ToolCall> out = new ArrayList<>();
        for (JsonNode node : toolCalls) {
            String id = node.path("id").asText("call-" + UUID.randomUUID());
            String name = node.path("function").path("name").asText("");
            String argsJson = node.path("function").path("arguments").asText("{}");
            Map<String, Object> args = safeParseMap(argsJson);
            String target = clientNames.contains(name) ? "CLIENT" : "SERVER";
            out.add(ToolCall.of(id, name, args, target));
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, Math.max(0, max)) + "...(truncated)";
    }



    // ---- helpers ----

    private Map<String, Object> msg(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private Set<String> clientToolNames(StepState st) {
        if (st.req().clientTools() == null) return Collections.emptySet();
        Set<String> names = new HashSet<>();
        for (Object o : st.req().clientTools()) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object fn = m.get("function");
            if (fn instanceof Map<?, ?> fm) {
                Object n = fm.get("name");
                if (n != null && StringUtils.hasText(n.toString())) names.add(n.toString());
            }
        }
        return names;
    }

    private List<ToolCall> parseDecisionFromJson(String json, Set<String> clientNames) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode msg = root.path("choices").path(0).path("message");
            JsonNode toolCalls = msg.path("tool_calls");
            if (!toolCalls.isArray()) return List.of();

            List<ToolCall> out = new ArrayList<>();
            for (JsonNode node : toolCalls) {
                String id = node.path("id").asText("call-" + UUID.randomUUID());
                String name = node.path("function").path("name").asText("");
                String argsJson = node.path("function").path("arguments").asText("{}");
                Map<String, Object> args = safeParseMap(argsJson);
                String target = clientNames.contains(name) ? "CLIENT" : "SERVER";
                out.add(ToolCall.of(id, name, args, target));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeParseMap(String json) {
        try { return mapper.readValue(json, Map.class); }
        catch (Exception ignore) { return Map.of(); }
    }

    private void persistAssistantDecisionDraft(
            ConversationMemoryService memoryService,
            ObjectMapper objectMapper,
            String userId,
            String conversationId,
            String stepId,
            List<com.example.api.dto.ToolCall> calls
    ) {
        try {
            Integer max = memoryService.findMaxSeq(userId, conversationId, stepId);
            int seq = (max == null ? 0 : max) + 1;

            // 统一保存成 OpenAI 风格结构，后续回灌最稳
            List<Map<String, Object>> tcPayload = new ArrayList<>();
            for (var c : calls) {
                tcPayload.add(Map.of(
                        "id",        c.id(),
                        "type",      "function",
                        "function",  Map.of(
                                "name", c.name(),
                                // 这里一定是字符串；若你有 stableArgs(mapper) 可直接用
                                "arguments", c.stableArgs(objectMapper)
                        )
                ));
            }

            Map<String, Object> payload = Map.of(
                    "source",     "model",
                    "type",       "assistant_decision",
                    "tool_calls", tcPayload,
                    "stepId",     stepId
            );

            memoryService.upsertMessage(
                    userId,
                    conversationId,
                    "assistant",                 // ← 决策来自 assistant
                    "",                          // content 为空（只存决策结构）
                    objectMapper.writeValueAsString(payload),
                    stepId,
                    seq,
                    "DRAFT"                      // 本轮结束再 promote
            );

            log.debug("[memory] decision draft persisted: user={} conv={} step={} tcs={}",
                    userId, conversationId, stepId, tcPayload.size());
        } catch (Exception e) {
            log.warn("[memory] persist assistant decision failed: user={} conv={} step={} err={}",
                    userId, conversationId, stepId, e.toString());
        }
    }

    private boolean markDecisionOnce(String stepId, String decisionFp) {
        return decisionSeen
                .computeIfAbsent(stepId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .add(decisionFp);
    }

    private String fingerprintToolCalls(java.util.List<ToolCall> calls) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (ToolCall c : calls) {
            String argsStr = c.stableArgs(mapper); // 你的 ToolCall 已有稳定参数方法
            try {
                // 规范化，忽略抖动字段（按你去重账本的习惯）
                var canon = JsonCanonicalizer.normalize(
                        mapper,
                        mapper.readTree(argsStr),
                        java.util.Set.of("nonce","timestamp","requestId")
                ).toString();
                parts.add(c.name() + "::" + canon);
            } catch (Exception e) {
                // 兜底：解析失败也别中断
                parts.add(c.name() + "::" + String.valueOf(argsStr));
            }
        }
        java.util.Collections.sort(parts);
        return Fingerprint.sha256(String.join("|", parts));
    }


}
