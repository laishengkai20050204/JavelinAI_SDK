package com.example.service.impl.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class V2StepNdjsonRequest {
    private String userId;
    private String conversationId;
    private String q;
    private String toolChoice;
    private String responseMode;
    private List<ToolResultDTO> clientResults;
    private List<Map<String, Object>> clientTools;
}
