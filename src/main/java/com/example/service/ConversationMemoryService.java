package com.example.service;

import java.util.List;
import java.util.Map;

public interface ConversationMemoryService {

    List<Map<String, Object>> getHistory(String userId, String conversationId);

    void appendMessages(String userId, String conversationId, List<Map<String, Object>> messages);

    void clear(String userId, String conversationId);

    List<Map<String, Object>> findRelevant(String userId, String conversationId, String query, int maxMessages);

    void upsertMessage(String userId, String conversationId,
                       String role, String content, String payloadJson,
                       String stepId, int seq, String state);

    List<Map<String, Object>> getContext(String userId, String conversationId, int limit);

    void promoteDraftsToFinal(String userId, String conversationId, String stepId);

    void deleteDraftsOlderThanHours(int hours);
}
