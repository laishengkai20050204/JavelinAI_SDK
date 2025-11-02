package com.example.audit;

import com.example.tools.support.JsonCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AuditHasher {
    private AuditHasher() {}

    public static String canonicalize(Object payload) {
        return JsonCanonicalizer.canonicalize(payload);
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 计算链式哈希：hash = SHA256((prevHash or "") + canonical) */
    public static Chain link(String prevHash, String canonical) {
        String base = (prevHash == null ? "" : prevHash) + canonical;
        return new Chain(prevHash, sha256Hex(base), canonical);
    }

    public record Chain(String prev, String hash, String canonical) {}

    /** 消息事件的规范化负载 */
    public static Map<String, Object> buildMessageAuditPayload(
            String userId, String convId, String stepId,
            String role, String nameOrNull, String content,
            OffsetDateTime ts, Integer seq, String modelOrNull) {

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "message");
        m.put("userId", userId);
        m.put("conversationId", convId);
        m.put("stepId", stepId);
        m.put("role", role);
        if (nameOrNull != null) m.put("name", nameOrNull);
        m.put("content", content);
        if (seq != null) m.put("seq", seq);
        m.put("ts", ts.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        if (modelOrNull != null) m.put("model", modelOrNull);
        return m;
    }

    /** 工具事件的规范化负载（利用已存在的 args_hash；data_hash 从 data 计算或复用 artifact 的 sha256） */
    public static Map<String, Object> buildToolAuditPayload(
            String userId, String convId, String stepId,
            String toolName, String argsHash,
            Object data, boolean reused, String status,
            OffsetDateTime ts, Long costMs) {

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "tool");
        m.put("userId", userId);
        m.put("conversationId", convId);
        m.put("stepId", stepId);
        m.put("name", toolName);
        if (argsHash != null) m.put("args_hash", argsHash);
        m.put("data_hash", safeDataHash(data));
        m.put("status", status);
        m.put("reused", reused);
        m.put("ts", ts.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        if (costMs != null) m.put("cost_ms", costMs);
        return m;
    }

    /** data_hash 策略：若 data 是 {type:"artifact", sha256:"..."} 则直接用；否则对 canonical(data) 做 sha256 */
    @SuppressWarnings("unchecked")
    public static String safeDataHash(Object data) {
        if (data instanceof Map<?,?> map) {
            Object type = map.get("type");
            Object sha = map.get("sha256");
            if ("artifact".equals(type) && sha instanceof String s && s.length() >= 32) {
                return s;
            }
        }
        return sha256Hex(canonicalize(data == null ? Map.of() : data));
    }
}
