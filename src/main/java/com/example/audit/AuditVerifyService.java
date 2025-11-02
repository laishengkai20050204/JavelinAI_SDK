package com.example.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 从数据库拉取审计时间线，按 created_at ASC（微秒）、seq、id 校验：
 *   - prev_hash 是否等于上一条的 hash
 *   - hash 是否等于 AuditHasher.link(prev, canonical).hash()
 * 返回首个断点位置与详细断言。
 *
 * 依赖的 Mapper 方法（放在你的 AuditMapper 里）：
 *   List<Map<String,Object>> selectAuditTimeline(String conversationId)
 *   // 返回列至少包含：
 *   // id, type, role, step_id, seq, state, created_at(LocalDateTime), prev_hash, hash, audit_canonical
 */
@Service
@RequiredArgsConstructor
public class AuditVerifyService {

    private final AuditMapper auditMapper;         // 你项目里的 Mapper 接口（请确保已声明为 @Mapper）
    private final ObjectMapper objectMapper;

    /** 外部入口：生成报告 */
    public Report verify(String userId, String conversationId) {
        List<Map<String, Object>> timeline = fetchTimeline(userId, conversationId);
        return verifyTimeline(userId, conversationId, timeline);
    }

    /** 给导出服务共用的取数方法（单一数据口径） */
    public List<Map<String, Object>> fetchTimeline(String userId, String conversationId) {
        List<Map<String, Object>> list = auditMapper.selectAuditTimeline(userId, conversationId);
        // 保险起见再排序一次（SQL 已排序时这里稳定排序不会乱序）
//        list.sort(Comparator
//                .comparing((Map<String, Object> m) -> asLdt(m.get("created_at")), Comparator.nullsFirst(Comparator.naturalOrder()))
//                .thenComparing(m -> asInt(m.get("seq")))
//                .thenComparing(m -> asLong(m.get("id"))));
        return list;
    }

    /** 核心校验逻辑 */
    private Report verifyTimeline(String userId, String conversationId, List<Map<String, Object>> rows) {
        List<Break> breaks = new ArrayList<>();
        String prev = null;
        String tail = null;

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);

            String storedPrev = asStr(r.get("prev_hash"));
            String storedHash = asStr(r.get("hash"));
            String canonical = asStr(r.getOrDefault("audit_canonical", r.get("canonical"))); // 兼容列名

            // 计算期望
            var linked = AuditHasher.link(prev, canonical);
            String expectPrev = linked.prev();
            String expectHash = linked.hash();

            boolean prevOk = Objects.equals(storedPrev, expectPrev);
            boolean hashOk = Objects.equals(storedHash, expectHash);

            if (!(prevOk && hashOk)) {
                breaks.add(new Break(
                        i,
                        String.valueOf(r.get("id")),
                        asLdt(r.get("created_at")),
                        expectPrev, storedPrev,
                        expectHash, storedHash,
                        prevOk, hashOk
                ));
            }

            // 继续迭代链尾（按“实际链”推进，以便尽早发现第一个坏点后对后续也能定位）
            tail = storedHash != null ? storedHash : expectHash;
            prev = tail;
        }

        Integer firstBad = breaks.isEmpty() ? null : breaks.getFirst().getIndex();
        return new Report(userId, conversationId, rows.size(), breaks.isEmpty(), firstBad, breaks, tail);
    }

    // ====== 工具方法 ======

    private static LocalDateTime asLdt(Object v) {
        if (v instanceof LocalDateTime ldt) return ldt;
        return null;
    }

    private static String asStr(Object v) {
        return (v == null) ? null : String.valueOf(v);
    }

    private static int asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignore) { return 0; }
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception ignore) { return 0L; }
    }

    // ====== 报告结构 ======

    /** 校验报告 */
    public record Report(
            String userId,
            String conversationId,
            int totalNodes,
            boolean ok,
            Integer firstBadIndex,
            List<Break> breaks,
            String tailHash
    ) {}

    /** 断点详情 */
    @Getter
    @ToString
    public static class Break {
        private final int index;                 // 在时间线中的索引（从 0 起）
        private final String nodeId;             // 该行 id
        private final LocalDateTime createdAt;   // 该行时间
        private final String expectPrev;
        private final String actualPrev;
        private final String expectHash;
        private final String actualHash;
        private final boolean prevMatch;
        private final boolean hashMatch;

        public Break(int index, String nodeId, LocalDateTime createdAt,
                     String expectPrev, String actualPrev,
                     String expectHash, String actualHash,
                     boolean prevMatch, boolean hashMatch) {
            this.index = index;
            this.nodeId = nodeId;
            this.createdAt = createdAt;
            this.expectPrev = expectPrev;
            this.actualPrev = actualPrev;
            this.expectHash = expectHash;
            this.actualHash = actualHash;
            this.prevMatch = prevMatch;
            this.hashMatch = hashMatch;
        }
    }
}
