package com.example.service;

import com.example.api.dto.AssembledContext;
import com.example.api.dto.StepState;
import reactor.core.publisher.Mono;

public interface ContextAssembler {
    Mono<AssembledContext> assemble(StepState st);

}
