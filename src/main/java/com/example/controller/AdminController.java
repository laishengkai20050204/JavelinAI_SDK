package com.example.controller;

import com.example.config.EffectiveProps;
import com.example.runtime.ConfigStore;
import com.example.runtime.RuntimeConfig;
import com.example.runtime.RuntimeConfigService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {
    private final RuntimeConfigService cfgSvc;
    private final ConfigStore store;
    private final EffectiveProps effectiveProps;

    @Operation(summary = "获取配置")
    @GetMapping(value="/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> get() {
        var rc = cfgSvc.view();

        // runtime：仅显示覆盖层（apiKey 只打码，不返回原文）
        Map<String,Object> runtime = new LinkedHashMap<>();
        runtime.put("compatibility", rc.getCompatibility());
        runtime.put("model", rc.getModel());
        runtime.put("toolsMaxLoops", rc.getToolsMaxLoops());
        runtime.put("toolToggles", rc.getToolToggles());
        runtime.put("baseUrl", rc.getBaseUrl());
        runtime.put("apiKeyMasked", mask(rc.getApiKey()));   // ★ 只返回打码
        runtime.put("clientTimeoutMs", rc.getClientTimeoutMs());
        runtime.put("streamTimeoutMs", rc.getStreamTimeoutMs());

        // effective：展示“实际生效”的值（运行时覆盖不为空则用覆盖，否则用静态配置）
        Map<String,Object> effective = new LinkedHashMap<>();
        effective.put("compatibility", effectiveProps.mode().name());
        effective.put("model", effectiveProps.model());
        effective.put("toolsMaxLoops", effectiveProps.toolsMaxLoops());
        effective.put("clientTimeoutMs", effectiveProps.clientTimeoutMs());
        effective.put("streamTimeoutMs", effectiveProps.streamTimeoutMs());

        // ===== 日志 =====
        log.info("[ADMIN][GET]/config runtime: compat={} model={} loops={} baseUrl={} apiKeyMasked={} cTimeout={} sTimeout={}",
                runtime.get("compatibility"), runtime.get("model"), runtime.get("toolsMaxLoops"),
                runtime.get("baseUrl"), runtime.get("apiKeyMasked"),
                runtime.get("clientTimeoutMs"), runtime.get("streamTimeoutMs"));
        log.debug("[ADMIN][GET]/config effective: {}", effective);

        return Map.of("runtime", runtime, "effective", effective);
    }

    @Operation(summary = "修改配置（合并语义：只更新传入的字段）")
    @PutMapping(value="/config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> put(@RequestBody RuntimeConfig in) throws Exception {
        // ===== 日志（入参概要）=====
        boolean hasNewApiKey = in.getApiKey() != null && !in.getApiKey().isBlank();
        log.info("[ADMIN][PUT]/config incoming: compat={} model={} loops={} baseUrl={} apiKeyProvided={} cTimeout={} sTimeout={} togglesKeys={}",
                in.getCompatibility(), in.getModel(), in.getToolsMaxLoops(), in.getBaseUrl(),
                hasNewApiKey, in.getClientTimeoutMs(), in.getStreamTimeoutMs(),
                (in.getToolToggles() != null ? in.getToolToggles().keySet() : "[]"));

        RuntimeConfig old = cfgSvc.view();

        // 规范化并校验模式（允许 OPENAI / OLLAMA；为空则沿用旧值）
        String compat = normalizeCompat(in.getCompatibility(), old.getCompatibility());

        RuntimeConfig merged = RuntimeConfig.builder()
                .compatibility( compat )
                .model(           coalesce(in.getModel(),           old.getModel()))
                .toolsMaxLoops(   coalesce(in.getToolsMaxLoops(),   old.getToolsMaxLoops()))
                .toolToggles(     coalesce(in.getToolToggles(),     old.getToolToggles()))
                // 下方几个按需保留：如果你目前不打算运行时重建 WebClient，可先不暴露/不合并
                .baseUrl(         coalesce(in.getBaseUrl(),         old.getBaseUrl()))
                .apiKey(          coalesce(in.getApiKey(),          old.getApiKey()))
                .clientTimeoutMs( coalesce(in.getClientTimeoutMs(), old.getClientTimeoutMs()))
                .streamTimeoutMs( coalesce(in.getStreamTimeoutMs(), old.getStreamTimeoutMs()))
                .build();

        store.save(merged);
        cfgSvc.update(merged);

        // ===== 日志（结果概要）=====
        log.info("[ADMIN][PUT]/config applied: compat={} model={} loops={} baseUrl={} apiKeyMasked={} cTimeout={} sTimeout={} togglesKeys={}",
                merged.getCompatibility(), merged.getModel(), merged.getToolsMaxLoops(), merged.getBaseUrl(),
                mask(merged.getApiKey()), merged.getClientTimeoutMs(), merged.getStreamTimeoutMs(),
                (merged.getToolToggles() != null ? merged.getToolToggles().keySet() : "[]"));
        log.debug("[ADMIN][PUT]/config merged: {}", merged);

        return Map.of("ok", true);
    }

    @Operation(summary = "修改配置（全量替换：未传字段会被清空）")
    @PutMapping(value="/config/replace", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> replace(@RequestBody RuntimeConfig cfg) throws Exception {
        // 也做一下模式校验，避免误写
        String compat = normalizeCompat(cfg.getCompatibility(), cfg.getCompatibility());
        cfg.setCompatibility(compat);

        // ===== 日志（高风险操作）=====
        boolean hasNewApiKey = cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
        log.warn("[ADMIN][PUT]/config/replace incoming: compat={} model={} loops={} baseUrl={} apiKeyProvided={} cTimeout={} sTimeout={} togglesKeys={}",
                cfg.getCompatibility(), cfg.getModel(), cfg.getToolsMaxLoops(), cfg.getBaseUrl(),
                hasNewApiKey, cfg.getClientTimeoutMs(), cfg.getStreamTimeoutMs(),
                (cfg.getToolToggles() != null ? cfg.getToolToggles().keySet() : "[]"));

        store.save(cfg);
        cfgSvc.update(cfg);

        log.warn("[ADMIN][PUT]/config/replace applied: compat={} model={} loops={} baseUrl={} apiKeyMasked={} cTimeout={} sTimeout={} togglesKeys={}",
                cfg.getCompatibility(), cfg.getModel(), cfg.getToolsMaxLoops(), cfg.getBaseUrl(),
                mask(cfg.getApiKey()), cfg.getClientTimeoutMs(), cfg.getStreamTimeoutMs(),
                (cfg.getToolToggles() != null ? cfg.getToolToggles().keySet() : "[]"));

        return Map.of("ok", true);
    }

    @Operation(summary = "重新加载")
    @PostMapping("/reload")
    public Map<String,Object> reload() {
        cfgSvc.update(cfgSvc.view());
        log.info("[ADMIN][POST]/reload triggered");
        return Map.of("ok", true);
    }

    private static <T> T coalesce(T v, T fallback) { return v != null ? v : fallback; }

    private static String normalizeCompat(String in, String fallback) {
        if (in == null || in.isBlank()) return fallback;
        String s = in.trim().toUpperCase();
        if ("OPENAI".equals(s) || "OLLAMA".equals(s)) return s;
        throw new IllegalArgumentException("compatibility must be OPENAI or OLLAMA");
    }

    private String mask(String s) {
        if (s == null || s.isBlank()) return null;
        int keep = Math.min(4, s.length());
        String tail = s.substring(s.length() - keep);
        return "*".repeat(Math.max(0, s.length() - keep)) + tail;
    }
}
