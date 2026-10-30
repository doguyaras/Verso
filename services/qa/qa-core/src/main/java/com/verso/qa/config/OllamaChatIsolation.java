package com.verso.qa.config;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * ADR-0062: the built-in Ollama chat model talks to its own model server ({@value #CHAT_BASE_URL}), not to the one
 * that embeds questions and documents ({@code spring.ai.ollama.base-url}). One Ollama process serving both let a stuck
 * chat generation stop every embedding (2026-10-29: 4 minutes without /api/embed, then 33 questions failed); two
 * processes in two containers keep the two bulkheads apart down to the model server's memory.
 *
 * <p>Spring AI's auto-configuration builds one {@link OllamaApi} for both models. The chat model it builds is rebuilt
 * here with the same options on its own {@link OllamaApi}, from Boot's builders, so the internal-addresses-only filter,
 * the timeouts and the Reactor Netty request factory (whose request an interrupt cancels; QuestionServiceImpl#call)
 * stay as they are. No client retry, as before (ADR-0008, {@code spring.ai.retry.max-attempts: 0}). An empty setting
 * keeps the shared server (an IDE run against one Ollama).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OllamaChatModel.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OllamaChatIsolation {

    public static final String CHAT_BASE_URL = "verso.ai.ollama-chat-base-url";

    /** Static: a post-processor is created before the other beans; the builders are fetched only when needed. */
    @Bean
    static BeanPostProcessor ollamaChatServer(Environment environment, ObjectProvider<RestClient.Builder> restClients,
                                              ObjectProvider<WebClient.Builder> webClients) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof OllamaChatModel model)) return bean;
                String baseUrl = environment.getProperty(CHAT_BASE_URL, "").strip();
                if (baseUrl.isEmpty()) return bean;
                return onOwnServer(model, baseUrl, restClients.getObject(), webClients.getObject());
            }
        };
    }

    /** The same model and options, on a client of its own aimed at {@code baseUrl}. */
    public static OllamaChatModel onOwnServer(OllamaChatModel model, String baseUrl, RestClient.Builder restClients,
                                              WebClient.Builder webClients) {
        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).restClientBuilder(restClients)
                .webClientBuilder(webClients).build();
        return OllamaChatModel.builder().ollamaApi(api).options(model.getOptions().mutate().build())
                .retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build())).build();
    }
}
