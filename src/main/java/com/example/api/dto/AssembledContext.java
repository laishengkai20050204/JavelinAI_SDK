package com.example.api.dto;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record AssembledContext(
        List<ChatMessage> messages,
        String hash,
        List<Map<String, Object>> structuredToolMessages, // 新增：OpenAI 规范的消息块
        List<Map<String, Object>> modelMessages            // ★ 新增：最终送模型的 messages
) { }
