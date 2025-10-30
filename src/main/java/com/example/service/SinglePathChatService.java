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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SinglePathChatService {

    private final ContextAssembler contextAssembler;
    private final DecisionService decisionService;
    private final ToolExecutionPipeline toolPipeline;
    private final ContinuationService continuationService;
    private final ConversationMemoryService memoryService;
    private final Guardrails guardrails;
    private final ObjectMapper objectMapper;
    private final StepContextStore stepStore;
    private final ClientResultIngestor clientResultIngestor;
    private final Set<String> userDraftSaved = ConcurrentHashMap.newKeySet();


    public Flux<StepEvent> run(ChatRequest req) {
        return Flux.create(sink -> {
            String stepId = "step-" + UUID.randomUUID();
            StepState init = StepState.init(req, stepId);

            if (req != null) {
                stepStore.bind(stepId, req.userId(), req.conversationId());
            }

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

            // ★ 先统一转正（覆盖所有无 SERVER 工具的结束路径）
            promoteDraftsToFinalSafe(st);

            // 然后发 finished
            sink.next(StepEvent.finished(st.stepId(), st.loop()));
            sink.complete();

            // 最后清缓存、解绑
            contextAssembler.clearPerStepCaches(st.stepId());
            // 若有：decisionService.clearStep(st.stepId());
            stepStore.clear(st.stepId());
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

        if (st.req() != null) {
            stepStore.bind(st.stepId(), st.req().userId(), st.req().conversationId());
        }

        // === 0) 串行吸收客户端结果 ===
        Mono<Void> preIngest = Mono
                .defer(() -> {
                    var r = st.req();
                    List<Map<String,Object>> cr = (r == null ? null : r.clientResults());
                    return clientResultIngestor.ingest(st, (cr == null) ? List.of() : cr);
                })
                .onErrorResume(ex -> {
                    log.warn("[clientResults] ingest failed, step={}, err={}", st.stepId(), ex.toString());
                    return Mono.empty();
                });

        return preIngest.then(Mono.defer(() -> {
            // 1) pending（只 SERVER）
            if (st.hasPendingServerTools()) {
                return execPending(st);
            }

            return  contextAssembler.assemble(st)
                    .flatMap(ctx -> {
                        StepState withHash = st.withContextHash(ctx.hash());
                        var req = st.req();
                        String toolChoice = (req == null || req.toolChoice() == null) ? "" : req.toolChoice();

                        // 3) toolChoice=none → 直接续写并结束
                        if ("none".equalsIgnoreCase(toolChoice)) {
                            return continueAnswer(withHash, ctx);
                        }

                        // 4) 模型决策（以下保持你的原代码不变）
                        return decisionService.decide(st, ctx)
                                .flatMap(decision -> {
                                    List<ToolCall> allCalls = decision.tools() == null ? List.of() : decision.tools();

                                    if (!allCalls.isEmpty()) {
                                        stepStore.savePlannedCalls(st.stepId(), allCalls);
                                    }

                                    if (allCalls.isEmpty()) {
                                        String draft = decision.assistantDraft();
                                        if (org.springframework.util.StringUtils.hasText(draft)) {
                                            Map<String, Object> payload = new LinkedHashMap<>();
                                            payload.put("stepId", st.stepId());
                                            payload.put("type", "assistant");
                                            payload.put("text", draft);
                                            return continuationService.appendAssistantToMemory(st.stepId(), draft)
                                                    .thenReturn(StepTransition.of(withHash.finish(), List.of(StepEvent.step(payload))));
                                        }
                                        return continueAnswer(withHash, ctx);
                                    }

                                    List<ToolCall> serverCalls = allCalls.stream()
                                            .filter(tc -> "SERVER".equalsIgnoreCase(tc.execTarget()))
                                            .collect(Collectors.toList());
                                    List<ToolCall> clientCalls = allCalls.stream()
                                            .filter(tc -> "CLIENT".equalsIgnoreCase(tc.execTarget()))
                                            .collect(Collectors.toList());

                                    StepEvent decisionEvent = decisionEvent(allCalls);

                                    List<StepEvent> extraEvents = new ArrayList<>();
                                    if (!clientCalls.isEmpty()) {
                                        Map<String, Object> m = new LinkedHashMap<>();
                                        m.put("type", "clientCalls");
                                        m.put("calls", serializeCalls(clientCalls));
                                        extraEvents.add(StepEvent.step(m));
                                    }

                                    if (!serverCalls.isEmpty()) {
                                        List<StepEvent> events = new ArrayList<>();
                                        events.add(decisionEvent);
                                        events.addAll(extraEvents);

                                        List<ToolCall> pending = serverCalls.stream()
                                                .filter(tc -> {
                                                    String k = tc.name() + "::" + tc.stableArgs(objectMapper);
                                                    return !st.executedKeys().contains(k);
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
                                            StepState next = withHash.withPending(pending);
                                            return Mono.just(StepTransition.of(next, events));
                                        } else {
                                            String draft = decision.assistantDraft();
                                            if (org.springframework.util.StringUtils.hasText(draft)) {
                                                Map<String, Object> payload = new LinkedHashMap<>();
                                                payload.put("stepId", st.stepId());
                                                payload.put("type", "assistant");
                                                payload.put("text", draft);
                                                events.add(StepEvent.step(payload));
                                                return continuationService.appendAssistantToMemory(st.stepId(), draft)
                                                        .thenReturn(StepTransition.of(withHash.finish(), events));
                                            } else {
                                                return continueAnswer(withHash, ctx).map(tr -> {
                                                    List<StepEvent> merged = new ArrayList<>(events);
                                                    merged.addAll(tr.events());
                                                    return StepTransition.of(tr.nextState(), merged);
                                                });
                                            }
                                        }
                                    }

                                    // 只有 CLIENT 工具（结束由你现有逻辑处理）
                                    List<StepEvent> events = new ArrayList<>();
                                    events.add(decisionEvent);
                                    events.addAll(extraEvents);
                                    return Mono.just(StepTransition.of(withHash.finish(), events));
                                });
                    });
        }));

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
        // ★ 注入 userId / conversationId，确保 AiToolExecutor 能持久化与复用
        final ToolCall callCtx = withContextIds(st, call); // ★ 不要改形参，另起 final 变量
        final String argsStable = callCtx.stableArgs(objectMapper);
        final String executedKey = callCtx.name() + "::" + argsStable;
        String fp = Fingerprint.sha256(call.name() + "|" + argsStable + "|" + safe(st.contextHash()));

        var req = st.req(); // 你已有
        String uid = (req == null ? null : req.userId());
        String cid = (req == null ? null : req.conversationId());

        return toolPipeline.tryReuse(st.stepId(), call.name(), fp)
                .switchIfEmpty(
                        toolPipeline.execute(call, uid, cid)
                                .flatMap(res -> toolPipeline.record(st.stepId(), callCtx.name(), fp, res).thenReturn(res))
                )
                .map(res -> {
                    Map<String,Object> data = new LinkedHashMap<>();
                    data.put("payload", res.data());     // 原始返回对象（Map/String/…）
                    data.put("_executedKey", executedKey);
                    data.put("args", argsStable);        // ★ 带上权威参数（字符串）
                    return ToolResult.success(
                            callCtx.id(), callCtx.name(), res.reused(), data
                    );
                });
    }

    private ToolCall withContextIds(com.example.api.dto.StepState st,
                                                        com.example.api.dto.ToolCall call) {
        var req = st.req();
        if (req == null) {
            return call;
        }
        Map<String, Object> args = new java.util.LinkedHashMap<>(
                call.arguments() == null ? java.util.Collections.emptyMap() : call.arguments()
        );
        // 仅当缺失时补齐，避免用户显式传入被覆盖
        args.putIfAbsent("userId", req.userId());
        args.putIfAbsent("conversationId", req.conversationId());
        return com.example.api.dto.ToolCall.of(call.id(), call.name(), args, call.execTarget());
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

    private void promoteDraftsToFinalSafe(StepState st) {
        var r = st.req();
        if (r == null) return;
        try {
            memoryService.promoteDraftsToFinal(r.userId(), r.conversationId(), st.stepId());
        } catch (Exception e) {
            log.warn("[memory] promoteDraftsToFinal failed: stepId={}, err={}", st.stepId(), e.toString());
        }
    }

    private Mono<Void> persistUserDraftIfAny(StepState st) {
        var r = st.req();
        if (r == null) return Mono.empty();

        // 幂等：每个 step 只做一次
        if (!userDraftSaved.add(st.stepId())) return Mono.empty();

        String q = (r.q() == null ? "" : r.q().trim());
        if (q.isEmpty()) return Mono.empty();

        return Mono.fromRunnable(() -> {
            String userId = r.userId();
            String convId = r.conversationId();

            // 让 user 成为本 step 的第一条（在 clientResults 之前调用此方法即可）
            int seq = Optional.ofNullable(
                    memoryService.findMaxSeq(userId, convId, st.stepId())
            ).orElse(0) + 1;

            memoryService.upsertMessage(
                    userId, convId,
                    "user", q, /* payload */ null,
                    st.stepId(), seq, "DRAFT"
            );

            // 立刻转正，后续 ContextAssembler(select FINAL) 才能读到
            memoryService.promoteDraftsToFinal(userId, convId, st.stepId());

            log.debug("[user] drafted & promoted, step={}, seq={}, len={}",
                    st.stepId(), seq, q.length());
        });
    }


}
