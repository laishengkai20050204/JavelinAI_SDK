package com.example.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ReloadManager {
    private final List<Reloadable> reloadables;

    @EventListener(RuntimeConfigReloadedEvent.class)
    public void onReload(RuntimeConfigReloadedEvent ev) {
        for (Reloadable r : reloadables) {
            try { r.reload(ev.cfg()); } catch (Throwable ignore) {}
        }
    }
}
