package com.example.service.impl.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ToolResultDTO {
    private String tool_call_id;
    private String name;
    private String content;
}
