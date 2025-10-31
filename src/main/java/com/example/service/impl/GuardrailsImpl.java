package com.example.service.impl;

import com.example.api.dto.StepState;
import com.example.service.Guardrails;
import com.example.config.EffectiveProps;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GuardrailsImpl implements Guardrails {
    private final EffectiveProps props;

    @Override
    public boolean reachedMaxLoops(StepState st) {
        int max = Math.max(1, props.toolsMaxLoops());
        return st.loop() >= max;
    }
}
