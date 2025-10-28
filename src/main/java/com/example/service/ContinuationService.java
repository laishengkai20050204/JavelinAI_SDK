package com.example.service;

import com.example.api.dto.AssembledContext;
import com.example.api.dto.ToolResult;
import reactor.core.publisher.Mono;

import java.util.List;

public interface ContinuationService {
    Mono<Void> appendToolResultsToMemory(String stepId, List<ToolResult> results);
    Mono<String> generateAssistant(AssembledContext ctx);
    Mono<Void> appendAssistantToMemory(String stepId, String text);
}
