package com.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "ai.tools.web-fetch")
public class WebFetchProperties {

    /** 仅允许的协议，默认 http/https */
    private String[] allowedSchemes = new String[]{"http","https"};

    /** 读取超时 */
    private Duration timeout = Duration.ofSeconds(8);

    /** WebClient 单次响应内存上限（影响字符串聚合），默认 512KB */
    private int maxInMemoryBytes = 512 * 1024;

    /** 生成给模型的正文最大字符数（截断），默认 2000 */
    private int defaultMaxChars = 2000;

    /** 默认的 User-Agent */
    private String userAgent = "JavelinAI-WebFetch/1.0";

    /** 是否启用基础 SSRF 防护（内网/回环/链路本地等） */
    private boolean ssrfGuardEnabled = true;
}
