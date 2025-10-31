package com.example.config;

import com.example.runtime.RuntimeConfig;
import com.example.runtime.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class EffectiveProps {
    private final AiProperties statics;
    private final RuntimeConfigService runtime;

    private RuntimeConfig rc() { return runtime.view(); }

    // === 模式 ===
    public AiProperties.Mode mode() {
        // 1) 运行时覆盖（字符串 OPENAI/OLLAMA）
        var r = rc();
        if (r != null && StringUtils.hasText(r.getCompatibility())) {
            try { return AiProperties.Mode.valueOf(r.getCompatibility().trim().toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        // 2) 静态回退
        return (statics.getCompatibility() != null) ? statics.getMode() : AiProperties.Mode.OPENAI;
    }

    // 备用：给网关内部把“入参 mode”与运行时做合并
    public AiProperties.Mode modeOr(AiProperties.Mode fallback) {
        var r = rc();
        if (r != null && StringUtils.hasText(r.getCompatibility())) {
            try { return AiProperties.Mode.valueOf(r.getCompatibility().trim().toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        return (fallback != null) ? fallback : mode();
    }

    // === 供业务层调用的“最终值” ===

    public String model() {
        var r = rc();
        if (r != null && r.getModel() != null && !r.getModel().isBlank()) return r.getModel();
        return statics.getModel();
    }

    public int toolsMaxLoops() {
        var r = rc();
        if (r != null && r.getToolsMaxLoops() != null && r.getToolsMaxLoops() > 0) return r.getToolsMaxLoops();
        return (statics.getTools() != null ? statics.getTools().getMaxLoops() : 2);
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

    // （需要的话再补充 baseUrl/apiKey 等；资源类热更建议用 Reloadable）
}
