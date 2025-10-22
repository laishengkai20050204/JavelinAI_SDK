package com.example.service.impl;

import com.example.service.ConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class InMemoryConversationMemoryService implements ConversationMemoryService {

    private final Map<String, Map<String, List<Map<String, Object>>>> conversations = new ConcurrentHashMap<>();

    @Override
    public List<Map<String, Object>> getHistory(String userId, String conversationId) {
        List<Map<String, Object>> history = new ArrayList<>(getConversation(userId).getOrDefault(conversationId, Collections.emptyList()));
        log.debug("History lookup userId={} conversationId={} -> {} message(s)", userId, conversationId, history.size());
        return history;
    }

    @Override
    public void appendMessages(String userId, String conversationId, List<Map<String, Object>> messages) {
        conversations
                .computeIfAbsent(userId, key -> new ConcurrentHashMap<>())
                .compute(conversationId, (id, existing) -> {
                    List<Map<String, Object>> target = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
                    target.addAll(messages);
                    log.debug("Appended {} message(s) userId={} conversationId={} -> total={}",
                            messages.size(), userId, conversationId, target.size());
                    return target;
                });
    }

    @Override
    public void clear(String userId, String conversationId) {
        Map<String, List<Map<String, Object>>> perUser = conversations.get(userId);
        if (perUser != null) {
            List<Map<String, Object>> removed = perUser.remove(conversationId);
            if (removed != null) {
                log.debug("Cleared conversation memory userId={} conversationId={} removedMessages={}",
                        userId, conversationId, removed.size());
            } else {
                log.debug("No conversation found to clear userId={} conversationId={}", userId, conversationId);
            }
            if (perUser.isEmpty()) {
                conversations.remove(userId);
                log.trace("Removed empty conversation bucket for userId={}", userId);
            }
        } else {
            log.debug("No user bucket found to clear userId={}", userId);
        }
    }

    @Override
    public List<Map<String, Object>> findRelevant(String userId, String conversationId, String query, int maxMessages) {
        List<Map<String, Object>> history = getConversation(userId).get(conversationId);
        if (history == null || history.isEmpty() || maxMessages <= 0) {
            log.debug("Relevant search empty userId={} conversationId={} query='{}' maxMessages={} historySize={}",
                    userId, conversationId, query, maxMessages, history == null ? 0 : history.size());
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

        int start = Math.max(0, history.size() - maxMessages);
        List<Map<String, Object>> fallback = new ArrayList<>();
        for (int i = start; i < history.size(); i++) {
            fallback.add(new HashMap<>(history.get(i)));
        }
        log.debug("Relevant search fallback returning {} message(s) userId={} conversationId={} query='{}'",
                fallback.size(), userId, conversationId, query);
        return fallback;
    }

    private Map<String, List<Map<String, Object>>> getConversation(String userId) {
        return conversations.computeIfAbsent(userId, key -> new ConcurrentHashMap<>());
    }
}
