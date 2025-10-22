package com.example.service.impl;

import com.example.service.ConversationMemoryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InMemoryConversationMemoryService implements ConversationMemoryService {

    private final Map<String, Map<String, List<Map<String, Object>>>> conversations = new ConcurrentHashMap<>();

    @Override
    public List<Map<String, Object>> getHistory(String userId, String conversationId) {
        return new ArrayList<>(getConversation(userId).getOrDefault(conversationId, Collections.emptyList()));
    }

    @Override
    public void appendMessages(String userId, String conversationId, List<Map<String, Object>> messages) {
        conversations
                .computeIfAbsent(userId, key -> new ConcurrentHashMap<>())
                .compute(conversationId, (id, existing) -> {
                    List<Map<String, Object>> target = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
                    target.addAll(messages);
                    return target;
                });
    }

    @Override
    public void clear(String userId, String conversationId) {
        Map<String, List<Map<String, Object>>> perUser = conversations.get(userId);
        if (perUser != null) {
            perUser.remove(conversationId);
            if (perUser.isEmpty()) {
                conversations.remove(userId);
            }
        }
    }

    @Override
    public List<Map<String, Object>> findRelevant(String userId, String conversationId, String query, int maxMessages) {
        List<Map<String, Object>> history = getConversation(userId).get(conversationId);
        if (history == null || history.isEmpty() || maxMessages <= 0) {
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
                return matches;
            }
        }

        int start = Math.max(0, history.size() - maxMessages);
        List<Map<String, Object>> fallback = new ArrayList<>();
        for (int i = start; i < history.size(); i++) {
            fallback.add(new HashMap<>(history.get(i)));
        }
        return fallback;
    }

    private Map<String, List<Map<String, Object>>> getConversation(String userId) {
        return conversations.computeIfAbsent(userId, key -> new ConcurrentHashMap<>());
    }
}
