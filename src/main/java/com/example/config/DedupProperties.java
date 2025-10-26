package com.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationPropertiesScan
@ConfigurationProperties(prefix = "ai.tools.dedup")
public class DedupProperties {
    private boolean enabled = true;
    private int defaultTtlSeconds = 600;               // 默认 10 分钟
    private List<String> ignoreArgs = List.of("timestamp","requestId","nonce");
}
