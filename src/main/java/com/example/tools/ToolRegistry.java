package com.example.tools;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class ToolRegistry {
    private final Map<String, AiTool> tools = new LinkedHashMap<>();
    private final Map<String, AiTool> lookup = new LinkedHashMap<>();

    public ToolRegistry(List<AiTool> toolBeans) {
        for (AiTool tool : toolBeans) {
            tools.put(tool.name(), tool);
            lookup.put(tool.name(), tool);
            lookup.put(tool.name().toLowerCase(Locale.ROOT), tool);
        }
    }

    public Optional<AiTool> get(String name) {
        if (name == null) {
            return Optional.empty();
        }
        AiTool tool = lookup.get(name);
        if (tool != null) {
            return Optional.of(tool);
        }
        return Optional.ofNullable(lookup.get(name.toLowerCase(Locale.ROOT)));
    }

    public List<Map<String, Object>> openAiToolsSchema() {
        return tools.values().stream()
                .map(tool -> Map.<String, Object>of(
                        "type", "function",
                        "function", Map.of(
                                "name", tool.name(),
                                "description", tool.description(),
                                "parameters", tool.openAiJsonSchema()
                        )
                ))
                .toList();
    }
}
