package com.example.service.impl;

import com.example.ai.SpringAiChatGateway;
import com.example.api.dto.*;
import com.example.config.AiProperties;
import com.example.service.DecisionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class DecisionServiceSpringAi implements DecisionService {

    private final SpringAiChatGateway gateway;
    private final AiProperties props;
    private final ObjectMapper mapper;

    @Override
    public Mono<ModelDecision> decide(StepState st, AssembledContext ctx) {
        // 1) 组 messages：system + assembled 历史 + 当前用户（若最后一条不是 user）
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system",
                "You are a helpful assistant who writes concise, accurate answers. " +
                        "If tools are available and helpful, propose function-calling tool calls."));

        if (ctx != null && ctx.messages() != null) {
            for (ChatMessage m : ctx.messages()) {
                if (StringUtils.hasText(m.content())) {
                    messages.add(msg(m.role(), m.content()));
                }
            }
        }
        if (messages.isEmpty() || !"user".equals(messages.get(messages.size() - 1).get("role"))) {
            messages.add(msg("user", Optional.ofNullable(st.req().q()).orElse("")));
        }

        // 2) 透传 toolChoice / clientTools / 标识
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", props.getModel());
        payload.put("messages", messages);
        if (StringUtils.hasText(st.req().toolChoice())) {
            payload.put("toolChoice", st.req().toolChoice());
        }
        if (st.req().clientTools() != null) {
            payload.put("clientTools", st.req().clientTools());
        }
        payload.put("userId", st.req().userId());
        payload.put("conversationId", st.req().conversationId());
        payload.put("stepId", st.stepId());

        AiProperties.Mode mode = props.getMode();

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
}
