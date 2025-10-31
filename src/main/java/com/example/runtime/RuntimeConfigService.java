package com.example.runtime;

import com.example.config.AiProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
public class RuntimeConfigService {
    private final AiProperties props;                 // 作为默认值来源
    private final ApplicationEventPublisher publisher;
    private final AtomicReference<RuntimeConfig> ref = new AtomicReference<>();

    @PostConstruct
    void init() {
        String compat = null;
        try {
            // 你的 AiProperties 里若有 getCompatibility()（枚举），转成字符串
            compat = (props.getCompatibility() != null) ? props.getMode().name() : null;
        } catch (Throwable ignore) {}
        RuntimeConfig init = RuntimeConfig.builder()
                .compatibility(compat)
                .model(props.getModel())
                .toolsMaxLoops(props.getTools() != null ? props.getTools().getMaxLoops() : 2)
                .clientTimeoutMs(props.getClient() != null ? props.getClient().getTimeoutMs() : null)
                .streamTimeoutMs(props.getClient() != null ? props.getClient().getStreamTimeoutMs() : null)
                .build();
        ref.set(init);
    }

    public RuntimeConfig view() { return ref.get(); }

    public void update(RuntimeConfig cfg) {
        ref.set(cfg);
        publisher.publishEvent(new RuntimeConfigReloadedEvent(cfg));
    }
}
