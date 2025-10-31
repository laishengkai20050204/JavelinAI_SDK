package com.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Configuration
public class WebClientConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public WebClient aiWebClient(EffectiveProps effectiveProps) {
        ExchangeFilterFunction dynamicAuthAndBaseUrl = (request, next) -> {
            String key = effectiveProps.apiKey();
            String targetBase = effectiveProps.baseUrl();

            ClientRequest.Builder b = ClientRequest.from(request);

            // 1) 动态 Authorization（为空则移除）
            if (key != null && !key.isBlank()) {
                b.headers(h -> h.setBearerAuth(key));
            } else {
                b.headers(h -> h.remove("Authorization"));
            }

            // 2) 动态 baseUrl 重写（只替换 scheme/host/port，保留 path/query）
            if (targetBase != null && !targetBase.isBlank()) {
                URI old = request.url();
                URI base = URI.create(targetBase);
                URI merged = UriComponentsBuilder.newInstance()
                        .scheme(base.getScheme())
                        .host(base.getHost())
                        .port(base.getPort())
                        .path(base.getPath())     // 若你的 base 有前缀（如 /v1），这里会拼接
                        .path(old.getPath())
                        .query(old.getQuery())
                        .build(true)
                        .toUri();
                b.url(merged);
            }

            return next.exchange(b.build());
        };

        return WebClient.builder()
                .filter(dynamicAuthAndBaseUrl)
                .build();
    }
}
