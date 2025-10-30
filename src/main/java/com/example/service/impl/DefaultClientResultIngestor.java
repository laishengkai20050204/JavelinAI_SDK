package com.example.service.impl;

import com.example.api.dto.StepState;
import com.example.api.dto.ToolResult;
import com.example.service.ClientResultIngestor;
import com.example.service.ConversationMemoryService;
import com.example.util.ClientResultNormalizer;
import com.example.util.ToolPayloads;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultClientResultIngestor implements ClientResultIngestor {

    private final ConversationMemoryService memoryService;
    private final StepContextStore stepStore;
    private final ObjectMapper objectMapper;

    // 幂等：每个 step 只吸收一次
    private final Set<String> ingestedSteps = ConcurrentHashMap.newKeySet();

    @Override
    public Mono<Void> ingest(StepState st, List<Map<String,Object>> clientResults) {
        if (st == null || st.req() == null || clientResults == null || clientResults.isEmpty()) {
            return Mono.empty();
        }
        // 幂等：同一 step 只吃一次
        if (!ingestedSteps.add(st.stepId())) return Mono.empty();

        return Mono.fromCallable(() -> {
            List<ToolResult> results = ClientResultNormalizer.normalize(clientResults, objectMapper);

            String userId = st.req().userId();
            String convId = st.req().conversationId();
            int seq = Optional.ofNullable(memoryService.findMaxSeq(userId, convId, st.stepId())).orElse(0) + 1;

            for (ToolResult r : results) {
                Map<String,Object> data    = ToolPayloads.toMap(r.data(), objectMapper);
                String argsStr            = ToolPayloads.extractArgsString(data, objectMapper);
                String readableText       = ToolPayloads.extractReadableText(data, objectMapper);

                Map<String,Object> payload = new LinkedHashMap<>();
                payload.put("name", r.name());
                payload.put("tool_call_id", r.callId());
                payload.put("reused", r.reused());
                payload.put("status", r.status());
                payload.put("args", argsStr);
                payload.put("data", data);

                memoryService.upsertMessage(
                        userId, convId,
                        "tool", readableText,
                        ToolPayloads.toJson(payload, objectMapper),
                        st.stepId(), seq++, "DRAFT"
                );
            }

            // 立刻转正 + 暂存 stepStore（让后续拼装或网关兜底也能看到）
            memoryService.promoteDraftsToFinal(userId, convId, st.stepId());
            stepStore.saveToolResults(st.stepId(), results);

            log.debug("[clientResults] promote DRAFT->FINAL & saved to stepStore, step={}, ids={}",
                    st.stepId(), results.stream().map(ToolResult::callId).toList());
            return true;
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then();
    }

}
