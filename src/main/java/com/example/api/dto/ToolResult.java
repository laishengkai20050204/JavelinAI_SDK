package com.example.api.dto;

import java.util.Map;

public record ToolResult(
        String callId,
        String name,
        boolean reused,
        String status, // "SUCCESS" | "ERROR"
        Object data    // result or error message
) {
    public static ToolResult success(String callId, String name, boolean reused, Object data) {
        return new ToolResult(callId, name, reused, "SUCCESS", data);
    }
    public static ToolResult error(String callId, String name, String error) {
        return new ToolResult(callId, name, false, "ERROR", Map.of("message", error));
    }
}
