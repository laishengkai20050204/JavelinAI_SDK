package com.example.api.dto;

import java.util.List;
import java.util.Map;

/** 最小请求 DTO；后续会逐步扩展，但本步只需要 q 就能跑 */
public record ChatRequest(
        String userId,
        String conversationId,
        String q,
        String toolChoice,                 // "auto" | "none"（本步先不用）
        String responseMode,               // "step-json-ndjson"（本步先不用）
        List<Map<String, Object>> tool_calls,   // 待执行工具（本步先不用）
        List<Map<String, Object>> clientTools,  // 客户端工具 schema（本步先不用）
        List<Map<String, Object>> clientResults // 客户端工具结果（本步先不用）
) {}
