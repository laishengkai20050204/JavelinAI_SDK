package com.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Configuration
@Slf4j
public class WebClientConfig {

    @Bean
    public WebClient aiWebClient(
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.api-key:}") String apiKey,
            @Value("${ai.auth-scheme:Bearer}") String authScheme
    ) {
        WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl);

        // Attach Authorization header when an API key is provided.
        if (StringUtils.hasText(apiKey)) {
            builder.filter((request, next) -> {
                ClientRequest mutated = ClientRequest.from(request)
                        .headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, authScheme + " " + apiKey))
                        .build();
                return next.exchange(mutated);
            });
        }

        builder.filter(ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            if (log.isDebugEnabled()) {
                log.debug("[AI-HTTP] {} {}", clientRequest.method(), clientRequest.url());
            }
            return Mono.just(clientRequest);
        }));

        return builder.build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
