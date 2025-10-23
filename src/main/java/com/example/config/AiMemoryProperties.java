package com.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.memory")
public class AiMemoryProperties {
    /**
     * 存储方式：可选值 "in-memory" 或 "database"
     */
    private String storage = "in-memory";
}
