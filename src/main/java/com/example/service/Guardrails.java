package com.example.service;

import com.example.api.dto.StepState;

public interface Guardrails {
    boolean reachedMaxLoops(StepState st);
}
