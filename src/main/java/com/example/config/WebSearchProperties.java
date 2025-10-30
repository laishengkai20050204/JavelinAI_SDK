package com.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.tools.web-search")
public class WebSearchProperties {

    /** 当前提供商：固定 serper（后续可扩展 brave、tavily 等） */
    private String provider = "serper";

    private Serper serper = new Serper();
    private Defaults defaults = new Defaults();

    @Data
    public static class Serper {
        /** Serper 基础地址 */
        private String baseUrl = "https://google.serper.dev";
        /** API Key，来自环境变量 SERPER_API_KEY */
        private String apiKey;
        /** 请求超时 */
        private Duration timeout = Duration.ofSeconds(8);
    }

    @Data
    public static class Defaults {
        private int topK = 5;
        private String lang = "zh-CN";   // 映射到 hl
        private String country = "jp";   // 映射到 gl
        private boolean safe = true;
    }
}
