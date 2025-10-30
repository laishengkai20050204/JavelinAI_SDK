package com.example.util;

import com.example.api.dto.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public final class ClientResultNormalizer {
    private ClientResultNormalizer() {}

    @SuppressWarnings("unchecked")
    public static List<ToolResult> normalize(List<Map<String, Object>> items, ObjectMapper om) {
        List<ToolResult> out = new ArrayList<>();
        if (items == null) return out;

        for (Map<String, Object> it : items) {
            // callId
            String callId = str(it.getOrDefault("callId", it.get("tool_call_id")));
            if (callId == null || callId.isBlank()) {
                callId = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            }

            // name
            String name = str(it.get("name"));
            if (name == null) {
                Object fn = it.get("function");
                if (fn instanceof Map<?, ?> m) name = str(m.get("name"));
            }
            if (name == null) name = "client_tool";

            // arguments → JSON 字符串
            String argsJson = "{}";
            Object argsObj = it.get("arguments");
            try {
                if (argsObj instanceof String s) {
                    // 若是 JSON 字符串，校验
                    om.readTree(s);
                    argsJson = s;
                } else if (argsObj instanceof Map<?, ?> m) {
                    argsJson = om.writeValueAsString(m);
                }
            } catch (Exception ignore) {}

            // payload：优先 data；其次 payload/content；最后空
            Object payload = it.get("data");
            if (payload == null) payload = it.get("payload");
            if (payload == null && "tool".equalsIgnoreCase(str(it.get("role")))) {
                payload = it.get("content");
            }
            if (payload == null) payload = "";
            // 扁平化：消掉 payload.payload
            payload = ToolPayloads.unwrap(payload, om);

            // 组 data：尽量贴近服务端工具结果
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("payload", payload);
            data.put("name", name);
            data.put("tool_call_id", callId);
            data.put("args", argsJson);
            data.put("_executedKey", name + "::" + argsJson);

            boolean reused = Boolean.TRUE.equals(it.get("reused"));
            String status = str(it.getOrDefault("status", "SUCCESS"));

            ToolResult tr = "ERROR".equalsIgnoreCase(status)
                    ? ToolResult.error(callId, name, str(it.getOrDefault("message", "client error")))
                    : ToolResult.success(callId, name, reused, data);

            out.add(tr);
        }

        return out;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
