package com.example.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class HistoryNormalizer {

    private final ObjectMapper mapper;

    /**
     * @param rows 来自 DB 的原始消息行（含 role/content/payload/…）
     * @param openAiCompatible 当前模型是否走 OpenAI 兼容协议（是：展开为 tool_calls；否：折叠为摘要）
     */
    public List<Map<String, Object>> normalize(List<Map<String, Object>> rows, boolean openAiCompatible) {
        return openAiCompatible ? toOpenAi(rows) : toOllama(rows);
    }

    /** 把 DB 里的三件套还原为 OpenAI 规范：assistant{tool_calls:[…], content:""} / tool{tool_call_id,name,content} */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toOpenAi(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());

        record Pending(String id, String name) {}
        ArrayDeque<Pending> pending = new ArrayDeque<>();

        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            String role = Objects.toString(row.get("role"), "");
            String content = (String) row.get("content");
            String payloadStr = (row.get("payload") instanceof String s) ? s : null;

            if ("assistant".equals(role)) {
                Map<String, Object> msg = msg("assistant", "");
                if (payloadStr != null && !payloadStr.isBlank()) {
                    try {
                        JsonNode node = mapper.readTree(payloadStr);
                        JsonNode tc = node.path("tool_calls");
                        if (tc.isArray() && tc.size() > 0) {
                            for (JsonNode one : tc) {
                                String id = one.path("id").asText(null);
                                String name = one.path("function").path("name").asText(null);
                                if (id != null && name != null) pending.add(new Pending(id, name));
                            }
                            msg.put("tool_calls", mapper.convertValue(tc, List.class)); // 顶层展开
                            out.add(msg);
                            continue;
                        }
                    } catch (Exception ignore) {}
                }
                msg.put("content", nz(content));
                out.add(msg);
                continue;
            }

            if ("tool".equals(role)) {
                String toolCallId = null, toolName = null, toolContent = null;

                if (payloadStr != null && !payloadStr.isBlank()) {
                    try {
                        JsonNode node = mapper.readTree(payloadStr);
                        if (node.has("tool_call_id")) toolCallId = node.get("tool_call_id").asText(null);
                        if (node.has("name"))         toolName   = node.get("name").asText(null);
                        if (node.has("content"))      toolContent= node.get("content").asText(null);
                        if ((toolContent == null || toolContent.isBlank()) && node.has("value")) {
                            toolContent = node.get("value").asText(null); // 兼容 {"type":"text","value":"…"}
                        }
                    } catch (Exception ignore) {}
                }
                if ((toolContent == null || toolContent.isBlank()) && content != null) {
                    toolContent = content;
                }
                if (toolCallId == null || toolName == null) {
                    Pending p = pending.pollFirst();
                    if (p != null) {
                        if (toolCallId == null) toolCallId = p.id();
                        if (toolName   == null) toolName   = p.name();
                    }
                }

                Map<String, Object> msg = msg("tool", nz(toolContent));
                if (toolCallId != null) msg.put("tool_call_id", toolCallId);
                if (toolName   != null) msg.put("name", toolName);
                out.add(msg);
                continue;
            }

            out.add(msg(role, nz(content))); // user/system/普通 assistant
        }
        return out;
    }

    /** 不支持 tool_calls（如部分 Ollama）时：把“调用对儿”折叠为一条 assistant 文本，提示“上一轮已执行过 …” */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toOllama(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());

        String pendingName = null, pendingArgs = "{}";
        String pendingResult = null;

        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            String role = Objects.toString(row.get("role"), "");
            String content = (String) row.get("content");
            String payloadStr = (row.get("payload") instanceof String s) ? s : null;

            if ("assistant".equals(role) && payloadStr != null && !payloadStr.isBlank()) {
                try {
                    JsonNode node = mapper.readTree(payloadStr);
                    JsonNode tc = node.path("tool_calls");
                    if (tc.isArray() && tc.size() > 0) {
                        JsonNode one = tc.get(0); // 你已限制“一次最多一个工具”
                        pendingName = one.path("function").path("name").asText(null);
                        pendingArgs = one.path("function").path("arguments").asText("{}");
                        continue; // 暂不输出，等待 tool 结果
                    }
                } catch (Exception ignore) {}
            }

            if ("tool".equals(role)) {
                String resultText = nz(content);
                if (resultText.isBlank() && payloadStr != null) {
                    try {
                        JsonNode node = mapper.readTree(payloadStr);
                        if (node.has("content")) resultText = nz(node.get("content").asText(null));
                        if (resultText.isBlank() && node.has("value")) {
                            resultText = nz(node.get("value").asText(null));
                        }
                    } catch (Exception ignore) {}
                }
                pendingResult = resultText;

                if (pendingName != null) {
                    try {
                        String preview = mapper.writeValueAsString(nz(pendingResult)); // JSON 字符串（已带引号）
                        String merged = String.format(
                                "{\"action\":\"%s\",\"args\":%s,\"_executed\":true,\"_result_preview\":%s}",
                                pendingName,
                                (pendingArgs == null || pendingArgs.isBlank()) ? "{}" : pendingArgs,
                                preview
                        );
                        out.add(msg("assistant", merged));
                    } catch (Exception e) {
                        out.add(msg("assistant",
                                "[HISTORY] executed action=" + pendingName + " args=" +
                                        (pendingArgs == null ? "{}" : pendingArgs) + " result=" + nz(pendingResult)));
                    }
                    pendingName = null; pendingArgs = "{}"; pendingResult = null;
                    continue;
                }
            }

            out.add(msg(role, nz(content))); // 其它消息原样
        }

        if (pendingName != null) {
            String merged = String.format(
                    "{\"action\":\"%s\",\"args\":%s,\"_executed\":false}",
                    pendingName,
                    (pendingArgs == null || pendingArgs.isBlank()) ? "{}" : pendingArgs
            );
            out.add(msg("assistant", merged));
        }
        return out;
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content == null ? "" : content);
        return m;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
