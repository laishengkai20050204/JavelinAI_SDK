package com.example.tools;

import com.example.tools.support.ToolDeduplicator;
import com.example.tools.support.JsonCanonicalizer;
import com.example.config.DedupProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 增强点：
 * 1) 执行前按 (toolName + canonical(args - ignore)) 生成 argsHash；
 * 2) 若未 force 且命中账本(未过期) => 直接复用；
 * 3) 否则执行并把结果写入账本(带 TTL)。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiToolExecutor {

    private final ToolRegistry registry;
    private final ObjectMapper mapper;

    // NEW: 注入去重配置与账本服务
    private final DedupProperties dedupProps;
    private final ToolDeduplicator dedup;

    private static final Set<String> PROTECTED_SCOPE_KEYS = Set.of("userId", "conversationId");

    public record ToolCall(String id, String name, String argumentsJson) {}

    public Map<String, Object> toAssistantToolCallsMessage(List<ToolCall> calls) {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (ToolCall call : calls) {
            log.trace("Preparing assistant tool call message id={} name={}", call.id(), call.name());
            arr.add(Map.of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of(
                            "name", call.name(),
                            "arguments", Objects.requireNonNullElse(call.argumentsJson(), "{}")
                    )
            ));
        }
        return Map.of(
                "role", "assistant",
                "tool_calls", arr,
                "content", ""
        );
    }

    public List<Map<String, Object>> executeAll(List<ToolCall> calls,
                                                Map<String, Object> fallbackArgs) throws Exception {
        log.debug("Executing {} tool call(s)", calls.size());
        List<Map<String, Object>> results = new ArrayList<>();

        for (ToolCall call : calls) {
            log.debug("Executing tool call id={} name={}", call.id(), call.name());
            AiTool tool = registry.get(call.name())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + call.name()));

            // 1) 解析参数
            Map<String, Object> args = mapper.readValue(
                    call.argumentsJson() == null || call.argumentsJson().isBlank() ? "{}" : call.argumentsJson(),
                    new TypeReference<Map<String, Object>>() {}
            );

            // 2) 合并上下文作用域参数（确保 userId / conversationId 一定存在且不可被覆盖）
            if (fallbackArgs != null) {
                fallbackArgs.forEach((key, value) -> {
                    if (value != null && PROTECTED_SCOPE_KEYS.contains(key)) {
                        args.put(key, value);
                    }
                });
                fallbackArgs.forEach((key, value) -> {
                    if (!PROTECTED_SCOPE_KEYS.contains(key)) {
                        args.putIfAbsent(key, value);
                    }
                });
            }

            String userId = Objects.toString(args.get("userId"), null);
            String conversationId = Objects.toString(args.get("conversationId"), null);
            boolean force = Boolean.TRUE.equals(args.get("force")); // 工具统一支持 force
            int ttlSeconds = dedupProps.getDefaultTtlSeconds();
            if (args.get("ttlSeconds") instanceof Number n && n.intValue() > 0) {
                ttlSeconds = n.intValue();
            }

            String contentJsonToReturn;

            // 3) 若启用去重，且具备 userId/convId 且不是 force，则尝试复用
            if (dedupProps.isEnabled() && !force && userId != null && conversationId != null) {
                // 3.1 计算参数指纹（忽略 timestamp/requestId/nonce 等抖动字段）
                Set<String> ignore = new HashSet<>(dedupProps.getIgnoreArgs());
                JsonNode canonicalArgs = JsonCanonicalizer.normalize(mapper, mapper.valueToTree(args), ignore);
                String argsHash = dedup.fingerprint(tool.name(), canonicalArgs);

                // 3.2 账本命中直接复用
                Optional<String> cached = dedup.tryReuse(userId, conversationId, tool.name(), argsHash);
                if (cached.isPresent()) {
                    contentJsonToReturn = cached.get();
                    log.debug("REUSED tool='{}' id={} fp={} user={} conv={} ttl={}s",
                            tool.name(), call.id(), argsHash.substring(0, 12), userId, conversationId, ttlSeconds);

                    results.add(Map.of(
                            "role", "tool",
                            "tool_call_id", call.id(),
                            "content", contentJsonToReturn
                    ));
                    continue;
                }

                // 3.3 未命中 -> 执行并入账
                ToolResult result;
                try {
                    result = tool.execute(args);
                } catch (Exception ex) {
                    log.warn("Tool '{}' execution failed", tool.name(), ex);
                    throw ex;
                }
                contentJsonToReturn = result.contentJson();
                dedup.saveSuccess(userId, conversationId, tool.name(), argsHash,
                        mapper.valueToTree(args), mapper.readTree(Objects.requireNonNullElse(contentJsonToReturn, "null")),
                        ttlSeconds);

                results.add(Map.of(
                        "role", "tool",
                        "tool_call_id", call.id(),
                        "content", contentJsonToReturn
                ));
                log.debug("Tool '{}' call id={} persisted SUCCESS, payloadLength={}",
                        tool.name(), call.id(), contentJsonToReturn != null ? contentJsonToReturn.length() : 0);
                continue;
            }

            // 4) 未启用去重 或 无 userId/convId 或 force=true -> 直接执行（不复用）
            ToolResult result;
            try {
                result = tool.execute(args);
            } catch (Exception ex) {
                log.warn("Tool '{}' execution failed", tool.name(), ex);
                throw ex;
            }
            contentJsonToReturn = result.contentJson();

            results.add(Map.of(
                    "role", "tool",
                    "tool_call_id", call.id(),
                    "content", contentJsonToReturn
            ));
            log.debug("Tool '{}' call id={} produced payloadLength={}",
                    tool.name(), call.id(), contentJsonToReturn != null ? contentJsonToReturn.length() : 0);
        }

        log.debug("Completed execution of {} tool call(s)", results.size());
        return results;
    }
}
