package com.example.api.dto;

import java.time.OffsetDateTime;
import java.util.Map;

/** 对外输出的事件（started/step/finished/error）。后续我们会把 data 的结构定死。 */
public record StepEvent(String event, String ts, Object data) {
    public static StepEvent started(String stepId, int loop) {
        return new StepEvent("started", now(), Map.of("stepId", stepId, "loop", loop));
    }
    public static StepEvent step(Object data) {
        return new StepEvent("step", now(), data);
    }
    public static StepEvent finished(String stepId, int loop) {
        return new StepEvent("finished", now(), Map.of("stepId", stepId, "loop", loop));
    }
    public static StepEvent error(String stepId, int loop, String message) {
        return new StepEvent("error", now(), Map.of("stepId", stepId, "loop", loop, "message", message));
    }
    private static String now() { return OffsetDateTime.now().toString(); }
}
