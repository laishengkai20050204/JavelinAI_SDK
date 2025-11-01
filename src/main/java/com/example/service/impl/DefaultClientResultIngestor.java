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

    // 幂等：按 (stepId, callId) 维度
    private final Set<String> ingestedResultKeys = ConcurrentHashMap.newKeySet();

    @Override
    public Mono<Void> ingest(StepState st, List<Map<String,Object>> clientResults) {
        if (st == null || st.req() == null || clientResults == null || clientResults.isEmpty()) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
            List<ToolResult> results = ClientResultNormalizer.normalize(clientResults, objectMapper);

            String userId = st.req().userId();
            String convId = st.req().conversationId();
            int seq = Optional.ofNullable(memoryService.findMaxSeq(userId, convId, st.stepId())).orElse(0) + 1;

            for (ToolResult r : results) {
                String callId = r.callId();                   // ★ 关键
                if (callId == null || callId.isBlank()) {
                    // 没有 callId 就退化到整条结果做哈希去重（可选）
                    callId = "noid:" + Objects.hashCode(r);
                }
                String key = st.stepId() + "|" + callId;
                if (!ingestedResultKeys.add(key)) {
                    // 已吸收过这条 callId，跳过
                    continue;
                }

                Map<String,Object> data      = ToolPayloads.toMap(r.data(), objectMapper);
                String argsStr               = ToolPayloads.extractArgsString(data, objectMapper);
                String readableText          = ToolPayloads.extractReadableText(data, objectMapper);

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

                // （可选，推荐）告知 StepContextStore：这条客户端调用已吸收
                // 需要在 StepContextStore 里新增 markClientResultIngested(stepId, callId)
                stepStore.markClientResultIngested(st.stepId(), callId);
            }

            // ✅ 建议：不要在这里 promote（统一由主循环在 DONE 时转正）
            // 如果你现在依赖 FINAL 可见性，也可以临时保留；但更稳的是由 loop() 统一转正
            // memoryService.promoteDraftsToFinal(userId, convId, st.stepId());

            // 兜底：把结果也暂存到 stepStore，便于 Assembler 或网关拼接
            stepStore.saveToolResults(st.stepId(), results);

            log.debug("[clientResults] ingested {} item(s), step={}", results.size(), st.stepId());
            return true;
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then();
    }

}
