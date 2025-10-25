package com.example.ai.tools;

import com.example.tools.AiTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ToolSchemas {
    private final ObjectMapper mapper;

    /** 仅参数层 JSON Schema（type=object）。 */
    public JsonNode toParameters(AiTool t) {
        return mapper.valueToTree(t.parametersSchema());
    }

    /** OpenAI/Mistral 常见外壳：{ "type":"function", "function":{ name, description, parameters } } */
    public ObjectNode toOpenAI(AiTool t) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "function");
        ObjectNode fn = root.putObject("function");
        fn.put("name", sanitize(t.name()));
        fn.put("description", t.description());
        fn.set("parameters", toParameters(t));
        return root;
    }

    /** Anthropic 工具外壳基本一致 */
    public ObjectNode toAnthropic(AiTool t) { return toOpenAI(t); }

    /** 视你的 Ollama 函数调用格式需求而定，示例保持轻壳。 */
    public ObjectNode toOllama(AiTool t) {
        ObjectNode fn = mapper.createObjectNode();
        fn.put("name", sanitize(t.name()));
        fn.put("description", t.description());
        fn.set("parameters", toParameters(t));
        return fn;
    }

    /** Vertex 常见是 functionDeclarations[...] + parameters(JSON Schema) 的形式，简化示例。 */
    public ObjectNode toVertex(AiTool t) {
        ObjectNode decl = mapper.createObjectNode();
        decl.put("name", sanitize(t.name()));
        decl.set("parameters", toParameters(t));
        return decl;
    }

    private String sanitize(String raw) {
        String s = raw.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_");
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
