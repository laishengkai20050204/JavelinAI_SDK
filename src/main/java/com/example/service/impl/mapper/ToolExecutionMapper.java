package com.example.service.impl.mapper;

import com.example.service.impl.mapper.model.ToolExecutionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface ToolExecutionMapper {

    Optional<ToolExecutionRecord> findValidSuccess(
            @Param("userId") String userId,
            @Param("conversationId") String conversationId,
            @Param("toolName") String toolName,
            @Param("argsHash") String argsHash);

    int upsertSuccess(@Param("rec") ToolExecutionRecord rec);
}