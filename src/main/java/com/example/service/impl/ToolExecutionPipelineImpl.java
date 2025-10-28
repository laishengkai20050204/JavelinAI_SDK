package com.example.service.impl;

import com.example.api.dto.ToolCall;
import com.example.api.dto.ToolResult;
import com.example.service.ToolExecutionPipeline;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ToolExecutionPipelineImpl implements ToolExecutionPipeline {

    private final Map<String, ToolResult> cache = new ConcurrentHashMap<>();

    @Override
    public Mono<ToolResult> tryReuse(String stepId, String tool, String fp) {
        ToolResult hit = cache.get(key(stepId, tool, fp));
        return hit == null ? Mono.empty() : Mono.just(hit);
    }

    @Override
    public Mono<ToolResult> execute(ToolCall call) {
        // 占位：模拟工具执行
        String msg = "hello from " + call.name() + (call.arguments().isEmpty() ? "" : (" args=" + call.arguments()));
        return Mono.just(ToolResult.success(call.id(), call.name(), false, msg));
    }

    @Override
    public Mono<Void> record(String stepId, String tool, String fp, ToolResult res) {
        cache.put(key(stepId, tool, fp), ToolResult.success(res.callId(), res.name(), true, res.data()));
        return Mono.empty();
    }

    private static String key(String stepId, String tool, String fp) { return stepId + "|" + tool + "|" + fp; }
}
