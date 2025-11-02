package com.example.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditChainService {
    private final AuditMapper mapper;

    public void linkMessageByKey(String userId, String convId, String stepId, int seq, String canonical) {
        String prev = mapper.findLastHashByConversation(convId);
        var chain = AuditHasher.link(prev, canonical);
        mapper.updateMessageAuditByKey(userId, convId, stepId, seq, chain.prev(), chain.hash(), chain.canonical());
    }

    public void linkLatestToolByArgsHash(String convId, String toolName, String argsHash, String canonical) {
        String prev = mapper.findLastHashByConversation(convId);
        var chain = AuditHasher.link(prev, canonical);
        mapper.updateLatestToolAudit(convId, toolName, argsHash, chain.prev(), chain.hash(), chain.canonical());
    }
}
