package com.example.service.impl;

import com.example.api.dto.StepState;
import com.example.service.Guardrails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GuardrailsImpl implements Guardrails {
    @Value("${ai.tools.max-loops:2}")
    private int maxLoops;
    @Override public boolean reachedMaxLoops(StepState st) { return st.loop() >= maxLoops; }
}
