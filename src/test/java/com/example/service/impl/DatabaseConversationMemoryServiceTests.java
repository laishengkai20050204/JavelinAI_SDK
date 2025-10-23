package com.example.service.impl;

import com.example.service.impl.entity.ConversationMessageEntity;
import com.example.service.impl.mapper.ConversationMemoryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseConversationMemoryServiceTests {

    private ConversationMemoryMapper mapper;
    private DatabaseConversationMemoryService service;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(ConversationMemoryMapper.class);
        service = new DatabaseConversationMemoryService(mapper, new ObjectMapper());
    }

    @Test
    void getHistoryReturnsMapsFromJson() {
        ConversationMessageEntity entity = new ConversationMessageEntity();
        entity.setUserId("u");
        entity.setConversationId("c");
        entity.setRole("assistant");
        entity.setPayload("{\"role\":\"assistant\",\"content\":\"hello\"}");
        entity.setMessageTimestamp("2024-06-01T12:00:00Z");
        entity.setCreatedAt(LocalDateTime.of(2024, Month.JUNE, 1, 12, 0));

        when(mapper.selectHistory("u", "c")).thenReturn(List.of(entity));

        List<Map<String, Object>> history = service.getHistory("u", "c");

        assertThat(history).hasSize(1);
        Map<String, Object> message = history.getFirst();
        assertThat(message.get("role")).isEqualTo("assistant");
        assertThat(message.get("content")).isEqualTo("hello");
        assertThat(message.get("timestamp")).isEqualTo("2024-06-01T12:00:00Z");
    }

    @Test
    void appendMessagesSerializesAndPersists() {
        service.appendMessages("u", "c", List.of(Map.of(
                "role", "user",
                "content", "hi there"
        )));

        ArgumentCaptor<ConversationMessageEntity> captor = ArgumentCaptor.forClass(ConversationMessageEntity.class);
        verify(mapper).insertMessage(captor.capture());

        ConversationMessageEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("u");
        assertThat(saved.getConversationId()).isEqualTo("c");
        assertThat(saved.getRole()).isEqualTo("user");
        assertThat(saved.getContent()).isEqualTo("hi there");
        assertThat(saved.getPayload()).contains("\"content\":\"hi there\"");
        assertThat(saved.getMessageTimestamp()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void findRelevantFallsBackToLatestWhenNoSearchMatches() {
        ConversationMessageEntity fallback = new ConversationMessageEntity();
        fallback.setUserId("u");
        fallback.setConversationId("c");
        fallback.setRole("assistant");
        fallback.setContent("latest response");
        fallback.setMessageTimestamp("2024-07-10T08:30:00Z");
        fallback.setCreatedAt(LocalDateTime.of(2024, Month.JULY, 10, 8, 30));

        when(mapper.selectByContent("u", "c", "missing", 5)).thenReturn(List.of());
        when(mapper.selectLatest("u", "c", 5)).thenReturn(List.of(fallback));

        List<Map<String, Object>> messages = service.findRelevant("u", "c", "missing", 5);

        verify(mapper).selectByContent("u", "c", "missing", 5);
        verify(mapper).selectLatest("u", "c", 5);
        assertThat(messages).hasSize(1);
        Map<String, Object> message = messages.getFirst();
        assertThat(message.get("content")).isEqualTo("latest response");
        assertThat(message.get("timestamp")).isEqualTo(fallback.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant().toString());
    }
}
