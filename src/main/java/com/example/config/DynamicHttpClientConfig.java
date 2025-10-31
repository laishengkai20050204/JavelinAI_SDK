package com.example.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;

/**
 * Ensure outbound HTTP clients reflect runtime EffectiveProps for baseUrl and apiKey.
 * Applies to both WebClient and RestClient usage paths.
 */
@Configuration
public class DynamicHttpClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder(EffectiveProps effectiveProps, Environment env) {
        ExchangeFilterFunction dynamicAuthAndBaseUrl = (request, next) -> {
            String key = effectiveProps.apiKey();
            String targetBase = effectiveProps.baseUrl();

            ClientRequest.Builder b = ClientRequest.from(request);
            URI old = request.url();
            boolean isAiCall = isAiUpstream(old, targetBase, env);

            if (isAiCall) {
                if (StringUtils.hasText(key)) {
                    b.headers(h -> h.setBearerAuth(key));
                } else {
                    b.headers(h -> h.remove(HttpHeaders.AUTHORIZATION));
                }
            }

            if (isAiCall && StringUtils.hasText(targetBase)) {
                URI base = URI.create(targetBase);
                URI merged = UriComponentsBuilder.newInstance()
                        .scheme(base.getScheme())
                        .host(base.getHost())
                        .port(base.getPort())
                        .path(base.getPath())
                        .path(old.getPath())
                        .query(old.getQuery())
                        .build(true)
                        .toUri();
                b.url(merged);
            }

            return next.exchange(b.build());
        };

        return WebClient.builder().filter(dynamicAuthAndBaseUrl);
    }

    @Bean
    public RestClientCustomizer aiRestClientCustomizer(EffectiveProps effectiveProps, Environment env) {
        return builder -> builder.requestInterceptor((request, body, execution) ->
                executeWithRuntimeOverrides(effectiveProps, env, request, body, execution));
    }

    private ClientHttpResponse executeWithRuntimeOverrides(EffectiveProps props,
                                                           Environment env,
                                                           HttpRequest request,
                                                           byte[] body,
                                                           ClientHttpRequestExecution execution) throws IOException {
        String key = props.apiKey();
        String targetBase = props.baseUrl();

        HttpRequestWrapper wrapper = new HttpRequestWrapper(request) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                boolean isAiCall = isAiUpstream(getURI(), targetBase, env);
                if (isAiCall) {
                    if (StringUtils.hasText(key)) {
                        headers.setBearerAuth(key);
                    } else {
                        headers.remove(HttpHeaders.AUTHORIZATION);
                    }
                }
                return headers;
            }

            @Override
            public URI getURI() {
                URI old = super.getURI();
                if (!StringUtils.hasText(targetBase) || !isAiUpstream(old, targetBase, env)) return old;
                URI base = URI.create(targetBase);
                return UriComponentsBuilder.newInstance()
                        .scheme(base.getScheme())
                        .host(base.getHost())
                        .port(base.getPort())
                        .path(base.getPath())
                        .path(old.getPath())
                        .query(old.getQuery())
                        .build(true)
                        .toUri();
            }
        };
        return execution.execute(wrapper, body);
    }

    private boolean isAiUpstream(URI old, String targetBase, Environment env) {
        try {
            if (old == null) return false;
            String oldHost = old.getHost();
            if (!StringUtils.hasText(oldHost)) return false;
            String tbHost = null;
            if (StringUtils.hasText(targetBase)) tbHost = URI.create(targetBase).getHost();
            String openai = env.getProperty("spring.ai.openai.base-url");
            String ollama = env.getProperty("spring.ai.ollama.base-url");
            String openaiHost = (StringUtils.hasText(openai) ? URI.create(openai).getHost() : null);
            String ollamaHost = (StringUtils.hasText(ollama) ? URI.create(ollama).getHost() : null);
            if (tbHost != null && oldHost.equalsIgnoreCase(tbHost)) return true;
            if (openaiHost != null && oldHost.equalsIgnoreCase(openaiHost)) return true;
            if (ollamaHost != null && oldHost.equalsIgnoreCase(ollamaHost)) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
