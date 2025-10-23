package com.example.service.impl.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ToolCallDTO {
    private String id;
    private String name;
    private Map<String, Object> arguments;
    private ExecTarget execTarget;
}
