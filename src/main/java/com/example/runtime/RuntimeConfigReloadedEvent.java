package com.example.runtime;

import org.springframework.context.ApplicationEvent;

public class RuntimeConfigReloadedEvent extends ApplicationEvent {
    public RuntimeConfigReloadedEvent(RuntimeConfig cfg) { super(cfg); }
    public RuntimeConfig cfg() { return (RuntimeConfig) getSource(); }
}
