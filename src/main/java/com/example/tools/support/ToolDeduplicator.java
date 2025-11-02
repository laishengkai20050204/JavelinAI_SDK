package com.example.tools.support;

import com.example.audit.AuditChainService;
import com.example.audit.AuditHasher;
import com.example.mapper.ToolExecutionMapper;
import com.example.mapper.model.ToolExecutionRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolDeduplicator {

    private final ToolExecutionMapper db;
    private final AuditChainService auditChainService;
    private final ObjectMapper mapper;

    /** 指纹：toolName + '|' + canonicalArgs 的 SHA256 */
    public String fingerprint(String toolName, JsonNode canonicalArgs) {
        String payload = toolName + "|" + canonicalArgs.toString();
        return DigestUtils.sha256Hex(payload);
    }

    /** 命中返回 result_json（字符串），否则 Optional.empty() */
    public Optional<String> tryReuse(String userId, String convId, String toolName, String argsHash) {
        log.debug("[MAP-LOOKUP] user={} conv={} tool={} hash={}", userId, convId, toolName, argsHash);
        Optional<String> out = db.findValidSuccess(userId, convId, toolName, argsHash)
                .map(ToolExecutionRecord::getResultJson);
        log.debug("[MAP-LOOKUP-RET] hit={}", out.isPresent());
        return out;
    }

    /** 成功后入账（带 TTL） */
    @Transactional
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

        LocalDateTime createdAt = db.findLatestCreatedAt(userId, convId, toolName, argsHash);

        String dataHash = AuditHasher.computeDataHash(mapper, result);
        var payload = AuditHasher.buildToolAuditPayload(userId, convId, null, toolName, argsHash, dataHash, false, "SUCCESS", null, null);
        String canonical = AuditHasher.canonicalize(mapper, payload);

        auditChainService.linkLatestToolByArgsHashAt(userId, convId, toolName, argsHash, createdAt, canonical);

        log.debug("Persisted tool success: user={} conv={} tool={} ttl={}s",
                        userId, convId, toolName, ttlSeconds);

    }
}
