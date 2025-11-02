package com.example.audit;

import org.apache.ibatis.annotations.*;

@Mapper
public interface AuditMapper {

    // 同会话跨两表取“上一条” hash
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

    // 用复合键更新消息（假定 userId+convId+stepId+seq 唯一）
    @Update("""
      UPDATE conversation_messages
         SET prev_hash = #{prevHash}, hash = #{hash}, audit_canonical = #{canonical}
       WHERE user_id = #{userId} AND conversation_id = #{conversationId}
         AND step_id = #{stepId} AND seq = #{seq}
       LIMIT 1
    """)
    void updateMessageAuditByKey(@Param("userId") String userId,
                                 @Param("conversationId") String conversationId,
                                 @Param("stepId") String stepId,
                                 @Param("seq") int seq,
                                 @Param("prevHash") String prevHash,
                                 @Param("hash") String hash,
                                 @Param("canonical") String canonical);

    // 更新“该工具该参数”的最新一条执行记录（MySQL 支持 UPDATE...ORDER BY...LIMIT）
    @Update("""
      UPDATE tool_executions
         SET prev_hash = #{prevHash}, hash = #{hash}, audit_canonical = #{canonical}
       WHERE conversation_id = #{conversationId} AND tool_name = #{toolName}
         AND args_hash = #{argsHash}
       ORDER BY created_at DESC
       LIMIT 1
    """)
    void updateLatestToolAudit(@Param("conversationId") String conversationId,
                               @Param("toolName") String toolName,
                               @Param("argsHash") String argsHash,
                               @Param("prevHash") String prevHash,
                               @Param("hash") String hash,
                               @Param("canonical") String canonical);
}
