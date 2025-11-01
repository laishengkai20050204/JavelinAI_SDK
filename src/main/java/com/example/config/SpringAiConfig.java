package com.example.config;

import com.example.ai.tools.SpringAiToolAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.function.FunctionCallback;



import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.ai.model.function.FunctionCallingOptions;

import java.util.Collections;
import java.util.List;

/**
 * Spring AI wiring that keeps the existing controllers/services untouched while
 * delegating the actual chat execution to Spring AI {@link ChatClient}.
 *
 * <p>The {@link AiProperties#getMode()} decides which {@link ChatModel} is used.
 * Both OpenAI and Ollama starters contribute their respective model beans, so
 * the routing configuration simply injects whichever is required. Timeout,
 * retry and logging knobs remain in {@link AiProperties.Client}; the concrete
 * Spring AI model beans honour the Spring configuration properties (for example
 * {@code spring.ai.openai.chat.options.*}).</p>
 */
@Configuration
@EnableConfigurationProperties({AiProperties.class, AiMemoryProperties.class})
@Slf4j
public class SpringAiConfig {

    private final AiProperties properties;
    private final SpringAiToolAdapter toolAdapter;

    public SpringAiConfig(AiProperties properties, SpringAiToolAdapter toolAdapter) {
        this.properties = properties;
        this.toolAdapter = toolAdapter;
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public ChatClient chatClient(ChatModel chatModel) {
        List<FunctionCallback> callbacks = toolAdapter != null
                ? toolAdapter.functionCallbacks()
                : Collections.emptyList();
        if (!callbacks.isEmpty()) {
            log.info("Function callbacks available: {}", callbacks.stream().map(FunctionCallback::getName).toList());
        } else {
            log.info("No function callbacks registered; prompts will run without tool calling unless provided per request");
        }
        ChatClient.Builder builder = ChatClient.builder(chatModel);

        return builder.build();
    }
}
