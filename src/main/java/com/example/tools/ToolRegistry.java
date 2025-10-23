package com.example.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
@Slf4j
public class ToolRegistry {
    private final Map<String, AiTool> tools = new LinkedHashMap<>();
    private final Map<String, AiTool> lookup = new LinkedHashMap<>();

    public ToolRegistry(List<AiTool> toolBeans) {
        log.debug("Initializing ToolRegistry with {} tool bean(s)", toolBeans.size());
        for (AiTool tool : toolBeans) {
            tools.put(tool.name(), tool);
            lookup.put(tool.name(), tool);
            lookup.put(tool.name().toLowerCase(Locale.ROOT), tool);
            log.debug("Registered tool '{}' ({})", tool.name(), tool.getClass().getSimpleName());
        }
    }

    public Optional<AiTool> get(String name) {
        if (name == null) {
            log.debug("Tool lookup requested with null name");
            return Optional.empty();
        }
        log.debug("Lookup tool '{}'", name);
        AiTool tool = lookup.get(name);
        if (tool != null) {
            log.debug("Resolved tool '{}' via exact match", name);
            return Optional.of(tool);
        }
        AiTool normalized = lookup.get(name.toLowerCase(Locale.ROOT));
        if (normalized != null) {
            log.debug("Resolved tool '{}' via case-insensitive match", name);
        } else {
            log.debug("Tool '{}' not found in registry", name);
        }
        return Optional.ofNullable(normalized);
    }

    public List<Map<String, Object>> openAiToolsSchema() {
        log.debug("Generating OpenAI tool schema for {} tool(s)", tools.size());
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

    public List<Map<String, Object>> openAiServerToolsSchema() {
        log.debug("Generating OpenAI server tool schema for {} tool(s)", tools.size());
        return tools.values().stream()
                .map(tool -> Map.<String, Object>of(
                        "type", "function",
                        "function", Map.of(
                                "name", tool.name(),
                                "description", tool.description(),
                                "parameters", tool.openAiJsonSchema(),
                                "x-execTarget", "server"
                        )
                ))
                .toList();
    }

    public boolean isServerTool(String name) {
        return get(name).isPresent();
    }
}
