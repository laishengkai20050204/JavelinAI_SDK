package com.example.audit;

import com.example.tools.support.JsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class AuditHasher {
    private AuditHasher() {}

    public static String sha256Hex(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** 把任意对象规范化（显式传 ObjectMapper） */
    public static String canonicalize(ObjectMapper om, Object payload) {
        JsonNode node = (payload instanceof JsonNode j) ? j
                : om.valueToTree(payload == null ? Map.of() : payload);
        // 第3个参数：可传要忽略的动态键（如 "system_fingerprint"），先留空集合
        return JsonCanonicalizer.canonicalize(om, node, Set.of());
    }

    /** 链式哈希：hash = SHA256((prevHash or "") + canonical) */
    public static Chain link(String prev, String canonical) {
        return new Chain(prev, sha256Hex((prev == null ? "" : prev) + canonical), canonical);
    }
    public record Chain(String prev, String hash, String canonical) {}

    // —— 构造审计负载（不做 JSON 处理）——
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

    public static Map<String,Object> buildToolAuditPayload(
            String userId, String convId, String stepId,
            String toolName, String argsHash,
            String dataHash, boolean reused, String status,
            String timestampIso, Long costMs) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("type","tool"); m.put("userId",userId); m.put("conversationId",convId);
        m.put("stepId",stepId); m.put("name",toolName);
        if (argsHash != null) m.put("args_hash", argsHash);
        if (dataHash != null) m.put("data_hash", dataHash);
        m.put("reused", reused); m.put("status", status);
        if (timestampIso!=null) m.put("ts", timestampIso);
        if (costMs!=null) m.put("cost_ms", costMs);
        return m;
    }

    /** 生成 data_hash：若是 {type:"artifact", sha256:"..."} 直接用，否则对 data 做 canonicalize 后 sha256 */
    public static String computeDataHash(ObjectMapper om, Object data) {
        if (data instanceof Map<?,?> map) {
            Object t = map.get("type"), s = map.get("sha256");
            if ("artifact".equals(t) && s instanceof String hs && hs.length() >= 32) {
                return hs;
            }
        }
        return sha256Hex(canonicalize(om, data == null ? Map.of() : data));
    }
}
