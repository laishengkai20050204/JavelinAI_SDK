package com.example.api.dto;

import org.springframework.lang.Nullable;

import java.util.List;

public record ModelDecision(List<ToolCall> tools, @Nullable String assistantDraft) {
    public static ModelDecision empty(){ return new ModelDecision(List.of(), null); }
}
