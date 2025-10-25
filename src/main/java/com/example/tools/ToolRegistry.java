package com.example.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
@Slf4j
public class ToolRegistry {
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    // 只在构造期写，之后只读：线程安全
    private final Map<String, AiTool> tools = new LinkedHashMap<>();
    private final Map<String, AiTool> lookup = new LinkedHashMap<>();

    // 可选：把导出结构做成只读缓存（构造完成后不再变更）
    private final List<Map<String, Object>> openAiToolsSchemaCached;
    private final List<Map<String, Object>> openAiServerToolsSchemaCached;

    public ToolRegistry(List<AiTool> toolBeans) {
        log.debug("Initializing ToolRegistry with {} tool bean(s)", toolBeans.size());

        // 1) 装载 + 校验
        Set<String> seenLower = new HashSet<>();
        for (AiTool tool : toolBeans) {
            String name = Objects.requireNonNull(tool.name(), "tool.name() must not be null").trim();

            // 名称基本校验
            if (name.isEmpty() || !NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid tool name: '" + name + "'. " +
                        "Expected pattern " + NAME_PATTERN.pattern());
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!seenLower.add(lower)) {
                // 明确禁止仅大小写不同的重名，避免 lookup 覆盖
                throw new IllegalStateException("Duplicate tool name (case-insensitive): '" + name + "'");
            }

            // Schema 兜底校验（参数层必须是 object）
            Map<String, Object> schema = Objects.requireNonNull(
                    tool.parametersSchema(), () -> "parametersSchema() is null for tool " + name);
            Object type = schema.get("type");
            if (!"object".equals(String.valueOf(type))) {
                throw new IllegalStateException("Tool '" + name +
                        "' parametersSchema().type must be 'object', got: " + type);
            }

            tools.put(name, tool);
            lookup.put(name, tool);
            lookup.put(lower, tool);
            log.debug("Registered tool '{}' ({})", name, tool.getClass().getSimpleName());
        }

        // 2) 可选缓存：一次生成，后续复用（如果你未来支持动态增删工具，再改成懒加载或失效重建）
        this.openAiToolsSchemaCached = Collections.unmodifiableList(buildOpenAiToolsSchema(false));
        this.openAiServerToolsSchemaCached = Collections.unmodifiableList(buildOpenAiToolsSchema(true));
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

    /** 方便在服务层直接抛错 */
    public AiTool require(String name) {
        return get(name).orElseThrow(() -> new NoSuchElementException("Tool not found: " + name));
    }

    /** OpenAI tools（客户端/模型侧） */
    public List<Map<String, Object>> openAiToolsSchema() {
        log.debug("Returning cached OpenAI tool schema for {} tool(s)", tools.size());
        return openAiToolsSchemaCached;
    }

    /** OpenAI server-tools（附带 x-execTarget，告诉模型“走服务端执行”） */
    public List<Map<String, Object>> openAiServerToolsSchema() {
        log.debug("Returning cached OpenAI server tool schema for {} tool(s)", tools.size());
        return openAiServerToolsSchemaCached;
    }

    /** 仅判断是否在注册表中（你原语义就是“是否是服务端可执行的工具”） */
    public boolean isServerTool(String name) {
        return get(name).isPresent();
    }

    public List<AiTool> allTools() {
        return List.copyOf(tools.values());
    }

    // ---------- 私有封装：集中构建 OpenAI 外壳，避免重复样板 ----------
    private List<Map<String, Object>> buildOpenAiToolsSchema(boolean serverFlag) {
        return tools.values().stream()
                .map(tool -> toOpenAiFunctionMap(tool, serverFlag))
                .toList();
    }

    private Map<String, Object> toOpenAiFunctionMap(AiTool tool, boolean serverFlag) {
        // 注意：name 不在这里做 sanitize，要求工具名本身合法且稳定
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.put("parameters", tool.parametersSchema());
        if (serverFlag) {
            function.put("x-execTarget", "server");
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", "function");
        wrapper.put("function", function);
        return wrapper;
    }
}
