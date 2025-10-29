package com.example.service;

import com.example.api.dto.ToolCall;
import reactor.core.publisher.Mono;

public interface ToolExecutionPipeline {

    /** 如果历史已执行过相同指纹的调用，返回重用结果；否则 empty */
    Mono<ToolExecResult> tryReuse(String stepId, String toolName, String fingerprint);

    /** 真正执行一次工具调用 */
    Mono<ToolExecResult> execute(ToolCall call);
    Mono<ToolExecResult> execute(ToolCall call, String userId, String conversationId);

    /** 把执行结果按指纹记录下来，便于后续重用 */
    Mono<Void> record(String stepId, String toolName, String fingerprint, ToolExecResult res);

    /** 简单结果对象：是否重用 + 业务负载 */
    record ToolExecResult(boolean reused, Object data) {}
}
