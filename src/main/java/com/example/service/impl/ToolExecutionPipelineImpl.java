package com.example.service.impl;

import com.example.api.dto.ToolCall;
import com.example.config.AiProperties;
import com.example.config.EffectiveProps;
import com.example.service.ToolExecutionPipeline;
import com.example.tools.AiToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
//@RequiredArgsConstructor
@Slf4j
public class ToolExecutionPipelineImpl implements ToolExecutionPipeline {

    // 进程内重用（如需持久化可后续替换为 DB）
    private final Cache<String, ToolExecResult> cache;
    private final AiToolExecutor aiToolExecutor; // 复用你已有的执行器（已接 ToolRegistry / 去重账本等）
    private final ObjectMapper mapper;
    private final EffectiveProps effectiveProps; // ★ 新增

    public ToolExecutionPipelineImpl(AiToolExecutor executor, ObjectMapper mapper, AiProperties props,
                                     EffectiveProps effectiveProps) {
        this.aiToolExecutor = executor;
        this.mapper = mapper;
        this.effectiveProps = effectiveProps;

        long ttlMinutes =  props != null && props.getTools() != null && props.getTools().getCallStep() != null
                ? Math.max(1, props.getTools().getCallStep().getTtlMinutes())
                : 10; // 兜底 10 分钟

        long maxSize = props != null && props.getTools() != null && props.getTools().getCallStep() != null
                ? Math.max(64, props.getTools().getCallStep().getMaximumSize())
                : 1024; // 兜底 1024

        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
                .maximumSize(maxSize)
                //.recordStats() // 如果需要观测命中率，打开这行
                .build();
    }

    @Override
    public Mono<ToolExecResult> execute(ToolCall call) {
        return execute(call, null, null);
    }



    @Override
    public Mono<ToolExecResult> execute(ToolCall call, String userId, String conversationId) {
        return Mono.fromCallable(() -> {

            // ★★★ 新增：运行时禁用检查
            Map<String, Boolean> toggles = effectiveProps.toolToggles();
            if (toggles != null && !toggles.getOrDefault(call.name(), true)) {
                // 抛异常即可，SinglePathChatService.execPending() 里 onErrorResume 会转成 ToolResult.error(...)
                throw new IllegalStateException("DISABLED: tool disabled by runtime config");
            }

            // ---- 兜底空参 ----
            Map<String, Object> args = call.arguments() == null
                    ? java.util.Collections.emptyMap()
                    : call.arguments();

            String argumentsJson = mapper.writeValueAsString(args);
            var execCall = new AiToolExecutor.ToolCall(call.id(), call.name(), argumentsJson);

            // ★ 关键：把会话ID注入 fallbackArgs（优先于模型参数）
            Map<String, Object> fallbackArgs = new LinkedHashMap<>();
            if (userId != null && !userId.isBlank()) {
                fallbackArgs.put("userId", userId);
            }
            if (conversationId != null && !conversationId.isBlank()) {
                fallbackArgs.put("conversationId", conversationId);
            }
            // 若模型参数里已经带了，也无妨，AiToolExecutor 里有 PROTECTED_SCOPE_KEYS 覆盖逻辑
            // 仅用于排查：打印一下
            log.debug("[TOOL-PIPE] fallbackArgs={}", fallbackArgs);

            List<Map<String, Object>> toolMsgs =
                    aiToolExecutor.executeAll(List.of(execCall), fallbackArgs);

            Object data = java.util.Collections.emptyMap();
            if (!toolMsgs.isEmpty()) {
                Object content = toolMsgs.get(0).get("content");
                if (content instanceof String s && !s.isBlank()) {
                    try { data = mapper.readValue(s, Object.class); }
                    catch (Exception e) { log.debug("Parse tool content JSON failed, tool={}, err={}", call.name(), e.toString()); data = s; }
                } else if (content instanceof com.fasterxml.jackson.databind.JsonNode node) {
                    data = mapper.convertValue(node, Object.class);
                } else if (content != null) {
                    data = content;
                }
            }
            return new ToolExecResult(false, data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> record(String stepId, String tool, String fp, ToolExecResult res) {
        if (res == null) return Mono.empty();
        // 如果有 success 字段，建议只缓存成功：
        // if (!res.success()) return Mono.empty();
        // 存“原始执行结果”，不要提前把 reused=true
        cache.put(key(tool, fp), new ToolExecResult(false, res.data()));
        return Mono.empty();
    }

    @Override
    public Mono<ToolExecResult> tryReuse(String stepId, String tool, String fp) {
        ToolExecResult hit = cache.getIfPresent(key(tool, fp));
        if (hit == null) return Mono.empty();
        // 命中缓存时再标记为复用
        return Mono.just(new ToolExecResult(true, hit.data()));
    }

    private static String key(String tool, String fp) {
        return tool + "::" + fp;
    }

}
