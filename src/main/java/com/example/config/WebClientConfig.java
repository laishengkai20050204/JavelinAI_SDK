package com.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient aiWebClient(
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.api-key:}") String apiKey,
            @Value("${ai.auth-scheme:Bearer}") String authScheme
    ) {
        WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl);

        // 如果配置了 API Key，则自动添加 Authorization 头
        if (StringUtils.hasText(apiKey)) {
            builder.filter((request, next) -> {
                ClientRequest mutated = ClientRequest.from(request)
                        .headers(h -> h.set(HttpHeaders.AUTHORIZATION, authScheme + " " + apiKey))
                        .build();
                return next.exchange(mutated);
            });
        }

        // 可选：简单日志
        builder.filter(ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            System.out.println("[AI-HTTP] " + clientRequest.method() + " " + clientRequest.url());
            return reactor.core.publisher.Mono.just(clientRequest);
        }));

        return builder.build();
    }
}
