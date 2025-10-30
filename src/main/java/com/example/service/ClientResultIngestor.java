package com.example.service;

import com.example.api.dto.StepState;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface ClientResultIngestor {
    Mono<Void> ingest(StepState st, List<Map<String,Object>> clientResults);
}
