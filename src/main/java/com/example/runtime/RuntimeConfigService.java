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
    private final ConfigStore store;
    private final AiProperties props;
    private final ApplicationEventPublisher publisher;
    private final AtomicReference<RuntimeConfig> ref = new AtomicReference<>();

    @PostConstruct
    void init() {
        RuntimeConfig persisted = null;
        try { persisted = store.loadOrNull(); } catch (Throwable ignore) {}

        if (persisted != null) {
            ref.set(persisted);
            return;
        }

        RuntimeConfig init = RuntimeConfig.builder()
                .compatibility(null)
                .model(props.getModel())
                .toolsMaxLoops(props.getTools() != null ? props.getTools().getMaxLoops() : 2)
                .clientTimeoutMs(props.getClient() != null ? props.getClient().getTimeoutMs() : null)
                .streamTimeoutMs(props.getClient() != null ? props.getClient().getStreamTimeoutMs() : null)
                .baseUrl(props.getBaseUrl())
                .build();
        ref.set(init);
    }

    public RuntimeConfig view() { return ref.get(); }

    public void update(RuntimeConfig cfg) {
        ref.set(cfg);
        publisher.publishEvent(new RuntimeConfigReloadedEvent(cfg));
    }
}