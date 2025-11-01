package com.example.ai;

import com.example.config.AiProperties;
import com.example.config.EffectiveProps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import reactor.core.publisher.Flux;

/**
 * Delegates to OpenAI or Ollama model per call, based on EffectiveProps.mode().
 * No bean refresh required; switching takes effect immediately after /admin/config updates.
 */
@Slf4j
@RequiredArgsConstructor
@Component
@ConditionalOnMissingBean(org.springframework.ai.chat.model.ChatModel.class)
public class DelegatingChatModel implements ChatModel {

    private final ObjectProvider<OpenAiChatModel> openAiProvider;
    private final ObjectProvider<OllamaChatModel> ollamaProvider;
    private final EffectiveProps effectiveProps;

    private ChatModel current() {
        AiProperties.Mode mode = effectiveProps.mode();
        return switch (mode) {
            case OPENAI -> {
                ChatModel m = openAiProvider.getIfAvailable();
                if (m == null) throw new IllegalStateException("OpenAI model not available on classpath/config.");
                yield m;
            }
            case OLLAMA -> {
                ChatModel m = ollamaProvider.getIfAvailable();
                if (m == null) throw new IllegalStateException("Ollama model not available on classpath/config.");
                yield m;
            }
        };
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return current().call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return current().stream(prompt);
    }
}
