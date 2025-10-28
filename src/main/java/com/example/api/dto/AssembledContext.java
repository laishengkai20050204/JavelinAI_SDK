package com.example.api.dto;

import java.util.List;

public record AssembledContext(
        List<ChatMessage> messages, // 简化：这步先不接数据库
        String hash
) {}
