package com.example.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

@SuppressWarnings("unchecked")
public final class ToolPayloads {
    private ToolPayloads() {}

    /** 递归消掉 payload.payload，一路向里钻到 value/text/result 等可读层 */
    public static Object unwrap(Object p, ObjectMapper om) {
        if (p == null) return null;

        if (p instanceof String s) {
            // 尝试把 JSON 字符串解析成 Map 再继续向下
            try {
                Map<String, Object> m = om.readValue(s, Map.class);
                return unwrap(m, om);
            } catch (Exception ignore) {
                return s; // 纯文本字符串
            }
        }

        if (p instanceof Map<?, ?>) {
            Map<String, Object> m = toMap(p, om);
            // 常见：payload → 再向里钻
            if (m.containsKey("payload")) {
                return unwrap(m.get("payload"), om);
            }
            // 常见 value/text
            if (m.containsKey("value")) return m.get("value");
            if (m.containsKey("text"))  return m.get("text");
            // 有些返回把结果放 result
            if (m.containsKey("result")) return unwrap(m.get("result"), om);
            // 没有更内层可钻，原样返回 Map
            return m;
        }

        if (p instanceof Iterable<?> it) {
            // 如果是列表，尝试把每个元素递归解出来再决定
            List<Object> out = new ArrayList<>();
            for (Object x : it) out.add(unwrap(x, om));
            return out;
        }

        // 其他类型：原样返回
        return p;
    }

    /** data 可能是 Map / POJO / String，统一成 LinkedHashMap */
    public static Map<String, Object> toMap(Object data, ObjectMapper om) {
        if (data == null) return new LinkedHashMap<>();
        if (data instanceof Map<?, ?> m) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        if (data instanceof String s) {
            try { return om.readValue(s, Map.class); }
            catch (Exception ignore) {
                // 非 JSON 字符串，按 payload 文本兜底
                return new LinkedHashMap<>(Map.of("payload", s));
            }
        }
        try {
            // 其他对象：先序列化再解析，避免 convertValue 丢字段
            String json = om.writeValueAsString(data);
            return om.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>(Map.of("payload", String.valueOf(data)));
        }
    }

    public static String toJson(Object o, ObjectMapper om) {
        try { return om.writeValueAsString(o); }
        catch (Exception e) { return null; }
    }

    /** 提取权威参数字符串：优先 data.args（需是合法 JSON 字符串/Map），其次从 _executedKey 的 name::{"..."} 抠 */
    public static String extractArgsString(Map<String, Object> data, ObjectMapper om) {
        Object a = data.get("args");
        if (a instanceof String s && isJson(om, s)) {
            return s;
        }
        if (a instanceof Map<?, ?> m) {
            try { return om.writeValueAsString(m); } catch (Exception ignore) {}
        }
        Object ek = data.get("_executedKey");
        if (ek != null) {
            String s = String.valueOf(ek);
            int i = s.indexOf("::");
            if (i >= 0 && i + 2 < s.length()) {
                String maybeJson = s.substring(i + 2);
                if (isJson(om, maybeJson)) return maybeJson;
            }
        }
        return "{}";
    }

    /** 递归挖纯文本：优先返回 value/text/content/message/delta；再钻 payload/result；列表会智能渲染；兜底 JSON */
    public static String extractReadableText(Object data, ObjectMapper om) {
        return digReadable(data, om);
    }

    // ===== helpers =====

    private static String digReadable(Object node, ObjectMapper om) {
        if (node == null) return "";
        if (node instanceof String s) return s;

        if (node instanceof Map<?, ?>) {
            Map<String, Object> m = toMap(node, om);

            // 优先尝试把 payload 是 List<Map> 的情况渲染为 “标题 — URL” 多行文本（典型：search_web）
            Object payload = m.get("payload");
            if (payload instanceof List<?> list && looksLikeSearchList(list)) {
                return formatSearchList(list);
            }

            // 直层候选键
            for (String k : new String[]{"value", "text", "content", "message", "delta"}) {
                Object v = m.get(k);
                if (v instanceof String sv && !sv.isBlank()) return sv;
            }

            // 继续向里钻（payload/result）
            for (String ck : new String[]{"payload", "result"}) {
                if (m.containsKey(ck)) {
                    String inner = digReadable(m.get(ck), om);
                    if (!inner.isBlank()) return inner;
                }
            }

            // 兜底：转 JSON 文本
            String j = toJson(m, om);
            return j != null ? j : String.valueOf(m);
        }

        if (node instanceof Iterable<?> it) {
            List<?> list = toList(it);
            // 如果整个节点本身就是搜索结果列表，也友好渲染
            if (!list.isEmpty() && looksLikeSearchList(list)) {
                return formatSearchList(list);
            }
            // 否则逐项拼接
            StringBuilder sb = new StringBuilder();
            for (Object x : list) {
                String part = digReadable(x, om);
                if (!part.isBlank()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(part);
                }
            }
            return sb.toString();
        }

        return String.valueOf(node);
    }

    /** 判定 List<?> 是否像 [{title, url, snippet?}, ...] 的搜索结果 */
    private static boolean looksLikeSearchList(List<?> list) {
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> m)) return false;
        boolean hasUrl   = m.containsKey("url");
        boolean hasTitle = m.containsKey("title");
        // 允许只有 url 或只有 title，也尽量渲染
        return hasUrl || hasTitle;
    }

    /** 把搜索结果列表渲染成多行 “标题 — URL”（若无标题则仅 URL；可选追加 snippet） */
    private static String formatSearchList(List<?> list) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String title   = str(m.get("title"));
            String url     = str(m.get("url"));
            String snippet = str(m.get("snippet"));
            if (title == null && url == null) continue;

            if (sb.length() > 0) sb.append('\n');
            if (title != null && !title.isBlank()) {
                sb.append(title);
                if (url != null && !url.isBlank()) sb.append(" — ").append(url);
            } else {
                sb.append(url);
            }
            // 选配：如果有 snippet，可以拼一小段（防止过长，这里不拼或你按需加）
            // if (snippet != null && !snippet.isBlank()) sb.append("  · ").append(snippet);

            if (++count >= 10) break; // 防止过长
        }
        return sb.toString();
    }

    private static List<?> toList(Iterable<?> it) {
        List<Object> out = new ArrayList<>();
        for (Object x : it) out.add(x);
        return out;
    }

    private static boolean isJson(ObjectMapper om, String s) {
        if (s == null || s.isBlank()) return false;
        try { om.readTree(s); return true; } catch (Exception e) { return false; }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
