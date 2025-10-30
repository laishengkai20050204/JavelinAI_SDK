package com.example.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MsgTrace {
    private static final ObjectMapper M = new ObjectMapper();

    public static String digest(List<Map<String,Object>> msgs) {
        // 只取 role + content + tool_calls(function.name+arguments) 计算 SHA256，避免无关字段抖动
        List<Map<String, Object>> slim = new ArrayList<>();
        for (Map<String, Object> m : msgs) {
            Map<String,Object> s = new LinkedHashMap<>();
            s.put("role", m.get("role"));
            s.put("content", m.get("content"));
            Object tcs = m.get("tool_calls");
            if (tcs instanceof List<?> list) {
                List<Map<String,Object>> t2 = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?,?> tc) {
                        Map<String,Object> f = (Map<String,Object>) tc.get("function");
                        String name = f == null ? null : String.valueOf(f.get("name"));
                        String args = f == null ? null : String.valueOf(f.get("arguments"));
                        Map<String,Object> tci = new LinkedHashMap<>();
                        tci.put("name", name);
                        tci.put("arguments", args);
                        t2.add(tci);
                    }
                }
                s.put("tool_calls", t2);
            }
            slim.add(s);
        }
        try {
            String json = M.writeValueAsString(slim);
            return sha256(json);
        } catch (Exception e) {
            return "ERR";
        }
    }

    public static String lastLine(List<Map<String,Object>> msgs) {
        if (msgs == null || msgs.isEmpty()) return "<empty>";
        Map<String,Object> last = msgs.get(msgs.size()-1);
        String role = String.valueOf(last.get("role"));
        String content = String.valueOf(last.get("content"));
        if (content != null && content.length() > 120) content = content.substring(0,120) + "...";
        return role + " :: " + content;
    }

    public static String sha256(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return "ERR"; }
    }
}
