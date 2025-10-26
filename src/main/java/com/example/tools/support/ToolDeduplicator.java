package com.example.tools.support;

import com.example.service.impl.mapper.ToolExecutionMapper;
import com.example.service.impl.mapper.model.ToolExecutionRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ToolDeduplicator {

    private final ToolExecutionMapper db;
    private final ObjectMapper mapper;

    /** 指纹：toolName + '|' + canonicalArgs 的 SHA256 */
    public String fingerprint(String toolName, JsonNode canonicalArgs) {
        String payload = toolName + "|" + canonicalArgs.toString();
        return DigestUtils.sha256Hex(payload);
    }

    /** 命中返回 result_json（字符串），否则 Optional.empty() */
    public Optional<String> tryReuse(String userId, String convId, String toolName, String argsHash) {
        return db.findValidSuccess(userId, convId, toolName, argsHash)
                .map(ToolExecutionRecord::getResultJson);
    }

    /** 成功后入账（带 TTL） */
    public void saveSuccess(String userId, String convId, String toolName, String argsHash,
                            JsonNode args, JsonNode result, int ttlSeconds) {
        ToolExecutionRecord rec = new ToolExecutionRecord();
        rec.setUserId(userId);
        rec.setConversationId(convId);
        rec.setToolName(toolName);
        rec.setArgsHash(argsHash);
        rec.setStatus("SUCCESS");
        rec.setArgsJson(args == null ? null : args.toString());
        rec.setResultJson(result == null ? null : result.toString());
        if (ttlSeconds > 0) rec.setExpiresAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        db.upsertSuccess(rec);
    }
}
