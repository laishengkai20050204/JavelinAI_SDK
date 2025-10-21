package com.example.service;

import org.springframework.stereotype.Service;

@Service
public interface AiService {
    String chatOnce(String userMessage);

    String callOllama(String userMessage);

    String callOpenAI(String userMessage);
}
