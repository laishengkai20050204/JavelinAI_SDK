package com.example.service.impl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ToolCall {
    private final String id;
    private final String name;
    private final String argumentsJson;
}
