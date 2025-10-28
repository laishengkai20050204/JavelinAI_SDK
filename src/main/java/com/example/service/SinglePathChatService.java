package com.example.service;

import com.example.api.dto.*;
import com.example.api.dto.StepEvent;
import com.example.api.dto.StepState;
import com.example.api.dto.StepTransition;
import com.example.api.dto.ToolCall;
import com.example.api.dto.ToolResult;
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
    private final Guardrails guardrails;
    private final ObjectMapper objectMapper;

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
            sink.next(StepEvent.finished(st.stepId(), st.loop()));
            sink.complete();
            return;
        }

        doOneStep(st)
                .subscribe(tr -> {
                    tr.events().forEach(sink::next);
                    loop(tr.nextState(), sink, cancelled);
                }, err -> {
                    sink.next(StepEvent.error(st.stepId(), st.loop(), err.getMessage()));
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
                                if (allCalls.isEmpty()) {
                                    // 没有工具 → 直接续写
                                    return continueAnswer(withHash, ctx);
                                }

                                // 按目标拆分
                                List<ToolCall> serverCalls = allCalls.stream()
                                        .filter(tc -> "SERVER".equalsIgnoreCase(tc.execTarget()))
                                        .toList();
                                List<ToolCall> clientCalls = allCalls.stream()
                                        .filter(tc -> "CLIENT".equalsIgnoreCase(tc.execTarget()))
                                        .toList();

                                // 统一先发一个“决策事件”，便于前端展示/回放
                                StepEvent decisionEvent = decisionEvent(allCalls);

                                // 需要给前端的客户端调用事件
                                List<StepEvent> extraEvents = new ArrayList<>();
                                if (!clientCalls.isEmpty()) {
                                    extraEvents.add(StepEvent.step(Map.of(
                                            "type", "clientCalls",
                                            "calls", serializeCalls(clientCalls)
                                    )));
                                }

                                if (!serverCalls.isEmpty()) {
                                    // 生成 pending（SERVER）并做跨轮去重 + 本批去重
                                    List<ToolCall> pending = serverCalls.stream()
                                            .filter(tc -> {
                                                String k = tc.name() + "::" + tc.stableArgs(objectMapper);
                                                return !st.executedKeys().contains(k);  // 跨轮已执行过滤
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

                                    StepState next = withHash.withPending(pending).nextLoop();
                                    List<StepEvent> events = new ArrayList<>();
                                    events.add(decisionEvent);
                                    events.addAll(extraEvents);
                                    return Mono.just(StepTransition.of(next, events));
                                } else {
                                    // 只有 CLIENT 工具 → 下发给前端并结束本轮
                                    List<StepEvent> events = new ArrayList<>();
                                    events.add(decisionEvent);
                                    events.addAll(extraEvents);
                                    return Mono.just(StepTransition.of(withHash.finish(), events));
                                }
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
                        continuationService.appendToolResultsToMemory(st.stepId(), results).thenReturn(results)
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
