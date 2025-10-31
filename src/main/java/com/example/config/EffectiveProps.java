package com.example.config;

import com.example.runtime.RuntimeConfig;
import com.example.runtime.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class EffectiveProps {
    private final AiProperties statics;
    private final RuntimeConfigService runtime;
    private final Environment env;

    private RuntimeConfig rc() { return runtime.view(); }

    // === 模式 ===
    public AiProperties.Mode mode() {
        // 1) 运行时覆盖（字符�?OPENAI/OLLAMA�?
        var r = rc();
        if (r != null && StringUtils.hasText(r.getCompatibility())) {
            try { return AiProperties.Mode.valueOf(r.getCompatibility().trim().toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        // 2) 静态回退
        return (statics.getCompatibility() != null) ? statics.getMode() : AiProperties.Mode.OPENAI;
    }

    // 备用：给网关内部把“入�?mode”与运行时做合并
    public AiProperties.Mode modeOr(AiProperties.Mode fallback) {
        var r = rc();
        if (r != null && StringUtils.hasText(r.getCompatibility())) {
            try { return AiProperties.Mode.valueOf(r.getCompatibility().trim().toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        return (fallback != null) ? fallback : mode();
    }

    // === 供业务层调用的“最终值�?===

    public String model() {
        var r = rc();
        if (r != null && r.getModel() != null && !r.getModel().isBlank()) return r.getModel();
        return statics.getModel();
    }

    public String baseUrl() {
        var r = rc();
        if (r != null && r.getBaseUrl() != null && !r.getBaseUrl().isBlank()) return r.getBaseUrl();
        if (statics.getBaseUrl() != null && !statics.getBaseUrl().isBlank()) return statics.getBaseUrl();
        // Fallback to Spring AI configured base-url for current mode
        AiProperties.Mode m = mode();
        String key = (m == AiProperties.Mode.OPENAI) ? "spring.ai.openai.base-url" : "spring.ai.ollama.base-url";
        String v = env.getProperty(key);
        return (StringUtils.hasText(v)) ? v : null;
    }

    public String apiKey() {
        var r = rc();
        if (r != null && r.getApiKey() != null && !r.getApiKey().isBlank()) return r.getApiKey();
        // Fallback to Spring environment (resolved application properties)
        String key = env.getProperty("spring.ai.openai.api-key");
        if (!StringUtils.hasText(key) || "dummy".equalsIgnoreCase(key)) {
            key = env.getProperty("OPENAI_API_KEY");
        }
        return StringUtils.hasText(key) ? key : null;
    }

    public int toolsMaxLoops() {
        var r = rc();
        if (r != null && r.getToolsMaxLoops() != null && r.getToolsMaxLoops() > 0) return r.getToolsMaxLoops();
        return (statics.getTools() != null ? statics.getTools().getMaxLoops() : 10);
    }

    public Map<String, Boolean> toolToggles() {
        var r = rc();
        return (r != null && r.getToolToggles() != null) ? r.getToolToggles() : Map.of();
    }

    public Long clientTimeoutMs() {
        var r = rc();
        return (r != null && r.getClientTimeoutMs() != null) ? r.getClientTimeoutMs() :
                (statics.getClient() != null ? statics.getClient().getTimeoutMs() : null);
    }

    public Long streamTimeoutMs() {
        var r = rc();
        return (r != null && r.getStreamTimeoutMs() != null) ? r.getStreamTimeoutMs() :
                (statics.getClient() != null ? statics.getClient().getStreamTimeoutMs() : null);
    }

    // （需要的话再补充 baseUrl/apiKey 等；资源类热更建议用 Reloadable�?
}
