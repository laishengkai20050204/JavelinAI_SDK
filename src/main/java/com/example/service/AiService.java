package com.example.service;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
// Service 接口
public interface AiService {
    // 同步
    String chatOnce(String userMessage);

    // 异步（非流式）
    Mono<String> chatOnceAsync(String userMessage);

    // 流式（SSE/分块）
    Flux<String> chatStream(String userMessage);
}
