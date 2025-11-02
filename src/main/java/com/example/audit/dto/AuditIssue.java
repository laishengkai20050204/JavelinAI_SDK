package com.example.audit.dto;

import java.time.LocalDateTime;

public record AuditIssue(
        int index,                 // 在时间线中的序号
        Long id,                   // message id
        LocalDateTime createdAt,   // 微秒时间戳
        String role,               // user/assistant/tool
        String stepId,
        Integer seq,
        String storedPrev,         // DB 中的 prev_hash
        String expectedPrev,       // 计算得到的 prev
        String storedHash,         // DB 中的 hash
        String expectedHash,       // 计算得到的 hash(prev+canonical)
        String reason              // 失败原因
) {}
