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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        // 3) 读取上下文（只读 FINAL；由你的 mapper 决定，确保 selectContext 仅取 FINAL）
        int limit = 12; // 默认值
        if (aiProperties != null && aiProperties.getMemory() != null) {
            limit = Math.max(1, aiProperties.getMemory().getMaxMessages());
        }

        java.util.List<java.util.Map<String, Object>> rows =
                (userId != null && conversationId != null)
                        ? memoryService.getContext(userId, conversationId, limit)
                        : java.util.List.of();

        // 4) 转成 ChatMessage（role + content）
        // 4) 转成 ChatMessage（role + content） + 提取工具行为结构化工具消息
        List<ChatMessage> msgs = new ArrayList<>(rows.size());
        List<Map<String, Object>> structured = new ArrayList<>(); // ★ 新增

        for (Map<String, Object> r : rows) {
            Object role = r.get("role");
            Object content = r.get("content");
            String roleStr = role == null ? "user" : String.valueOf(role);
            String text = content instanceof String s ? s : (content == null ? "" : String.valueOf(content));

            if ("tool".equalsIgnoreCase(roleStr)) {
                // ★ 把工具行重放为：assistant(tool_calls) + tool 两条结构化消息
                String payloadJson = null;
                Object payload = r.get("payload");
                try {
                    if (payload instanceof String ps) {
                        payloadJson = ps;
                    } else if (payload != null) {
                        payloadJson = objectMapper.writeValueAsString(payload);
                    }
                } catch (Exception ignore) {}

                String toolName = null, toolCallId = null, argsStr = "{}";
                if (payloadJson != null && !payloadJson.isBlank()) {
                    try {
                        var root = objectMapper.readTree(payloadJson);
                        toolName = root.path("name").asText(null);
                        toolCallId = root.path("tool_call_id").asText(null);

                        // 尝试从 _executedKey 提取“稳定参数”作为 arguments（形如 name::{"userId":"u1","conversationId":"c3"}）
                        String executedKey = root.path("data").path("_executedKey").asText(null);
                        if (executedKey != null) {
                            int idx = executedKey.indexOf("::");
                            if (idx >= 0 && idx + 2 < executedKey.length()) {
                                String maybeJson = executedKey.substring(idx + 2);
                                try {
                                    // 校验一下 JSON 合法性
                                    objectMapper.readTree(maybeJson);
                                    argsStr = maybeJson;
                                } catch (Exception ignore) {}
                            }
                        }
                    } catch (Exception ignore) {}
                }
                if (toolCallId == null) {
                    // 没拿到 id 也能工作：给个稳定但不冲突的 id（不影响模型语义）
                    toolCallId = "call_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                }
                if (toolName == null) {
                    toolName = "unknown_tool";
                }

                // assistant(tool_calls) 消息
                Map<String, Object> assistantWithToolCall = Map.of(
                        "role", "assistant",
                        "content", "",
                        "tool_calls", List.of(Map.of(
                                "id", toolCallId,
                                "type", "function",
                                "function", Map.of(
                                        "name", toolName,
                                        "arguments", argsStr // 必须是字符串
                                )
                        ))
                );
                // tool 消息（content 必须是字符串；你已把工具结果的纯文本存入了 content 列）
                Map<String, Object> toolMsg = Map.of(
                        "role", "tool",
                        "tool_call_id", toolCallId,
                        "name", toolName,
                        "content", text
                );

                structured.add(assistantWithToolCall);
                structured.add(toolMsg);
                // 注意：工具行**不要**再加入 msgs，避免“扁平文本 + 结构化重复”
                continue;
            }

            // 非工具行照旧
            msgs.add(new ChatMessage(roleStr, text));
        }

        // 5) 上下文哈希（日志/观测）不变 ...

        return Mono.just(new AssembledContext(msgs, hash, structured)); // ★ 带上 structured

    }
}
