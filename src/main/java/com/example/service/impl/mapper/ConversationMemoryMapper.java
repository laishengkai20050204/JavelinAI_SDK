package com.example.service.impl.mapper;

import com.example.service.impl.entity.ConversationMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ConversationMemoryMapper {

    List<ConversationMessageEntity> selectHistory(@Param("userId") String userId,
                                                  @Param("conversationId") String conversationId);

    int insertMessage(ConversationMessageEntity entity);

    int deleteConversation(@Param("userId") String userId,
                           @Param("conversationId") String conversationId);

    List<ConversationMessageEntity> selectByContent(@Param("userId") String userId,
                                                    @Param("conversationId") String conversationId,
                                                    @Param("query") String query,
                                                    @Param("limit") int limit);

    List<ConversationMessageEntity> selectLatest(@Param("userId") String userId,
                                                 @Param("conversationId") String conversationId,
                                                 @Param("limit") int limit);
}
