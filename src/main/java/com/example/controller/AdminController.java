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
    @PutMapping(value="/config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> put(@RequestBody Map<String,Object> body) throws Exception {
        RuntimeConfig old = cfgSvc.view();

        // —— presence 语义：区分“没传字段”与“传了但为空对象 {}”
        boolean togglesPresent = body.containsKey("toolToggles");
        @SuppressWarnings("unchecked")
        Map<String,Object> rawToggles = togglesPresent ? (Map<String,Object>) body.get("toolToggles") : null;
        Map<String,Boolean> incomingToggles =
                togglesPresent ? safeToBoolMap(rawToggles) : null;

        String compat = normalizeCompat(getString(body, "compatibility"), old.getCompatibility());

        Map<String,Boolean> mergedToggles =
                togglesPresent
                        ? (incomingToggles == null ? Map.of() : incomingToggles)   // 显式传入 → 覆盖（{}=清空覆盖）
                        : (old.getToolToggles() == null ? Map.of() : old.getToolToggles()); // 未传 → 保留旧值

        RuntimeConfig merged = RuntimeConfig.builder()
                .compatibility( compat )
                .model(           coalesce(getString(body,"model"),            old.getModel()))
                .toolsMaxLoops(   coalesce(getInt(body,"toolsMaxLoops"),       old.getToolsMaxLoops()))
                .toolToggles(     mergedToggles)
                .baseUrl(         coalesce(getString(body,"baseUrl"),          old.getBaseUrl()))
                .apiKey(          coalesce(getString(body,"apiKey"),           old.getApiKey()))
                .clientTimeoutMs( coalesce(getLong(body,"clientTimeoutMs"),    old.getClientTimeoutMs()))
                .streamTimeoutMs( coalesce(getLong(body,"streamTimeoutMs"),    old.getStreamTimeoutMs()))
                .build();

        // 日志（可选）
        log.info("[ADMIN][PUT]/config incoming: togglesPresent={}", togglesPresent);
        log.info("[ADMIN][PUT]/config applied: loops={} togglesKeys={}",
                merged.getToolsMaxLoops(), merged.getToolToggles().keySet());

        store.save(merged);
        cfgSvc.update(merged);
        return Map.of("ok", true);
    }

    private static String getString(Map<String,Object> m, String k) {
        Object v = m.get(k); return v == null ? null : String.valueOf(v);
    }
    private static Integer getInt(Map<String,Object> m, String k) {
        Object v = m.get(k); if (v == null) return null;
        return (v instanceof Number) ? ((Number)v).intValue() : Integer.valueOf(String.valueOf(v));
    }
    private static Long getLong(Map<String,Object> m, String k) {
        Object v = m.get(k); if (v == null) return null;
        return (v instanceof Number) ? ((Number)v).longValue() : Long.valueOf(String.valueOf(v));
    }
    private static Map<String,Boolean> safeToBoolMap(Map<String,Object> src) {
        if (src == null || src.isEmpty()) return Map.of();
        Map<String,Boolean> out = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            if (e.getKey() == null) continue;
            Object v = e.getValue();
            boolean b = (v instanceof Boolean) ? (Boolean)v : Boolean.parseBoolean(String.valueOf(v));
            out.put(e.getKey(), b);
        }
        return out;
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

    private static <K,V> Map<K,V> coalesceMap(Map<K,V> v, Map<K,V> fallback) {
        return (v != null) ? v : fallback;  // 允许空Map；null 才用旧值
    }


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
