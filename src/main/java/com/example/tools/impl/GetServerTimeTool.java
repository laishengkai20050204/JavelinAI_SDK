package com.example.tools.impl;

import com.example.ai.tools.AiToolComponent;
import com.example.api.dto.ToolResult;
import com.example.tools.AiTool;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@AiToolComponent
public class GetServerTimeTool implements AiTool {

    @Override
    public String name() {
        return "get_server_time";
    }

    @Override
    public String description() {
        return "Return current server time. Optional args: zoneId, format (java.time pattern).";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "zoneId", Map.of("type", "string", "description", "IANA ZoneId, e.g., 'UTC' or 'Asia/Shanghai'"),
                        "format", Map.of("type", "string", "description", "java.time pattern, e.g., 'yyyy-MM-dd HH:mm:ss'" )
                ),
                "required", List.of()
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String zoneStr = asString(args != null ? args.get("zoneId") : null);
        String fmtStr  = asString(args != null ? args.get("format") : null);

        ZoneId zone;
        try {
            zone = (zoneStr != null && !zoneStr.isBlank()) ? ZoneId.of(zoneStr) : ZoneId.systemDefault();
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }

        ZonedDateTime now = ZonedDateTime.now(zone);
        String iso = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String value = iso;
        if (fmtStr != null && !fmtStr.isBlank()) {
            try { value = now.format(DateTimeFormatter.ofPattern(fmtStr)); }
            catch (Exception ignore) { /* fallback to iso */ }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "text");
        payload.put("value", value);
        payload.put("iso", iso);
        payload.put("epochMillis", Instant.now().toEpochMilli());
        payload.put("zoneId", zone.getId());

        return ToolResult.success(null, name(), false, Map.of("payload", payload));
    }

    private static String asString(Object v) { return v == null ? null : String.valueOf(v); }
}

