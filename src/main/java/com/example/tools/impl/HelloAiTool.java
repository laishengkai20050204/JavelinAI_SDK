package com.example.tools.impl;

import com.example.tools.AiTool;
import com.example.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class HelloAiTool implements AiTool {

    private final ObjectMapper mapper; // 你项目里已有 ObjectMapper Bean

    @Override
    public String name() {
        return "hello_ai";
    }

    @Override
    public String description() {
        return "Print 'hello ai' to log and return a short text payload.";
    }

    @Override
    public Map<String, Object> openAiJsonSchema() {
        // 无参数工具
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) throws Exception {
        log.info("hello ai");
        // 随便返回点内容：可以是纯文本，也可以是JSON字符串。执行器会原样放进 tool 的 content。
        String payload = mapper.writeValueAsString(Map.of(
                "type", "text",
                "value", "hello ai from tool"
        ));
        return new ToolResult(name(), payload);
    }
}
