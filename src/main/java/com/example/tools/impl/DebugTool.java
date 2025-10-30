package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.tools.AiTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
@AiToolComponent
@RequiredArgsConstructor
public class DebugTool implements AiTool {

    private final ObjectMapper mapper; // 你项目里已有 ObjectMapper Bean

    @Override
    public String name() {
        return "debug_tool";
    }

    @Override
    public String description() {
        return "Print 'debug tool' to log and return a short text payload.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        // 无参数工具
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) throws Exception {
        log.info("debug tool from tool");
        Map<String, Object> payload = Map.of(
                "type", "text",
                "value", "debug from tool"
        );
        return ToolResult.success(null, name(), false, Map.of("payload", payload));
    }
}

