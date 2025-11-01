package com.example.controller;

import com.example.api.dto.ChatRequest;
import com.example.api.dto.StepEvent;
import com.example.service.SinglePathChatService;
import com.example.service.impl.StepContextStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class OrchestratedChatController {

    private final SinglePathChatService service;
    private final StepContextStore stepStore;     // ★ 新增：用来做配对校验
    private final ObjectMapper objectMapper;

    @Operation(summary = "ndjson统一接口")
    @PostMapping(
            value = "/v3/chat/step/ndjson",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/x-ndjson"
    )
    public Flux<String> chatNdjson(@RequestBody ChatRequest req) {
        // === 预校验：clientResults → 必须带 resumeStepId ===
        boolean hasClientResults = req != null
                && req.clientResults() != null
                && !req.clientResults().isEmpty();

        if (hasClientResults) {
            if (!StringUtils.hasText(req.resumeStepId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "clientResults present but resumeStepId is missing"
                );
            }
            // === 校验 resumeStepId 与 userId/conversationId 配对 ===
            var key = stepStore.get(req.resumeStepId());
            if (key == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "resumeStepId not found or already cleared"
                );
            }
            if (!Objects.equals(req.userId(), key.userId())
                    || !Objects.equals(req.conversationId(), key.conversationId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "resumeStepId does not match userId/conversationId"
                );
            }

            // === 进一步：校验回传的 callId 属于该 step 下发/计划的 clientCalls ===
            // 允许你用 listClientCalls 或 unsatisfiedClientCalls；这里选更宽松的 listClientCalls
            var knownCalls = stepStore.listClientCalls(req.resumeStepId())
                    .stream().map(c -> c.id()).filter(Objects::nonNull).collect(Collectors.toSet());

            // 兼容两种字段：tool_call_id / callId
            var incomingCallIds = new HashSet<String>();
            for (Map<String, Object> m : req.clientResults()) {
                Object id = m.getOrDefault("tool_call_id", m.get("callId"));
                if (id != null) incomingCallIds.add(String.valueOf(id));
            }

            if (!incomingCallIds.isEmpty() && !knownCalls.containsAll(incomingCallIds)) {
                // 找出不认识的 callId，给清晰报错
                Set<String> unknown = new HashSet<>(incomingCallIds);
                unknown.removeAll(knownCalls);
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "unknown tool_call_id for this step: " + unknown
                );
            }
        }

        // === 通过校验，进入编排 ===
        return service.run(req).map(this::toNdjsonLine);
    }

    private String toNdjsonLine(StepEvent e) {
        try {
            return objectMapper.writeValueAsString(e) + "\n";
        } catch (Exception ex) {
            return "{\"event\":\"error\",\"ts\":\"\",\"data\":{\"message\":\"serialize failed\"}}\n";
        }
    }
}
