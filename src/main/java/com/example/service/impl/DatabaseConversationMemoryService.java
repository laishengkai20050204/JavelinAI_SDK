package com.example.service.impl;

import com.example.service.ConversationMemoryService;
import com.example.service.impl.entity.ConversationMessageEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "ai.memory.storage", havingValue = "database")
@ConditionalOnBean(NamedParameterJdbcTemplate.class)
@RequiredArgsConstructor
@Slf4j
public class DatabaseConversationMemoryService implements ConversationMemoryService {

    private static final String BASE_SELECT = """
            SELECT id, user_id, conversation_id, role, content, payload, message_timestamp, created_at
            FROM conversation_messages
            """;

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public List<Map<String, Object>> getHistory(String userId, String conversationId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("conversationId", conversationId);

        List<ConversationMessageEntity> entities = jdbcTemplate.query(
                BASE_SELECT + " WHERE user_id = :userId AND conversation_id = :conversationId ORDER BY created_at ASC",
                params,
                new ConversationMessageRowMapper()
        );

        log.debug("Database history lookup userId={} conversationId={} -> {} message(s)",
                userId, conversationId, entities.size());
        return toMessageList(entities);
    }

    @Override
    public void appendMessages(String userId, String conversationId, List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        String sql = """
                INSERT INTO conversation_messages (user_id, conversation_id, role, content, payload, message_timestamp, created_at)
                VALUES (:userId, :conversationId, :role, :content, :payload, :messageTimestamp, :createdAt)
                """;

        for (Map<String, Object> message : messages) {
            ConversationMessageEntity entity = toEntity(userId, conversationId, message);
            SqlParameterSource params = new MapSqlParameterSource()
                    .addValue("userId", entity.getUserId())
                    .addValue("conversationId", entity.getConversationId())
                    .addValue("role", entity.getRole())
                    .addValue("content", entity.getContent())
                    .addValue("payload", entity.getPayload())
                    .addValue("messageTimestamp", entity.getMessageTimestamp())
                    .addValue("createdAt", entity.getCreatedAt() != null ? Timestamp.valueOf(entity.getCreatedAt()) : null);

            jdbcTemplate.update(sql, params);
        }
    }

    @Override
    public void clear(String userId, String conversationId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("conversationId", conversationId);

        int deleted = jdbcTemplate.update(
                "DELETE FROM conversation_messages WHERE user_id = :userId AND conversation_id = :conversationId",
                params
        );
        log.debug("Cleared database conversation userId={} conversationId={} removedMessages={}",
                userId, conversationId, deleted);
    }

    @Override
    public List<Map<String, Object>> findRelevant(String userId, String conversationId, String query, int maxMessages) {
        int limit = Math.max(1, maxMessages);

        List<ConversationMessageEntity> matches = searchByContent(userId, conversationId, query, limit);
        if (!matches.isEmpty()) {
            return toMessageList(matches);
        }

        List<ConversationMessageEntity> latest = latestMessages(userId, conversationId, limit);
        return toMessageList(latest);
    }

    private List<ConversationMessageEntity> searchByContent(String userId,
                                                            String conversationId,
                                                            String query,
                                                            int limit) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("conversationId", conversationId)
                .addValue("pattern", "%" + query.trim() + "%")
                .addValue("limit", limit);

        List<ConversationMessageEntity> entities = jdbcTemplate.query(
                BASE_SELECT + """
                        WHERE user_id = :userId
                          AND conversation_id = :conversationId
                          AND content LIKE :pattern
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """,
                params,
                new ConversationMessageRowMapper()
        );

        log.debug("Database relevant search matched {} message(s) userId={} conversationId={} query='{}'",
                entities.size(), userId, conversationId, query);
        reverseInPlace(entities);
        return entities;
    }

    private List<ConversationMessageEntity> latestMessages(String userId, String conversationId, int limit) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("conversationId", conversationId)
                .addValue("limit", limit);

        List<ConversationMessageEntity> entities = jdbcTemplate.query(
                BASE_SELECT + """
                        WHERE user_id = :userId
                          AND conversation_id = :conversationId
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """,
                params,
                new ConversationMessageRowMapper()
        );

        reverseInPlace(entities);
        log.debug("Database relevant fallback returning {} message(s) userId={} conversationId={}",
                entities.size(), userId, conversationId);
        return entities;
    }

    private void reverseInPlace(List<ConversationMessageEntity> entities) {
        if (entities == null || entities.size() <= 1) {
            return;
        }
        for (int i = 0, j = entities.size() - 1; i < j; i++, j--) {
            ConversationMessageEntity tmp = entities.get(i);
            entities.set(i, entities.get(j));
            entities.set(j, tmp);
        }
    }

    private ConversationMessageEntity toEntity(String userId,
                                               String conversationId,
                                               Map<String, Object> message) {
        ConversationMessageEntity entity = new ConversationMessageEntity();
        entity.setUserId(userId);
        entity.setConversationId(conversationId);
        entity.setRole(asString(message.get("role")));
        entity.setContent(extractContent(message));

        String messageTimestamp = extractTimestamp(message);
        entity.setMessageTimestamp(messageTimestamp);
        entity.setCreatedAt(resolveCreatedAt(messageTimestamp));

        try {
            entity.setPayload(objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.warn("Failed to serialize conversation message for userId={} conversationId={}", userId, conversationId, e);
            entity.setPayload(null);
        }
        return entity;
    }

    private List<Map<String, Object>> toMessageList(List<ConversationMessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> results = new ArrayList<>(entities.size());
        for (ConversationMessageEntity entity : entities) {
            Map<String, Object> map = toMessageMap(entity);
            if (!map.isEmpty()) {
                results.add(map);
            }
        }
        return results;
    }

    private Map<String, Object> toMessageMap(ConversationMessageEntity entity) {
        if (entity == null) {
            return Map.of();
        }

        Map<String, Object> message = new HashMap<>();
        boolean populatedFromJson = false;

        if (entity.getPayload() != null && !entity.getPayload().isBlank()) {
            try {
                message.putAll(objectMapper.readValue(entity.getPayload(), MAP_TYPE));
                populatedFromJson = true;
            } catch (Exception ex) {
                log.warn("Failed to deserialize conversation message id={}", entity.getId(), ex);
            }
        }

        if (!StringUtils.hasText(asString(message.get("role"))) && StringUtils.hasText(entity.getRole())) {
            message.put("role", entity.getRole());
        }
        if (!StringUtils.hasText(extractContent(message)) && StringUtils.hasText(entity.getContent())) {
            message.put("content", entity.getContent());
        }
        if (!message.containsKey("timestamp") && StringUtils.hasText(entity.getMessageTimestamp())) {
            message.put("timestamp", entity.getMessageTimestamp());
        }
        if (!message.containsKey("timestamp") && entity.getCreatedAt() != null) {
            String timestamp = entity.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant().toString();
            message.put("timestamp", timestamp);
        }
        if (!message.containsKey("timestamp") && !populatedFromJson) {
            message.put("timestamp", Instant.now().toString());
        }

        return message;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str;
        }
        return String.valueOf(value);
    }

    private String extractContent(Map<String, Object> message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        Object direct = message.get("content");
        String resolved = coerceText(direct);
        if (resolved != null) {
            return resolved;
        }
        for (String key : List.of("text", "value", "data", "message")) {
            resolved = coerceText(message.get(key));
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String coerceText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return StringUtils.hasText(str) ? str : null;
        }
        if (value instanceof Map<?, ?> map) {
            String candidate = coerceText(map.get("content"));
            if (candidate != null) {
                return candidate;
            }
            candidate = coerceText(map.get("message"));
            if (candidate != null) {
                return candidate;
            }
            candidate = coerceText(map.get("reasoning"));
            if (candidate != null) {
                return candidate;
            }
            candidate = coerceText(map.get("delta"));
            if (candidate != null) {
                return candidate;
            }
            candidate = coerceText(map.get("text"));
            if (candidate != null) {
                return candidate;
            }
            candidate = coerceText(map.get("value"));
            if (candidate != null) {
                return candidate;
            }
            candidate = coerceText(map.get("choices"));
            if (candidate != null) {
                return candidate;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                if (key instanceof String strKey) {
                    String lower = strKey.toLowerCase(Locale.ROOT);
                    if (lower.equals("id") || lower.equals("object") || lower.equals("model")
                            || lower.equals("system_fingerprint") || lower.equals("created")
                            || lower.equals("finish_reason") || lower.equals("index")) {
                        continue;
                    }
                }
                candidate = coerceText(entry.getValue());
                if (candidate != null) {
                    return candidate;
                }
            }
            return null;
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder();
            for (Object element : iterable) {
                String part = coerceText(element);
                if (part != null) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(part);
                }
            }
            return builder.length() > 0 ? builder.toString() : null;
        }
        return null;
    }

    private String extractTimestamp(Map<String, Object> message) {
        Object ts = message != null ? message.get("timestamp") : null;
        if (ts instanceof String str && StringUtils.hasText(str)) {
            return str;
        }
        return null;
    }

    private LocalDateTime resolveCreatedAt(String timestamp) {
        if (StringUtils.hasText(timestamp)) {
            try {
                Instant instant = Instant.parse(timestamp);
                return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static class ConversationMessageRowMapper implements RowMapper<ConversationMessageEntity> {
        @Override
        public ConversationMessageEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            ConversationMessageEntity entity = new ConversationMessageEntity();
            entity.setId(rs.getLong("id"));
            entity.setUserId(rs.getString("user_id"));
            entity.setConversationId(rs.getString("conversation_id"));
            entity.setRole(rs.getString("role"));
            entity.setContent(rs.getString("content"));
            entity.setPayload(rs.getString("payload"));
            entity.setMessageTimestamp(rs.getString("message_timestamp"));

            Timestamp timestamp = rs.getTimestamp("created_at");
            if (timestamp != null) {
                entity.setCreatedAt(timestamp.toLocalDateTime());
            }
            return entity;
        }
    }
}
