package com.example.mapper.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ToolExecutionRecord {
    private Long id;
    private String userId;
    private String conversationId;
    private String toolName;
    private String argsHash;
    private String status; // "SUCCESS" / "FAILURE"
    private String argsJson;   // 存字符串(JSON)，Mapper里写/读
    private String resultJson; // 同上
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expiresAt;
}