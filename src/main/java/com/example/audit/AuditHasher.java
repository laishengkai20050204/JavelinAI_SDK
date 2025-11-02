package com.example.audit;

import com.example.tools.support.JsonCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AuditHasher {
    private AuditHasher() {}

    public static String canonicalize(Object payload) {
        return JsonCanonicalizer.canonicalize(payload == null ? Map.of() : payload);
    }
    public static String sha256Hex(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    public record Chain(String prev, String hash, String canonical) {}
    public static Chain link(String prev, String canonical) {
        return new Chain(prev, sha256Hex((prev == null ? "" : prev) + canonical), canonical);
    }

    // 规范化负载（消息）
    public static Map<String,Object> buildMessageAuditPayload(
            String userId, String convId, String stepId,
            String role, String name, String content,
            String timestampIso, Integer seq, String model) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("type","message"); m.put("userId",userId); m.put("conversationId",convId);
        m.put("stepId",stepId);  m.put("role",role);
        if (name!=null) m.put("name",name);
        if (content!=null) m.put("content",content);
        if (seq!=null) m.put("seq",seq);
        if (timestampIso!=null) m.put("ts",timestampIso);
        if (model!=null) m.put("model",model);
        return m;
    }

    // 规范化负载（工具）
    public static Map<String,Object> buildToolAuditPayload(
            String userId, String convId, String stepId,
            String toolName, String argsHash, Object data,
            boolean reused, String status, String timestampIso, Long costMs) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("type","tool"); m.put("userId",userId); m.put("conversationId",convId);
        m.put("stepId",stepId); m.put("name",toolName);
        if (argsHash!=null) m.put("args_hash",argsHash);
        m.put("data_hash", safeDataHash(data));
        m.put("reused", reused); m.put("status", status);
        if (timestampIso!=null) m.put("ts", timestampIso);
        if (costMs!=null) m.put("cost_ms", costMs);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static String safeDataHash(Object data) {
        if (data instanceof Map<?,?> map) {
            Object t = map.get("type"), s = map.get("sha256");
            if ("artifact".equals(t) && s instanceof String hs && hs.length()>=32) return hs;
        }
        return sha256Hex(canonicalize(data));
    }
}
