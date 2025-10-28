package com.example.service.impl;

import com.example.api.dto.AssembledContext;
import com.example.api.dto.ToolResult;
import com.example.service.ContinuationService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class ContinuationServiceImpl implements ContinuationService {
    @Override public Mono<Void> appendToolResultsToMemory(String stepId, List<ToolResult> results) { return Mono.empty(); }
    @Override public Mono<String> generateAssistant(AssembledContext ctx) {
        // 占位：直接返回一句总结
        return Mono.just("【占位】本轮上下文哈希: " + ctx.hash());
    }
    @Override public Mono<Void> appendAssistantToMemory(String stepId, String text) { return Mono.empty(); }
}
