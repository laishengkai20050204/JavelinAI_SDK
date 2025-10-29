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
        List<ChatMessage> msgs = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Object role = r.get("role");
            Object content = r.get("content");
            String roleStr = role == null ? "user" : String.valueOf(role);
            String text = content instanceof String s ? s : (content == null ? "" : String.valueOf(content));
            msgs.add(new ChatMessage(roleStr, text));
        }

        // 5) 上下文哈希（日志/观测）
        String base;
        try {
            base = objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            base = st.req() != null && st.req().q() != null ? st.req().q().trim() : "";
        }
        String hash = Fingerprint.sha256(base);

        return Mono.just(new AssembledContext(msgs, hash));
    }
}
