package com.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Central application properties for AI orchestration.
 *
 * <p>These settings bridge legacy configuration (max loops, memory window, step-json heartbeats)
 * with the new Spring AI chat model selection toggled via {@code ai.mode}.</p>
 */
@Data
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    public enum Mode {
        OPENAI, OLLAMA
    }

    private Mode mode = Mode.OPENAI;

    /**
     * Optional compatibility shims retained for legacy behaviour. They are no-ops
     * for Spring AI based calls but remain here for graceful downgrade.
     */
    private String compatibility;
    private String model = "qwen3:8b";
    private String path;
    private String baseUrl;

    private Tools tools = new Tools();
    private Memory memory = new Memory();
    private Think think = new Think();
    private Client client = new Client();
    private StepJson stepjson = new StepJson();

    @Data
    public static class Tools {
        private int maxLoops = 2;
    }

    @Data
    public static class Memory {
        private String storage = "in-memory";
        private int maxMessages = 12;
        private String persistenceMode = "draft-and-final";
        private boolean promoteDraftsOnFinish = true;
    }

    @Data
    public static class Think {
        private boolean enabled = false;
        private String level;
    }

    @Data
    public static class Client {
        private long timeoutMs = 60_000;
        private long streamTimeoutMs = 120_000;
        private Retry retry = new Retry();
    }

    @Data
    public static class Retry {
        private int maxAttempts = 2;
        private long backoffMs = 300;
    }

    @Data
    public static class StepJson {
        private long heartbeatSeconds = 5;
    }
}
