package com.example.service;

import org.springframework.stereotype.Service;

@Service
// Service 接口
public interface AiService {
    // 同步
    String chatOnce(String userMessage);

    // 异步（非流式）
    reactor.core.publisher.Mono<String> chatOnceAsync(String userMessage);

    // 流式（SSE/分块）
    reactor.core.publisher.Flux<String> chatStream(String userMessage);
}
