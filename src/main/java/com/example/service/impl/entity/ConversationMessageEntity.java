package com.example.service.impl.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationMessageEntity {

    private Long id;
    private String userId;
    private String conversationId;
    private String role;
    private String content;
    private String payload;
    private String messageTimestamp;
    private String state;
    private String stepId;
    private Integer seq;
    private LocalDateTime createdAt;
}
