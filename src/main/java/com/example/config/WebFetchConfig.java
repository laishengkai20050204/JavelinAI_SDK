package com.example.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WebFetchProperties.class)
public class WebFetchConfig {
    // 仅用于启用 @ConfigurationProperties 绑定
}
