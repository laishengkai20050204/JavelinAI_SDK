package com.example.service.impl.mapper;

import com.example.service.impl.entity.ConversationMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ConversationMemoryMapper {

    List<ConversationMessageEntity> selectHistory(@Param("userId") String userId,
                                                  @Param("conversationId") String conversationId);

    int deleteConversation(@Param("userId") String userId,
                           @Param("conversationId") String conversationId);

    List<ConversationMessageEntity> selectByContent(@Param("userId") String userId,
                                                    @Param("conversationId") String conversationId,
                                                    @Param("query") String query,
                                                    @Param("limit") int limit);

    List<ConversationMessageEntity> selectLatest(@Param("userId") String userId,
                                                 @Param("conversationId") String conversationId,
                                                 @Param("limit") int limit);

    int upsertMessage(@Param("userId") String userId,
                      @Param("conversationId") String conversationId,
                      @Param("role") String role,
                      @Param("content") String content,
                      @Param("payload") String payloadJson,
                      @Param("messageTimestamp") String messageTimestamp,
                      @Param("stepId") String stepId,
                      @Param("seq") int seq,
                      @Param("state") String state);

    List<Map<String, Object>> selectContext(@Param("userId") String userId,
                                            @Param("conversationId") String conversationId,
                                            @Param("limit") int limit);

    int promoteDraftsToFinal(@Param("userId") String userId,
                             @Param("conversationId") String conversationId,
                             @Param("stepId") String stepId);

    int deleteDraftsOlderThanHours(@Param("hours") int hours);
}
