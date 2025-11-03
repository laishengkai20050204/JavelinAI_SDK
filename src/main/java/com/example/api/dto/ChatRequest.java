package com.example.api.dto;

import java.util.List;
import java.util.Map;

/** 最小请求 DTO；后续会逐步扩展，但本步只需要 q 就能跑 */
public record ChatRequest(
        String userId,
        String conversationId,
        String resumeStepId,
        String q,
        String toolChoice,                 // "auto" | "none"
        String responseMode,               // "step-json-ndjson"
        List<Map<String, Object>> tool_calls,   // 待执行工具
        List<Map<String, Object>> clientTools,  // 客户端工具 schema
        List<Map<String, Object>> clientResults // 客户端工具结果
) {}
