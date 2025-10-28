package com.example.api.dto;

import com.example.util.StableJson;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments,
        String execTarget // "SERVER" | "CLIENT"
) {
    public static ToolCall of(String id, String name, Map<String,Object> args, String execTarget) {
        return new ToolCall(id, name, args == null ? Map.of() : args, execTarget == null ? "SERVER" : execTarget);
    }

    @SuppressWarnings("unchecked")
    public static List<ToolCall> normalizeServerCalls(List<Map<String,Object>> raw) {
        if (raw == null) return List.of();
        return raw.stream().map(m -> {
                    String id = Optional.ofNullable((String) m.get("id")).orElse(UUID.randomUUID().toString());
                    String name = String.valueOf(m.get("name"));
                    Map<String,Object> args = (Map<String, Object>) m.getOrDefault("arguments", Map.of());
                    String target = Optional.ofNullable((String)m.get("execTarget")).orElse("SERVER");
                    return ToolCall.of(id, name, args, target);
                }).filter(tc -> "SERVER".equalsIgnoreCase(tc.execTarget()))
                .collect(Collectors.toList());
    }

    public String stableArgs(ObjectMapper om) {
        try {
            return StableJson.stringify(arguments, om);
        } catch (Exception e) {
            return arguments.toString();
        }
    }
}
