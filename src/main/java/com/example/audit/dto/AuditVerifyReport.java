package com.example.audit.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AuditVerifyReport(
        String conversationId,
        int total,          // 被校验的条目数
        int ok,             // 通过的条目数
        int bad,            // 错误条目数
        Integer firstBadIdx,// 第一处错误的索引（按时间排序）
        AuditIssue firstBad,
        String latestHash,  // 最后一条的 hash（作为 conversation 的当前锚点）
        List<AuditIssue> issues
) {}

