package com.example.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditChainService {

    private final AuditRepo repo;

    /** 为消息行追加 prev/hash/audit_canonical */
    public AuditHasher.Chain linkAndPersistForMessage(String conversationId, long rowId, String canonical) {
        String prev = repo.findLastHashByConversation(conversationId);
        AuditHasher.Chain chain = AuditHasher.link(prev, canonical);
        repo.updateMessageAudit(rowId, chain.prev(), chain.hash(), chain.canonical());
        return chain;
    }

    /** 为工具执行行追加 prev/hash/audit_canonical */
    public AuditHasher.Chain linkAndPersistForToolExec(String conversationId, long rowId, String canonical) {
        String prev = repo.findLastHashByConversation(conversationId);
        AuditHasher.Chain chain = AuditHasher.link(prev, canonical);
        repo.updateToolAudit(rowId, chain.prev(), chain.hash(), chain.canonical());
        return chain;
    }
}
