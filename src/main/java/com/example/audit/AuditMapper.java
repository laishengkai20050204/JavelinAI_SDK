package com.example.audit;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface AuditMapper {

    // 同会话跨两表取“上一条” hash（由 XML 提供 SQL）
    String findLastHashByConversation(@Param("conversationId") String conversationId);

    // 用复合键更新消息（假定 userId+convId+stepId+seq 唯一）（由 XML 提供 SQL）
    void updateMessageAuditByKey(@Param("userId") String userId,
                                 @Param("conversationId") String conversationId,
                                 @Param("stepId") String stepId,
                                 @Param("seq") int seq,
                                 @Param("prevHash") String prevHash,
                                 @Param("hash") String hash,
                                 @Param("canonical") String canonical);

    // 更新“该工具该参数”的最新一条执行记录（由 XML 提供 SQL）
    void updateLatestToolAudit(@Param("conversationId") String conversationId,
                               @Param("toolName") String toolName,
                               @Param("argsHash") String argsHash,
                               @Param("prevHash") String prevHash,
                               @Param("hash") String hash,
                               @Param("canonical") String canonical);

    // 回溯到某个时间点时的上一条（由 XML 提供 SQL）
    String findLastHashByConversationAt(@Param("userId") String userId,
                                        @Param("conversationId") String conversationId,
                                        @Param("atTs") java.time.LocalDateTime atTs);

    /** 审计时间线（按微秒时间 + seq + id 升序） */
    List<Map<String,Object>> selectAuditTimeline(@Param("userId") String userId,
                                                 @Param("conversationId") String conversationId);


}
