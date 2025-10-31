package com.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.server.*;

@Configuration
public class ConsoleSpaConfig {
    @Bean
    public RouterFunction<ServerResponse> consoleSpaRouter() {
        var index = new ClassPathResource("static/console/index.html");
        return RouterFunctions
                .resources("/console/**", new ClassPathResource("static/"))
                .andRoute(RequestPredicates.GET("/console"), req -> ServerResponse.ok().bodyValue(index))
                .andRoute(RequestPredicates.GET("/console/{*path}"), req -> ServerResponse.ok().bodyValue(index));
    }
}
