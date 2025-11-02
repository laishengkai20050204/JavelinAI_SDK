package com.example.audit;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuditRepo {

    /** 取同一会话的“上一条事件”的 hash（在两表中按时间倒序取最近一条非空 hash） */
    @Select("""
        SELECT h FROM (
          SELECT hash AS h, created_at AS ts FROM conversation_messages 
            WHERE conversation_id = #{conversationId} AND hash IS NOT NULL
            ORDER BY created_at DESC LIMIT 1
          UNION ALL
          SELECT hash AS h, created_at AS ts FROM tool_executions 
            WHERE conversation_id = #{conversationId} AND hash IS NOT NULL
            ORDER BY created_at DESC LIMIT 1
        ) t ORDER BY ts DESC LIMIT 1
        """)
    String findLastHashByConversation(@Param("conversationId") String conversationId);

    @Update("""
        UPDATE conversation_messages 
           SET prev_hash = #{prevHash}, hash = #{hash}, audit_canonical = #{canonical}
         WHERE id = #{id}
        """)
    void updateMessageAudit(@Param("id") long id,
                            @Param("prevHash") String prevHash,
                            @Param("hash") String hash,
                            @Param("canonical") String canonical);

    @Update("""
        UPDATE tool_executions 
           SET prev_hash = #{prevHash}, hash = #{hash}, audit_canonical = #{canonical}
         WHERE id = #{id}
        """)
    void updateToolAudit(@Param("id") long id,
                         @Param("prevHash") String prevHash,
                         @Param("hash") String hash,
                         @Param("canonical") String canonical);
}
