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
import com.example.util.ToolPayloads;
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
    private final Set<String> clientBatchIngested = ConcurrentHashMap.newKeySet();
    private final Set<String> userFinalWritten = ConcurrentHashMap.newKeySet();

    public Flux<StepEvent> run(ChatRequest req) {
        return Flux.create(sink -> {
            String stepId = (req != null && req.resumeStepId() != null && !req.resumeStepId().isBlank())
                    ? req.resumeStepId()
                    : "step-" + UUID.randomUUID();

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

        if (st.isWaitingClient()) {
            sink.next(StepEvent.step(Map.of(
                    "type", "status",
                    "phase", "CLIENT_WAIT",
                    "stepId", st.stepId()
            )));
            sink.complete();
            return;
        }


        if (st.finished() || guardrails.reachedMaxLoops(st)) {

            // ★ 先统一转正（覆盖所有无 SERVER 工具的结束路径）
            promoteDraftsToFinalSafe(st); // 只有真终结才转正

            // 然后发 finished
            sink.next(StepEvent.finished(st.stepId(), st.loop()));
            sink.complete();

            // 若有：decisionService.clearStep(st.stepId());
            stepStore.clear(st.stepId());
            userDraftSaved.remove("user:" + st.stepId());// ☆ 清理标记
            clientBatchIngested.removeIf(k -> k.startsWith(st.stepId() + "::"));

            return;
        }

        if (cancelled.get()) {
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

        if (st.req() != null) {
            stepStore.bind(st.stepId(), st.req().userId(), st.req().conversationId());
        }

        // === 0) 先落用户问句  ===
        Mono<Void> preUser = persistUserDraftIfAny(st);

        // === 0) 串行吸收客户端结果 ===
        Mono<Void> preIngest = ingestClientResultsOnce(st);

        return preUser.then(preIngest).then(Mono.defer(() -> {
            // 1) 有挂起的 SERVER 工具先执行
            if (st.hasPendingServerTools()) {
                return execPending(st);
            }

            return contextAssembler.assemble(st)
                    .flatMap(ctx -> {
                        StepState withHash = st.withContextHash(ctx.hash());
                        var req = st.req();
                        String toolChoice = (req == null || req.toolChoice() == null) ? "" : req.toolChoice();

                        // 2) toolChoice=none → 直接续写并结束
//                        if ("none".equalsIgnoreCase(toolChoice)) {
//                            return continueAnswer(withHash, ctx);
//                        }

                        // 3) 让模型做决策
                        return decisionService.decide(st, ctx)
                                .flatMap(decision -> {
                                    List<ToolCall> allCalls = (decision.tools() == null) ? List.of() : decision.tools();

                                    if (!allCalls.isEmpty()) {
                                        stepStore.savePlannedCalls(st.stepId(), allCalls);
                                    }

                                    // 没有任何工具 → 草稿/续写
                                    if (allCalls.isEmpty()) {
                                        String draft = org.springframework.util.StringUtils.hasText(decision.assistantDraft())
                                                ? decision.assistantDraft() : null;
                                        if (draft != null) {
                                            Map<String, Object> payload = new LinkedHashMap<>();
                                            payload.put("stepId", st.stepId());
                                            payload.put("type", "assistant");
                                            payload.put("text", draft);
                                            return continuationService.appendAssistantToMemory(st.stepId(), draft)
                                                    .thenReturn(StepTransition.of(withHash.finish(FinishReason.DONE), List.of(StepEvent.step(payload))));

                                        }
                                        return continueAnswer(withHash, ctx);
                                    }

                                    // 拆分 SERVER / CLIENT
                                    List<ToolCall> serverCalls = allCalls.stream()
                                            .filter(tc -> "SERVER".equalsIgnoreCase(tc.execTarget()))
                                            .collect(Collectors.toList());

                                    List<ToolCall> clientCalls = allCalls.stream()
                                            .filter(tc -> "CLIENT".equalsIgnoreCase(tc.execTarget()))
                                            .collect(Collectors.toList());

                                    // 先把客户端调用暂存，稍后（SERVER 执行完）再下发
                                    if (!clientCalls.isEmpty()) {
                                        stepStore.saveClientCalls(st.stepId(), clientCalls);
                                    }

                                    StepEvent decisionEvent = decisionEvent(allCalls);

                                    if (!serverCalls.isEmpty()) {
                                        // 去重后的待执行 SERVER 列表
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
                                            // 仅发决策，不发 clientCalls，先让 SERVER 执行
                                            List<StepEvent> events = List.of(decisionEvent);
                                            StepState next = withHash.withPending(pending);
                                            return Mono.just(StepTransition.of(next, events));
                                        } else {
                                            // SERVER 实际无活可干 → 若有 clientCalls，立刻下发并结束本轮等待前端
                                            List<ToolCall> deferred = stepStore.pollClientCalls(st.stepId());
                                            if (!deferred.isEmpty()) {
                                                List<StepEvent> ev = new ArrayList<>();
                                                ev.add(decisionEvent);
                                                ev.add(StepEvent.step(Map.of(
                                                        "type", "clientCalls",
                                                        "stepId", st.stepId(),
                                                        "calls", serializeCalls(deferred)
                                                )));
                                                return Mono.just(StepTransition.of(withHash.finish(FinishReason.WAIT_CLIENT), ev));
                                            }

                                            // 没有 clientCalls，则按草稿/续写兜底
                                            String draft = org.springframework.util.StringUtils.hasText(decision.assistantDraft())
                                                    ? decision.assistantDraft() : null;
                                            if (draft != null) {
                                                Map<String, Object> payload = new LinkedHashMap<>();
                                                payload.put("stepId", st.stepId());
                                                payload.put("type", "assistant");
                                                payload.put("text", draft);
                                                return continuationService.appendAssistantToMemory(st.stepId(), draft)
                                                        .thenReturn(StepTransition.of(withHash.finish(FinishReason.DONE), List.of(
                                                                decisionEvent,
                                                                StepEvent.step(payload)
                                                        )));
                                            } else {
                                                return continueAnswer(withHash, ctx).map(tr -> {
                                                    List<StepEvent> merged = new ArrayList<>();
                                                    merged.add(decisionEvent);
                                                    merged.addAll(tr.events());
                                                    return StepTransition.of(tr.nextState(), merged);
                                                });
                                            }
                                        }
                                    }

                                    // 没有 SERVER，只有 CLIENT → 直接下发 clientCalls 并结束本轮
                                    List<ToolCall> deferred = stepStore.pollClientCalls(st.stepId());
                                    if (!deferred.isEmpty()) {
                                        List<StepEvent> ev = new ArrayList<>();
                                        ev.add(decisionEvent);
                                        ev.add(StepEvent.step(Map.of(
                                                "type", "clientCalls",
                                                "stepId", st.stepId(),
                                                "calls", serializeCalls(deferred)
                                        )));
                                        return Mono.just(StepTransition.of(withHash.finish(FinishReason.WAIT_CLIENT), ev));
                                    }

                                    // 理论上到不了这里；兜底：草稿/续写
                                    String draft = org.springframework.util.StringUtils.hasText(decision.assistantDraft())
                                            ? decision.assistantDraft() : null;
                                    if (draft != null) {
                                        Map<String, Object> payload = new LinkedHashMap<>();
                                        payload.put("stepId", st.stepId());
                                        payload.put("type", "assistant");
                                        payload.put("text", draft);
                                        return continuationService.appendAssistantToMemory(st.stepId(), draft)
                                                .thenReturn(StepTransition.of(withHash.finish(FinishReason.DONE), List.of(
                                                        decisionEvent,
                                                        StepEvent.step(payload)
                                                )));
                                    }
                                    return continueAnswer(withHash, ctx).map(tr -> {
                                        List<StepEvent> merged = new ArrayList<>();
                                        merged.add(decisionEvent);
                                        merged.addAll(tr.events());
                                        return StepTransition.of(tr.nextState(), merged);
                                    });
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
//                                .then(Mono.fromRunnable(() -> {
//                                    var r = st.req();
//                                    if (r != null) {
//                                        try {
//                                            memoryService.promoteDraftsToFinal(r.userId(), r.conversationId(), st.stepId());
//                                        } catch (Exception e) {
//                                            org.slf4j.LoggerFactory.getLogger(getClass())
//                                                    .warn("[memory] promoteDraftsToFinal (on tools) failed: stepId={}, err={}", st.stepId(), e.toString());
//                                        }
//                                    }
//                                }))
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
                    List<StepEvent> ev = new ArrayList<>();
                    ev.add(toolsEvent);

                    // SERVER 执行完，看看有没有延迟下发的 clientCalls
                    List<ToolCall> deferred = stepStore.pollClientCalls(st.stepId());
                    if (!deferred.isEmpty()) {
                        ev.add(StepEvent.step(Map.of("type", "clientCalls", "calls", serializeCalls(deferred))));
                        // 关键：结束本轮，等客户端把 clientResults 回传（下一次请求的 preIngest 会吸收它们）
                        StepState next = st.withPending(List.of()).finish(FinishReason.WAIT_CLIENT);
                        return StepTransition.of(next, ev);
                    }

                    // 没有 clientCalls，则按原来的行为继续下一轮
                    StepState next = st.withPending(List.of()).nextLoop();
                    return StepTransition.of(next, ev);

                });
    }

    private Mono<ToolResult> execOneToolWithIdempotency(StepState st, ToolCall call) {
        final ToolCall callCtx = withContextIds(st, call);
        final String argsStable = callCtx.stableArgs(objectMapper);
        final String executedKey = callCtx.name() + "::" + argsStable;
        String fp = Fingerprint.sha256(call.name() + "|" + argsStable + "|" + safe(st.contextHash()));

        var req = st.req();
        String uid = (req == null ? null : req.userId());
        String cid = (req == null ? null : req.conversationId());

        return toolPipeline.tryReuse(st.stepId(), call.name(), fp)
                .switchIfEmpty(
                        toolPipeline.execute(call, uid, cid)
                                .flatMap(res -> toolPipeline.record(st.stepId(), callCtx.name(), fp, res).thenReturn(res))
                )
                .map(res -> {
                    // ★ 关键：把 res.data() 做“解包”再放进去，去掉 payload.payload 的双层
                    Object inner = ToolPayloads.unwrap(res.data(), objectMapper);

                    Map<String,Object> data = new LinkedHashMap<>();
                    data.put("payload", inner);       // 只保留一层 payload
                    data.put("_executedKey", executedKey);
                    data.put("args", argsStable);     // 权威参数（字符串）

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
                .map(text -> StepTransition.of(st.finish(FinishReason.DONE), List.of(
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
        String q = (r.q() == null ? "" : r.q().trim());
        if (q.isEmpty()) return Mono.empty();

        // ☆☆ 幂等：每个 stepId 只允许写一次
        String onceKey = "user:" + st.stepId();
        if (!userDraftSaved.add(onceKey)) {
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> {
            String userId = r.userId();
            String convId = r.conversationId();

            // 关键：user 固定写 seq=1，借助唯一键 + upsert 实现跨重启幂等
            int seqUser = 1;

            memoryService.upsertMessage(
                    userId, convId,
                    "user", q, /* payload */ null,
                    st.stepId(), seqUser, "DRAFT"
            );
//            memoryService.promoteDraftsToFinal(userId, convId, st.stepId());

            log.debug("[user] drafted & promoted (seq=1), step={}, len={}", st.stepId(), q.length());
        });
    }

    // 封装一下吸收逻辑
    private Mono<Void> ingestClientResultsOnce(StepState st) {
        var r = st.req();
        if (r == null) return Mono.empty();
        List<Map<String,Object>> cr = (r.clientResults() == null) ? List.of() : r.clientResults();
        if (cr.isEmpty()) return Mono.empty();

        String raw;
        try {
            raw = objectMapper.writeValueAsString(cr);  // 规范化序列化
        } catch (Exception e) {
            raw = String.valueOf(cr);
        }
        String batchKey = st.stepId() + "::" + com.example.util.Fingerprint.sha256(raw);

        if (!clientBatchIngested.add(batchKey)) {
            return Mono.empty(); // 本轮已吸收相同批次，跳过
        }
        return clientResultIngestor.ingest(st, cr)
                .onErrorResume(ex -> {
                    log.warn("[clientResults] ingest failed, step={}, err={}", st.stepId(), ex.toString());
                    return Mono.empty();
                });
    }



}
