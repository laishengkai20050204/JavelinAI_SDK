package com.example.api.dto;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "error");
        data.put("stepId", stepId);
        data.put("loop", loop);
        data.put("message", (message == null || message.isBlank()) ? "<no message>" : message);
        return new StepEvent("error", now(), data);
    }
    public static StepEvent error(String stepId, int loop, Throwable t) {
        return error(stepId, loop, formatThrowable(t));
    }
    private static String formatThrowable(Throwable t) {
        if (t == null) return "<null>";
        // 优先用 message；没有就用类名 + toString；附带一级 cause
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) msg = t.toString();
        Throwable c = t.getCause();
        if (c != null && c != t) msg += " | cause: " + c.toString();
        return msg;
    }

    private static String now() { return OffsetDateTime.now().toString(); }
}
