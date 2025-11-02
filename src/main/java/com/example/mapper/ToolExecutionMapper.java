package com.example.mapper;

import com.example.mapper.model.ToolExecutionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Optional;

@Mapper
public interface ToolExecutionMapper {

    Optional<ToolExecutionRecord> findValidSuccess(
            @Param("userId") String userId,
            @Param("conversationId") String conversationId,
            @Param("toolName") String toolName,
            @Param("argsHash") String argsHash);

    int upsertSuccess(@Param("rec") ToolExecutionRecord rec);


    LocalDateTime findLatestCreatedAt(
            @Param("userId") String userId,
            @Param("convId") String convId,
            @Param("toolName") String toolName,
            @Param("argsHash") String argsHash);
}