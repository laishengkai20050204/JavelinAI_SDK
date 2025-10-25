package com.example.tools;

import java.util.Map;

public interface AiTool {
    String name();

    String description();

    Map<String, Object> parametersSchema();

    ToolResult execute(Map<String, Object> args) throws Exception;
}
