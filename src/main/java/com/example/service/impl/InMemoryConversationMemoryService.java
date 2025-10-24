package com.example.service.impl;

import com.example.service.ConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Service
@ConditionalOnProperty(name = "ai.memory.storage", havingValue = "in-memory", matchIfMissing = true)
@Slf4j
public class InMemoryConversationMemoryService implements ConversationMemoryService {

    private static final String STATE_DRAFT = "DRAFT";
    private static final String STATE_FINAL = "FINAL";

    private final Map<String, Map<String, Map<MessageKey, StoredMessage>>> conversations = new ConcurrentHashMap<>();

    @Override
    public List<Map<String, Object>> getHistory(String userId, String conversationId) {
        List<Map<String, Object>> history = snapshot(userId, conversationId, stored -> STATE_FINAL.equals(stored.getState()), Integer.MAX_VALUE);
        log.debug("History lookup userId={} conversationId={} -> {} message(s)", userId, conversationId, history.size());
        return history;
    }

    @Override
    public void appendMessages(String userId, String conversationId, List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String stepId = "legacy-" + UUID.randomUUID();
        int seq = 1;
        for (Map<String, Object> message : messages) {
            Map<String, Object> original = message == null ? Map.of() : new HashMap<>(message);
            String role = asString(original.get("role"));
            String content = asString(original.get("content"));
            String timestamp = extractTimestamp(original);
            upsertInternal(userId, conversationId, role, content, null, stepId, seq++, STATE_FINAL, original, timestamp);
        }
        log.debug("Appended {} message(s) userId={} conversationId={}", messages.size(), userId, conversationId);
    }

    @Override
    public void clear(String userId, String conversationId) {
        Map<String, Map<MessageKey, StoredMessage>> perUser = conversations.get(userId);
        if (perUser == null) {
            log.debug("No user bucket found to clear userId={}", userId);
            return;
        }
        Map<MessageKey, StoredMessage> removed = perUser.remove(conversationId);
        if (removed != null) {
            log.debug("Cleared conversation memory userId={} conversationId={} removedMessages={}",
                    userId, conversationId, removed.size());
        } else {
            log.debug("No conversation found to clear userId={} conversationId={}", userId, conversationId);
        }
        if (perUser.isEmpty()) {
            conversations.remove(userId);
        }
    }

    @Override
    public List<Map<String, Object>> findRelevant(String userId, String conversationId, String query, int maxMessages) {
        List<Map<String, Object>> history = getHistory(userId, conversationId);
        if (history.isEmpty() || maxMessages <= 0) {
            log.debug("Relevant search empty userId={} conversationId={} query='{}' maxMessages={} historySize={}",
                    userId, conversationId, query, maxMessages, history.size());
            return Collections.emptyList();
        }

        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matches = new ArrayList<>();

        if (!normalized.isEmpty()) {
            for (int i = history.size() - 1; i >= 0 && matches.size() < maxMessages; i--) {
                Map<String, Object> message = history.get(i);
                Object content = message.get("content");
                if (content instanceof String text && text.toLowerCase(Locale.ROOT).contains(normalized)) {
                    matches.add(new HashMap<>(message));
                }
            }
            Collections.reverse(matches);
            if (!matches.isEmpty()) {
                log.debug("Relevant search matched {} message(s) userId={} conversationId={} query='{}'",
                        matches.size(), userId, conversationId, query);
                return matches;
            }
        }

        int safeMax = Math.max(1, maxMessages);
        int start = Math.max(0, history.size() - safeMax);
        List<Map<String, Object>> fallback = new ArrayList<>();
        for (int i = start; i < history.size(); i++) {
            fallback.add(new HashMap<>(history.get(i)));
        }
        log.debug("Relevant search fallback returning {} message(s) userId={} conversationId={} query='{}'",
                fallback.size(), userId, conversationId, query);
        return fallback;
    }

    @Override
    public void upsertMessage(String userId, String conversationId,
                              String role, String content, String payloadJson,
                              String stepId, int seq, String state) {
        upsertInternal(userId, conversationId, role, content, payloadJson, stepId, seq, state, null, null);
    }

    @Override
    public List<Map<String, Object>> getContext(String userId, String conversationId, int limit) {
        int safeLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
        return snapshot(userId, conversationId,
                stored -> STATE_FINAL.equals(stored.getState()) || STATE_DRAFT.equals(stored.getState()),
                safeLimit);
    }

    @Override
    public void promoteDraftsToFinal(String userId, String conversationId, String stepId) {
        if (!StringUtils.hasText(stepId)) {
            return;
        }
        Map<MessageKey, StoredMessage> bucket = getConversation(userId).get(conversationId);
        if (bucket == null || bucket.isEmpty()) {
            return;
        }
        bucket.values().stream()
                .filter(stored -> stepId.equals(stored.getStepId()) && STATE_DRAFT.equals(stored.getState()))
                .forEach(stored -> stored.setState(STATE_FINAL));
    }

    @Override
    public void deleteDraftsOlderThanHours(int hours) {
        if (hours <= 0) {
            return;
        }
        Instant threshold = Instant.now().minus(hours, ChronoUnit.HOURS);
        for (Map.Entry<String, Map<String, Map<MessageKey, StoredMessage>>> userEntry : conversations.entrySet()) {
            Map<String, Map<MessageKey, StoredMessage>> perConversation = userEntry.getValue();
            for (Map.Entry<String, Map<MessageKey, StoredMessage>> convEntry : perConversation.entrySet()) {
                convEntry.getValue().entrySet().removeIf(entry -> {
                    StoredMessage stored = entry.getValue();
                    return STATE_DRAFT.equals(stored.getState()) && stored.getCreatedAt().isBefore(threshold);
                });
            }
            perConversation.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }
        conversations.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private void upsertInternal(String userId, String conversationId,
                                String role, String content, String payloadJson,
                                String stepId, int seq, String state,
                                Map<String, Object> original, String messageTimestamp) {
        if (!StringUtils.hasText(stepId)) {
            return;
        }
        Map<MessageKey, StoredMessage> messages = getConversation(userId)
                .computeIfAbsent(conversationId, key -> new ConcurrentHashMap<>());
        MessageKey key = new MessageKey(stepId, role, seq);
        messages.compute(key, (ignored, existing) -> {
            if (existing == null) {
                StoredMessage created = new StoredMessage(stepId, role, seq, state, messageTimestamp, original);
                created.updateContent(content, payloadJson);
                return created;
            }
            existing.updateContent(content, payloadJson);
            if (original != null) {
                existing.replaceOriginal(original, messageTimestamp);
            } else if (StringUtils.hasText(messageTimestamp)) {
                existing.updateTimestamp(messageTimestamp);
            }
            return existing;
        });
    }

    private Map<String, Map<MessageKey, StoredMessage>> getConversation(String userId) {
        return conversations.computeIfAbsent(userId, key -> new ConcurrentHashMap<>());
    }

    private List<Map<String, Object>> snapshot(String userId, String conversationId,
                                               Predicate<StoredMessage> filter, int limit) {
        Map<MessageKey, StoredMessage> bucket = getConversation(userId).get(conversationId);
        if (bucket == null || bucket.isEmpty()) {
            return Collections.emptyList();
        }
        List<StoredMessage> candidates = new ArrayList<>(bucket.values());
        candidates.sort(Comparator
                .comparing(StoredMessage::getCreatedAt)
                .thenComparingInt(StoredMessage::getSeq));
        List<Map<String, Object>> result = new ArrayList<>();
        for (StoredMessage stored : candidates) {
            if (!filter.test(stored)) {
                continue;
            }
            result.add(stored.toMessage());
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
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

    private String extractTimestamp(Map<String, Object> message) {
        Object ts = message != null ? message.get("timestamp") : null;
        if (ts instanceof String str && StringUtils.hasText(str)) {
            return str;
        }
        return null;
    }

    private record MessageKey(String stepId, String role, int seq) { }

    private static final class StoredMessage {
        private final String stepId;
        private final String role;
        private final int seq;
        private String state;
        private final Instant createdAt;
        private String messageTimestamp;
        private Map<String, Object> original;
        private String content;
        private String payload;

        private StoredMessage(String stepId, String role, int seq, String state,
                               String messageTimestamp, Map<String, Object> original) {
            this.stepId = stepId;
            this.role = role;
            this.seq = seq;
            this.state = StringUtils.hasText(state) ? state : STATE_DRAFT;
            this.createdAt = Instant.now();
            replaceOriginal(original, messageTimestamp);
        }

        private void updateContent(String content, String payloadJson) {
            if (content != null) {
                this.content = content;
                if (original != null) {
                    original.put("content", content);
                }
            }
            if (payloadJson != null) {
                this.payload = payloadJson;
                if (original != null) {
                    original.put("payload", payloadJson);
                }
            }
            if (original == null) {
                original = new HashMap<>();
                if (role != null) {
                    original.put("role", role);
                }
                if (this.content != null) {
                    original.put("content", this.content);
                }
                if (this.payload != null) {
                    original.put("payload", this.payload);
                }
            }
        }

        private void replaceOriginal(Map<String, Object> message, String timestamp) {
            if (message != null) {
                this.original = new HashMap<>(message);
            }
            if (this.original == null) {
                this.original = new HashMap<>();
            }
            if (role != null) {
                this.original.putIfAbsent("role", role);
            }
            if (timestamp == null && message != null) {
                Object ts = message.get("timestamp");
                if (ts instanceof String str && StringUtils.hasText(str)) {
                    timestamp = str;
                }
            }
            updateTimestamp(timestamp);
        }

        private void updateTimestamp(String timestamp) {
            if (StringUtils.hasText(timestamp)) {
                this.messageTimestamp = timestamp;
                this.original.put("timestamp", timestamp);
            }
        }

        private void setState(String state) {
            if (StringUtils.hasText(state)) {
                this.state = state;
            }
            this.original.put("state", this.state);
        }

        private String getState() {
            return state;
        }

        private String getStepId() {
            return stepId;
        }

        private int getSeq() {
            return seq;
        }

        private Instant getCreatedAt() {
            return createdAt;
        }

        private Map<String, Object> toMessage() {
            Map<String, Object> copy = new HashMap<>(original);
            if (role != null) {
                copy.putIfAbsent("role", role);
            }
            if (content != null) {
                copy.put("content", content);
            }
            if (payload != null) {
                copy.putIfAbsent("payload", payload);
            }
            if (messageTimestamp != null) {
                copy.putIfAbsent("timestamp", messageTimestamp);
            } else {
                copy.putIfAbsent("timestamp", createdAt.toString());
            }
            copy.putIfAbsent("state", state);
            copy.putIfAbsent("stepId", stepId);
            copy.putIfAbsent("seq", seq);
            return copy;
        }
    }
}
