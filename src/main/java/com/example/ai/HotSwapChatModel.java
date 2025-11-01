package com.example.ai;

import com.example.config.AiProperties;
import com.example.config.EffectiveProps;
import com.example.runtime.RuntimeConfigReloadedEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
// 注意：M5 的包名：OpenAiChatModel / OpenAiChatOptions 在 org.springframework.ai.openai
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
// 注意：OpenAiApi 在 org.springframework.ai.openai.api
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 适配 Spring AI 1.0.0-M5 的热更新 ChatModel：
 * - 不使用任何 Builder（M5 部分 Builder 在你环境中不可用）
 * - 通过监听 RuntimeConfigReloadedEvent 重建底层 OpenAiApi / OpenAiChatModel
 * - baseUrl / apiKey / model 运行时变更立即生效
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class HotSwapChatModel implements ChatModel {

    private final EffectiveProps effectiveProps;
    private final ObjectProvider<OllamaChatModel> ollamaProvider;

    private final AtomicReference<ChatModel> delegate = new AtomicReference<>();

    @PostConstruct
    public void init() {
        ChatModel model = buildModel();
        delegate.set(model);
        log.info("[HotSwapChatModel] initialized: mode={}, spring-ai={}, baseUrl={}, model={}",
                effectiveProps.mode(), getSpringAiVersion(), safe(effectiveProps.baseUrl()), safe(effectiveProps.model()));
    }

    @EventListener(RuntimeConfigReloadedEvent.class)
    public void onReload(RuntimeConfigReloadedEvent ev) {
        ChatModel model = buildModel();
        delegate.set(model);
        log.info("[HotSwapChatModel] reloaded: mode={}, spring-ai={}, baseUrl={}, model={}",
                effectiveProps.mode(), getSpringAiVersion(), safe(effectiveProps.baseUrl()), safe(effectiveProps.model()));
    }

    private ChatModel buildModel() {
        AiProperties.Mode mode = effectiveProps.mode();
        if (mode == AiProperties.Mode.OLLAMA) {
            OllamaChatModel m = ollamaProvider.getIfAvailable();
            if (m == null) throw new IllegalStateException("Ollama model not available on classpath/config.");
            return m;
        }
        return buildOpenAiModel();
    }

    /** 用 M5 可用的构造器组装 OpenAI Chat 模型（关键点：不用 Builder） */
    private ChatModel buildOpenAiModel() {
        String baseUrl = Objects.requireNonNullElse(effectiveProps.baseUrl(), "");
        String apiKey  = Objects.requireNonNullElse(effectiveProps.apiKey(), "");
        String modelId = Objects.requireNonNullElse(effectiveProps.model(), "gpt-4o-mini");

        // ★ M5 可用：最简单的 2 参构造器（baseUrl, apiKey）
        //   如果你后面要自定义超时/代理，再切换到 4 参构造 (baseUrl, apiKey, RestClient.Builder, WebClient.Builder)
        OpenAiApi openAiApi = new OpenAiApi(baseUrl, apiKey);

        // ★ 用 .model(...)（替代已弃用的 .withModel(...)）
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelId)
                .build();

        // ★ M5 可用：直接用构造器创建 ChatModel（不要用 new OpenAiChatModel.Builder()）
        return new OpenAiChatModel(openAiApi, options);
    }

    // === ChatModel 接口委托 ===
    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.get().call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.get().stream(prompt);
    }

    private static String safe(String v) {
        return v == null ? "<null>" : v;
    }

    private static String getSpringAiVersion() {
        Package p = OpenAiApi.class.getPackage();
        return p != null ? p.getImplementationVersion() : "unknown";
    }
}
