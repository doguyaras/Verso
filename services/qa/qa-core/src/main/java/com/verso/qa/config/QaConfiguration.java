package com.verso.qa.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Question answering wiring: the mode check, the outbound address filter, the X-Rag-Mode header and the model info of
 * /actuator/info (ADR-0006, ADR-0013, llm-rules 1.1/1.3).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({QaProperties.class, QaFollowUpProperties.class, QaGroundingProperties.class,
        QaKnowledgeProperties.class, QaDiagnosticsProperties.class, QaSkillProperties.class,
        QaSystemModelProperties.class, QaReasoningProperties.class, VersoAiProperties.class,
        QaGatewayProperties.class})
public class QaConfiguration {

    public static final String MODE_HEADER = "X-Rag-Mode";

    /** The application does not start when its model settings contradict verso.ai.mode (AiModeCheck). */
    @Bean
    InitializingBean aiModeCheck(VersoAiProperties ai, Environment environment) {
        return () -> AiModeCheck.verify(ai.mode(), environment);
    }

    /**
     * Every HTTP client Boot builds (the Ollama client among them) connects to internal addresses only: loopback,
     * link-local and private ranges (ADR-0006 decision 1.2). A model server that resolves to a public address is
     * refused on connect, in both modes. The cloud providers' SDKs bring their own HTTP stack and are not affected;
     * in local mode only the OpenAI-compatible one may exist (AiModeCheck), with the guard below.
     */
    @Bean
    InetAddressFilter outboundAddressFilter() {
        return InetAddressFilter.internalAddresses();
    }

    /**
     * The OpenAI-compatible client in local mode talks to a model server on this host's internal network (ADR-0051):
     * its own HTTP stack gets the same internal-addresses-only rule. In cloud mode it reaches the provider and is left
     * alone (ADR-0013).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OpenAiHttpClientBuilderCustomizer.class)
    // Component scanning finds this nested class on its own: it needs the outer class's condition, which is where
    // VersoAiProperties is enabled (a non-web context, such as the migrate run, has neither).
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class LocalOpenAiEgressConfiguration {

        @Bean
        OpenAiHttpClientBuilderCustomizer localModelEgress(ObjectProvider<VersoAiProperties> ai) {
            return builder -> ai.ifAvailable(properties -> localModelEgress(properties).customize(builder));
        }

        static OpenAiHttpClientBuilderCustomizer localModelEgress(VersoAiProperties ai) {
            return builder -> {
                if (ai.mode() == AiMode.LOCAL) builder.interceptor(new LocalModelEgress());
            };
        }
    }

    /**
     * Every response says which mode answered (llm-rules 1.3), errors and security rejections included: the filter runs
     * before Spring Security and sets the header before anything is written.
     */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> ragModeHeaderFilter(VersoAiProperties ai) {
        return ragModeHeaderRegistration(ai, "ragModeHeaderFilter");
    }

    /** Shared with {@link QaManagementContextConfiguration}: the management port gets the same header. */
    static FilterRegistrationBean<OncePerRequestFilter> ragModeHeaderRegistration(VersoAiProperties ai, String name) {
        String mode = ai.mode().header();
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                response.setHeader(MODE_HEADER, mode);
                chain.doFilter(request, response);
            }

            @Override
            protected boolean shouldNotFilterErrorDispatch() {
                return false;
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName(name);
        return registration;
    }

    /** /actuator/info: the active mode and model names (ADR-0006), nothing else. */
    @Bean
    InfoContributor aiInfoContributor(VersoAiProperties ai) {
        return builder -> builder.withDetail("ai", Map.of(
                "mode", ai.mode().header(), "chatModel", ai.chatModel(), "embeddingModel", ai.embeddingModel()));
    }
}
