package com.example.api.dto;

import java.util.List;

public record StepTransition(List<StepEvent> events, StepState nextState) {
    public static StepTransition of(StepState next, List<StepEvent> events) {
        return new StepTransition(events, next);
    }
    public static StepTransition onlyState(StepState next) {
        return new StepTransition(List.of(), next);
    }
}
