package com.example.tools.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;

public class JsonCanonicalizer {
    public static JsonNode normalize(ObjectMapper mapper, JsonNode node, Set<String> ignore) {
        if (node == null || node.isNull()) return NullNode.getInstance();

        if (node.isObject()) {
            ObjectNode dst = mapper.createObjectNode();
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            Collections.sort(fields);
            for (String f : fields) {
                if (ignore.contains(f)) continue;
                dst.set(f, normalize(mapper, node.get(f), ignore));
            }
            return dst;
        }
        if (node.isArray()) {
            ArrayNode arr = mapper.createArrayNode();
            for (JsonNode it : node) arr.add(normalize(mapper, it, ignore));
            return arr;
        }
        return node; // 值类型
    }

    public static String canonicalize(ObjectMapper mapper, JsonNode node, Set<String> ignore) {
        JsonNode norm = normalize(mapper, node, ignore);
        try {
            return mapper.writeValueAsString(norm);
        } catch (Exception e) {
            return "null";
        }
    }

}
