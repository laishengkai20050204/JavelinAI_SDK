package com.example.service;

import java.util.List;
import java.util.Map;

public interface ConversationMemoryService {

    List<Map<String, Object>> getHistory(String userId, String conversationId);

    void appendMessages(String userId, String conversationId, List<Map<String, Object>> messages);

    void clear(String userId, String conversationId);

    List<Map<String, Object>> findRelevant(String userId, String conversationId, String query, int maxMessages);
}
