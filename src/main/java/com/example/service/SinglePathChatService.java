package com.example.service;

import com.example.api.dto.*;
import com.example.api.dto.StepEvent;
import com.example.api.dto.StepState;
import com.example.api.dto.StepTransition;
import com.example.api.dto.ToolCall;
import com.example.api.dto.ToolResult;
import com.example.config.AiProperties;
import com.example.service.impl.StepContextStore;
import com.example.util.Fingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SinglePathChatService {

    private final ContextAssembler contextAssembler;
    private final DecisionService decisionService;
    private final ToolExecutionPipeline toolPipeline;
    private final ContinuationService continuationService;
    private final ConversationMemoryService memoryService;
    private final AiProperties aiProperties;
    private final Guardrails guardrails;
    private final ObjectMapper objectMapper;
    private final StepContextStore stepStore;

    public Flux<StepEvent> run(ChatRequest req) {
        return Flux.create(sink -> {
            String stepId = "step-" + UUID.randomUUID();
            StepState init = StepState.init(req, stepId);

            sink.next(StepEvent.started(stepId, init.loop()));

            AtomicBoolean cancelled = new AtomicBoolean(false);
            sink.onCancel(() -> cancelled.set(true));
            sink.onDispose(() -> cancelled.set(true));

            loop(init, sink, cancelled);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private void loop(StepState st, FluxSink<StepEvent> sink, AtomicBoolean cancelled) {
        if (cancelled.get()) {
            sink.complete();
            return;
        }
        if (st.finished() || guardrails.reachedMaxLoops(st)) {
            var req = st.req();
            if (req != null) {
                // 本轮所有 DRAFT（user/tool/assistant）一次性转 FINAL
                try {
                    if (aiProperties != null && aiProperties.getMemory() != null && aiProperties.getMemory().isPromoteDraftsOnFinish()) {
                        memoryService.promoteDraftsToFinal(req.userId(), req.conversationId(), st.stepId());
                    }
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(getClass())
                            .warn("[memory] promoteDraftsToFinal failed: stepId={}, err={}", st.stepId(), e.toString());
                }
            }
            sink.next(StepEvent.finished(st.stepId(), st.loop()));
            sink.complete();
            return;
        }

        doOneStep(st)
                .subscribe(tr -> {
                    tr.events().forEach(sink::next);
                    loop(tr.nextState(), sink, cancelled);
                }, err -> {
                    // 记录堆栈，方便排查
                    org.slf4j.LoggerFactory.getLogger(getClass()).error("[step-ndjson] loop error", err);
                    // 直接传 Throwable，避免 null message 再次触发 NPE
                    sink.next(StepEvent.error(st.stepId(), st.loop(), err));
                    sink.complete();
                });
    }

    private Mono<StepTransition> doOneStep(StepState st) {
        // 1) pending 优先消费（仅 SERVER）
        if (st.hasPendingServerTools()) {
            return execPending(st);
        }

        // 2) 组装记忆
        return contextAssembler.assemble(st)
                .flatMap(ctx -> {
                    StepState withHash = st.withContextHash(ctx.hash());

                    // 3) toolChoice=none → 直接续写并结束
                    if ("none".equalsIgnoreCase(st.req().toolChoice())) {
                        return continueAnswer(withHash, ctx);
                    }

                    // 4) 模型决策
                    return decisionService.decide(st, ctx)
                            .flatMap(decision -> {
                                List<ToolCall> allCalls = decision.tools();

                                if (allCalls != null && !allCalls.isEmpty()) {
                                    stepStore.savePlannedCalls(st.stepId(), allCalls);
                                }

                                // 4.1 没有工具建议：复用草稿；否则续写
                                if (allCalls.isEmpty()) {
                                    String draft = decision.assistantDraft();
                                    if (org.springframework.util.StringUtils.hasText(draft)) {
                                        Map<String, Object> payload = new LinkedHashMap<>();
                                        payload.put("type", "assistant");
                                        payload.put("text", draft);
                                        return continuationService.appendAssistantToMemory(st.stepId(), draft)
                                                .thenReturn(StepTransition.of(withHash.finish(), List.of(StepEvent.step(payload))));
                                    }
                                    return continueAnswer(withHash, ctx);
                                }

                                // 4.2 拆分目标
                                List<ToolCall> serverCalls = allCalls.stream()
                                        .filter(tc -> "SERVER".equalsIgnoreCase(tc.execTarget()))
                                        .toList();
                                List<ToolCall> clientCalls = allCalls.stream()
                                        .filter(tc -> "CLIENT".equalsIgnoreCase(tc.execTarget()))
                                        .toList();

                                // 决策事件（便于前端展示）
                                StepEvent decisionEvent = decisionEvent(allCalls);

                                // 客户端调用事件
                                List<StepEvent> extraEvents = new ArrayList<>();
                                if (!clientCalls.isEmpty()) {
                                    Map<String, Object> m = new LinkedHashMap<>();
                                    m.put("type", "clientCalls");
                                    m.put("calls", serializeCalls(clientCalls));
                                    extraEvents.add(StepEvent.step(m));
                                }

                                // 4.3 有 SERVER 调用
                                if (!serverCalls.isEmpty()) {
                                    // 事件集合
                                    List<StepEvent> events = new ArrayList<>();
                                    events.add(decisionEvent);
                                    events.addAll(extraEvents);

                                    // 过滤出真正需要执行的 SERVER 调用（跨轮 + 本批 去重）
                                    List<ToolCall> pending = serverCalls.stream()
                                            .filter(tc -> {
                                                String k = tc.name() + "::" + tc.stableArgs(objectMapper);
                                                return !st.executedKeys().contains(k); // 已执行过则过滤掉
                                            })
                                            .collect(Collectors.collectingAndThen(
                                                    Collectors.toMap(
                                                            tc -> tc.name() + "::" + tc.stableArgs(objectMapper),
                                                            tc -> tc,
                                                            (a, b) -> a,
                                                            LinkedHashMap::new
                                                    ),
                                                    m -> new ArrayList<>(m.values())
                                            ));

                                    if (!pending.isEmpty()) {
                                        // 放入 pending，进入下一轮 execPending
                                        StepState next = withHash.withPending(pending);
                                        return Mono.just(StepTransition.of(next, events));
                                    } else {
                                        // 所有 SERVER 工具都被去重过滤（已执行过）→ 不再空转
                                        String draft = decision.assistantDraft();
                                        if (org.springframework.util.StringUtils.hasText(draft)) {
                                            Map<String, Object> payload = new LinkedHashMap<>();
                                            payload.put("type", "assistant");
                                            payload.put("text", draft);
                                            events.add(StepEvent.step(payload));
                                            return continuationService.appendAssistantToMemory(st.stepId(), draft)
                                                    .thenReturn(StepTransition.of(withHash.finish(), events));
                                        } else {
                                            // 决策无草稿 → 续写一次生成最终回答
                                            return continueAnswer(withHash, ctx)
                                                    .map(tr -> {
                                                        List<StepEvent> merged = new ArrayList<>(events);
                                                        merged.addAll(tr.events());
                                                        return StepTransition.of(tr.nextState(), merged);
                                                    });
                                        }
                                    }
                                }

                                // 4.4 只有 CLIENT 工具（serverCalls 为空但 allCalls 非空）
                                List<StepEvent> events = new ArrayList<>();
                                events.add(decisionEvent);
                                events.addAll(extraEvents);
                                return Mono.just(StepTransition.of(withHash.finish(), events));
                            });
                });
    }



    private Mono<StepTransition> execPending(StepState st) {
        int concurrency = 4;
        Duration perToolTimeout = Duration.ofSeconds(10);

        return Flux.fromIterable(st.pendingServerCalls())
                .flatMapSequential(call -> execOneToolWithIdempotency(st, call)
                                .timeout(perToolTimeout)
                                .onErrorResume(ex -> Mono.just(ToolResult.error(call.id(), call.name(), ex.getMessage()))),
                        concurrency, 1)
                .collectList()
                .flatMap(results ->
                        continuationService.appendToolResultsToMemory(st.stepId(), results)
                                // ★ 立刻转正：user / tool（以及之前已有的 assistant 草稿）都会变成 FINAL
                                .then(Mono.fromRunnable(() -> {
                                    var r = st.req();
                                    if (r != null) {
                                        try {
                                            memoryService.promoteDraftsToFinal(r.userId(), r.conversationId(), st.stepId());
                                        } catch (Exception e) {
                                            org.slf4j.LoggerFactory.getLogger(getClass())
                                                    .warn("[memory] promoteDraftsToFinal (on tools) failed: stepId={}, err={}", st.stepId(), e.toString());
                                        }
                                    }
                                }))
                                // ★ 暂存工具结果，供“下一次 AI-REQ”拼到 messages（见第三步）
                                .then(Mono.fromRunnable(() -> stepStore.saveToolResults(st.stepId(), results)))
                                .thenReturn(results)
                )
                .map(results -> {
                    // 收集已执行键
                    results.forEach(r -> {
                        Object ek = (r.data() instanceof Map<?,?> m) ? m.get("_executedKey") : null;
                        if (ek != null) st.executedKeys().add(String.valueOf(ek));
                    });
                    StepEvent toolsEvent = StepEvent.step(Map.of("type","tools","results", results));
                    StepState next = st.withPending(List.of()).nextLoop();
                    return StepTransition.of(next, List.of(toolsEvent));
                });
    }

    private Mono<ToolResult> execOneToolWithIdempotency(StepState st, ToolCall call) {
        String argsStable = call.stableArgs(objectMapper);
        String fp = Fingerprint.sha256(call.name() + "|" + argsStable + "|" + safe(st.contextHash()));
        String executedKey = call.name() + "::" + argsStable;

        return toolPipeline.tryReuse(st.stepId(), call.name(), fp)
                .switchIfEmpty(
                        toolPipeline.execute(call)
                                .flatMap(res -> toolPipeline.record(st.stepId(), call.name(), fp, res).thenReturn(res))
                )
                .map(res -> ToolResult.success(
                        call.id(), call.name(), res.reused(),
                        Map.of("payload", res.data(), "_executedKey", executedKey) // ← 附带去重键
                ));
    }


    private Mono<StepTransition> continueAnswer(StepState st, AssembledContext ctx) {
        return continuationService.generateAssistant(ctx)
                .flatMap(text -> continuationService.appendAssistantToMemory(st.stepId(), text).thenReturn(text))
                .map(text -> StepTransition.of(st.finish(), List.of(
                        StepEvent.step(Map.of("type", "assistant", "text", text))
                )));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 把整份决策发给前端：便于展示/回放/排障 */
    private StepEvent decisionEvent(List<ToolCall> allCalls) {
        return StepEvent.step(Map.of(
                "type", "decision",
                "toolCalls", serializeCalls(allCalls)
        ));
    }

    /** 序列化 ToolCall（包含 id/name/execTarget/arguments） */
    private List<Map<String, Object>> serializeCalls(List<ToolCall> calls) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolCall c : calls) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("name", c.name());
            m.put("execTarget", c.execTarget());
            // 用 stableArgs() 还原参数；防御性解析成 Map 以便前端易读
            String argsJson = c.stableArgs(objectMapper);
            Map<String, Object> args = safeParseArgs(argsJson);
            m.put("arguments", args);
            items.add(m);
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeParseArgs(String json) {
        if (json == null) return Map.of();
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception ignore) { return Map.of("_raw", json); }
    }

}
