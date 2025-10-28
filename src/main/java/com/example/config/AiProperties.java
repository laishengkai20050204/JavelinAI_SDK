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
    public static class Debug {
        /** 是否把 provider 的原生元信息（metadata/raw）打印到日志（DEBUG） */
        private boolean logRawResponse = true;
        /** 是否在网关返回的 JSON 中加入 _provider_raw 字段（仅调试时打开，可能很大） */
        private boolean includeRawInGatewayJson = false;
    }

    /** 调试相关开关（默认全开日志、默认不回传） */
    private Debug debug = new Debug();


    @Data
    public static class Tools {
        private int maxLoops = 2;
        private CallStep callStep = new CallStep();
        @Data public static class CallStep {
            private long ttlMinutes = 30;
            private long maximumSize = 10000;
        }
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
