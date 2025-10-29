package com.example.service.impl;

import com.example.api.dto.*;
import com.example.service.ConversationMemoryService;
import com.example.service.DecisionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

//@Service
@Slf4j
public class DecisionServiceImpl implements DecisionService {
    @Override
    public Mono<ModelDecision> decide(StepState st, AssembledContext ctx) {
        // toolChoice=none → 不调用工具
        if ("none".equalsIgnoreCase(st.req().toolChoice())) {
            return Mono.just(ModelDecision.empty());
        }
        // 简单关键字触发：含“调用hello_ai” → 产生一个 hello_ai 工具
        String q = st.req().q() == null ? "" : st.req().q();
        if (q.contains("调用hello_ai")) {
            ToolCall c = ToolCall.of("call_" + st.loop(), "hello_ai", Map.of("echo", q), "SERVER");
            return Mono.just(new ModelDecision(List.of(c), null));
        }
        // 否则不调用工具
        return Mono.just(ModelDecision.empty());
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

}
