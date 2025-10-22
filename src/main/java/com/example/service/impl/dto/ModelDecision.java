package com.example.service.impl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

@Getter
@AllArgsConstructor
public class ModelDecision {
    private final List<ToolCall> toolCalls;
    private final boolean wantsFinal;

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public static ModelDecision finalOnly() {
        return new ModelDecision(Collections.emptyList(), true);
    }
}
