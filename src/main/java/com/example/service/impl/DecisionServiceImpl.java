package com.example.service.impl;

import com.example.api.dto.*;
import com.example.service.DecisionService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

//@Service
public class DecisionServiceImpl implements DecisionService {
    @Override
    public Mono<ModelDecision> decide(StepState st, AssembledContext ctx) {
        // 占位策略：
        // toolChoice=none → 不调用工具
        if ("none".equalsIgnoreCase(st.req().toolChoice())) {
            return Mono.just(ModelDecision.empty());
        }
        // 简单关键字触发：含“调用hello_ai” → 产生一个 hello_ai 工具
        String q = st.req().q() == null ? "" : st.req().q();
        if (q.contains("调用hello_ai")) {
            ToolCall c = ToolCall.of("call_" + st.loop(), "hello_ai", Map.of("echo", q), "SERVER");
            return Mono.just(new ModelDecision(List.of(c), null));
        }
        // 否则不调用工具
        return Mono.just(ModelDecision.empty());
    }
}
