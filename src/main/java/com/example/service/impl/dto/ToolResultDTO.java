package com.example.service.impl.dto;


import com.fasterxml.jackson.databind.ObjectMapper;
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

    // 统一取字符串；避免 NPE
    public String contentAsString() {
        return content == null ? "" : content;
    }
}
