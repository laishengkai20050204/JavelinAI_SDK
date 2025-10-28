package com.example.service;

import com.example.api.dto.AssembledContext;
import com.example.api.dto.ModelDecision;
import com.example.api.dto.StepState;
import reactor.core.publisher.Mono;

public interface DecisionService {
    Mono<ModelDecision> decide(StepState st, AssembledContext ctx);
}
