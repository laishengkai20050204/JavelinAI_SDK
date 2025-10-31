package com.example.controller;

import com.example.config.EffectiveProps;
import com.example.runtime.ConfigStore;
import com.example.runtime.RuntimeConfig;
import com.example.runtime.RuntimeConfigService;
import com.example.tools.AiTool;
import com.example.tools.ToolRegistry;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final RuntimeConfigService cfgSvc;
    private final ConfigStore store;
    private final EffectiveProps effectiveProps;
    private final ToolRegistry toolRegistry;

    @Operation(summary = "获取配置")
    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> get() {
        var rc = cfgSvc.view();

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("compatibility", rc.getCompatibility());
        runtime.put("model", rc.getModel());
        runtime.put("toolsMaxLoops", rc.getToolsMaxLoops());
        runtime.put("toolToggles", rc.getToolToggles());
        runtime.put("baseUrl", rc.getBaseUrl());
        runtime.put("apiKeyMasked", mask(rc.getApiKey())); // ✅ 打码
        runtime.put("clientTimeoutMs", rc.getClientTimeoutMs());
        runtime.put("streamTimeoutMs", rc.getStreamTimeoutMs());

        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("compatibility", effectiveProps.mode().name());
        effective.put("model", effectiveProps.model());
        effective.put("toolsMaxLoops", effectiveProps.toolsMaxLoops());
        effective.put("clientTimeoutMs", effectiveProps.clientTimeoutMs());
        effective.put("streamTimeoutMs", effectiveProps.streamTimeoutMs());
        // ✅ 回显真实生效的 baseUrl 与打码的 apiKey
        effective.put("baseUrl", effectiveProps.baseUrl());
        effective.put("apiKeyMasked", mask(effectiveProps.apiKey()));

        // 🔒 将 GET 的详细日志降到 debug，并做脱敏
        log.debug("[ADMIN][GET]/config runtime: compat={} model={} loops={} baseUrl={} apiKeyMasked={} cTimeout={} sTimeout={}",
                runtime.get("compatibility"), runtime.get("model"), runtime.get("toolsMaxLoops"),
                safeBaseUrl(String.valueOf(runtime.get("baseUrl"))), runtime.get("apiKeyMasked"),
                runtime.get("clientTimeoutMs"), runtime.get("streamTimeoutMs"));
        log.debug("[ADMIN][GET]/config effective: compat={} model={} loops={} baseUrl={} apiKeyMasked={} cTimeout={} sTimeout={}",
                effective.get("compatibility"), effective.get("model"), effective.get("toolsMaxLoops"),
                safeBaseUrl(String.valueOf(effective.get("baseUrl"))), effective.get("apiKeyMasked"),
                effective.get("clientTimeoutMs"), effective.get("streamTimeoutMs"));

        // 可用工具名：服务端注册 ∪ 已存在的开关键（支持对未注册名做覆盖）
        List<String> serverToolNames = toolRegistry.allTools().stream().map(AiTool::name).toList();
        Set<String> available = new LinkedHashSet<>(serverToolNames);
        if (rc.getToolToggles() != null) {
            available.addAll(rc.getToolToggles().keySet());
        }

        return Map.of(
                "runtime", runtime,
                "effective", effective,
                "availableTools", List.copyOf(available)
        );
    }

    @Operation(summary = "修改配置（合并语义：只更新传入的字段）")
    @PutMapping(value = "/config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> put(@RequestBody RuntimeConfig in) throws Exception {
        // ===== 入参概要（安全）=====
        boolean hasNewApiKey = in.getApiKey() != null && !in.getApiKey().isBlank();
        log.info("[ADMIN][PUT]/config incoming: compat={} model={} loops={} baseUrl={} apiKeyProvided={} cTimeout={} sTimeout={} togglesKeys={}",
                in.getCompatibility(), in.getModel(), in.getToolsMaxLoops(), safeBaseUrl(in.getBaseUrl()),
                hasNewApiKey, in.getClientTimeoutMs(), in.getStreamTimeoutMs(),
                (in.getToolToggles() != null ? in.getToolToggles().keySet() : "[]"));

        RuntimeConfig old = cfgSvc.view();

        // 规范化模式（OPENAI/OLLAMA）
        String compat = normalizeCompat(in.getCompatibility(), old.getCompatibility());

        RuntimeConfig merged = RuntimeConfig.builder()
                .compatibility(compat)
                .model(coalesce(in.getModel(), old.getModel()))
                .toolsMaxLoops(coalesce(in.getToolsMaxLoops(), old.getToolsMaxLoops()))
                .toolToggles(coalesceNonEmpty(in.getToolToggles(), old.getToolToggles()))
                // 若当前不重建下游客户端，也可以先不暴露这几项；这里保持你的原逻辑
                .baseUrl(coalesce(in.getBaseUrl(), old.getBaseUrl()))
                .apiKey(coalesce(in.getApiKey(), old.getApiKey()))
                .clientTimeoutMs(coalesce(in.getClientTimeoutMs(), old.getClientTimeoutMs()))
                .streamTimeoutMs(coalesce(in.getStreamTimeoutMs(), old.getStreamTimeoutMs()))
                .build();

        store.save(merged);
        cfgSvc.update(merged);

        // ✅ 日志开关变化：新增禁用 / 取消禁用 / 当前禁用集
        logToggleDiff(old.getToolToggles(), merged.getToolToggles());

        // 结果概要（安全）
        log.info("[ADMIN][PUT]/config applied: compat={} model={} loops={} baseUrl={} apiKeyMasked={} cTimeout={} sTimeout={} togglesKeys={}",
                merged.getCompatibility(), merged.getModel(), merged.getToolsMaxLoops(), safeBaseUrl(merged.getBaseUrl()),
                mask(merged.getApiKey()), merged.getClientTimeoutMs(), merged.getStreamTimeoutMs(),
                (merged.getToolToggles() != null ? merged.getToolToggles().keySet() : "[]"));

        // 详细 debug（安全版，不打印明文 key）
        // 替换原来的：var safeMerged = Map.of( ... );
        var safeMerged = new java.util.LinkedHashMap<String, Object>();
        safePut(safeMerged, "compat",          merged.getCompatibility());
        safePut(safeMerged, "model",           merged.getModel());
        safePut(safeMerged, "toolsMaxLoops",   merged.getToolsMaxLoops());
        safePut(safeMerged, "baseUrl",         safeBaseUrl(merged.getBaseUrl())); // 可能为 null
        safePut(safeMerged, "apiKeyMasked",    mask(merged.getApiKey()));         // 可能为 null
        safePut(safeMerged, "clientTimeoutMs", merged.getClientTimeoutMs());      // 可能为 null
        safePut(safeMerged, "streamTimeoutMs", merged.getStreamTimeoutMs());      // 可能为 null
        // toggleKeys 我们保证有值（至少是空集）
        safeMerged.put("toggleKeys",
                merged.getToolToggles() != null ? merged.getToolToggles().keySet() : java.util.Set.of());

        log.debug("[ADMIN][PUT]/config merged(safe): {}", safeMerged);


        return Map.of("ok", true);
    }

    private static void safePut(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }


    @Operation(summary = "修改配置（全量替换：未传字段会被清空）")
    @PutMapping(value = "/config/replace", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> replace(@RequestBody RuntimeConfig cfg) throws Exception {
        RuntimeConfig before = cfgSvc.view(); // 用于差异日志

        // 校验模式
        String compat = normalizeCompat(cfg.getCompatibility(), cfg.getCompatibility());
        cfg.setCompatibility(compat);

        boolean hasNewApiKey = cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
        log.warn("[ADMIN][PUT]/config/replace incoming: compat={} model={} loops={} baseUrl={} apiKeyProvided={} cTimeout={} sTimeout={} togglesKeys={}",
                cfg.getCompatibility(), cfg.getModel(), cfg.getToolsMaxLoops(), safeBaseUrl(cfg.getBaseUrl()),
                hasNewApiKey, cfg.getClientTimeoutMs(), cfg.getStreamTimeoutMs(),
                (cfg.getToolToggles() != null ? cfg.getToolToggles().keySet() : "[]"));

        store.save(cfg);
        cfgSvc.update(cfg);

        // ✅ 日志开关变化（全量替换也对比前后）
        logToggleDiff(before.getToolToggles(), cfg.getToolToggles());

        log.warn("[ADMIN][PUT]/config/replace applied: compat={} model={} loops={} baseUrl={} apiKeyMasked={} cTimeout={} sTimeout={} togglesKeys={}",
                cfg.getCompatibility(), cfg.getModel(), cfg.getToolsMaxLoops(), safeBaseUrl(cfg.getBaseUrl()),
                mask(cfg.getApiKey()), cfg.getClientTimeoutMs(), cfg.getStreamTimeoutMs(),
                (cfg.getToolToggles() != null ? cfg.getToolToggles().keySet() : "[]"));

        return Map.of("ok", true);
    }

    @Operation(summary = "重新加载")
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        cfgSvc.update(cfgSvc.view());
        log.info("[ADMIN][POST]/reload triggered");
        return Map.of("ok", true);
    }

    // ===== helpers =====

    private static <T> T coalesce(T v, T fallback) {
        return v != null ? v : fallback;
    }

    private static <K, V> Map<K, V> coalesceNonEmpty(Map<K, V> v, Map<K, V> fallback) {
        if (v == null) return fallback;
        if (v.isEmpty()) return fallback;
        return v;
    }

    private static String normalizeCompat(String in, String fallback) {
        if (in == null || in.isBlank()) return fallback;
        String s = in.trim().toUpperCase();
        if ("OPENAI".equals(s) || "OLLAMA".equals(s)) return s;
        throw new IllegalArgumentException("compatibility must be OPENAI or OLLAMA");
    }

    // 固定长度遮罩，避免泄露 key 长度
    private String mask(String s) {
        if (s == null || s.isBlank()) return null;
        int keep = Math.min(4, s.length());
        String tail = s.substring(s.length() - keep);
        return "********" + tail; // 固定 8 个 *
    }

    // 去掉 baseUrl 中可能的 user:pass@，避免账号/密码入日志
    private String safeBaseUrl(String s) {
        if (s == null || s.isBlank()) return s;
        try {
            URI u = URI.create(s);
            if (u.getUserInfo() != null) {
                return new URI(
                        u.getScheme(), null, u.getHost(), u.getPort(),
                        u.getPath(), u.getQuery(), u.getFragment()
                ).toString();
            }
        } catch (Exception ignored) {}
        return s;
    }

    // 打印工具开关差异：新增禁用 / 取消禁用 / 当前禁用集
    private void logToggleDiff(Map<String, Boolean> before, Map<String, Boolean> after) {
        Set<String> offBefore = (before == null) ? Set.of()
                : before.entrySet().stream()
                .filter(e -> Boolean.FALSE.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Set<String> offAfter = (after == null) ? Set.of()
                : after.entrySet().stream()
                .filter(e -> Boolean.FALSE.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Set<String> turnedOff = new LinkedHashSet<>(offAfter);
        turnedOff.removeAll(offBefore);

        Set<String> restoredOn = new LinkedHashSet<>(offBefore);
        restoredOn.removeAll(offAfter);

        log.info("[TOGGLE] disabled+= {} | disabled-= {} | nowDisabled={}", turnedOff, restoredOn, offAfter);
    }
}
