package com.example.api.dto;

import java.util.List;

public record ModelDecision(List<ToolCall> tools) {
    public static ModelDecision empty(){ return new ModelDecision(List.of()); }
}
