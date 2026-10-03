package com.verso.qa.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Question answering wiring, the X-Rag-Mode header and the model info of /actuator/info (ADR-0006, llm-rules 1.3). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({QaProperties.class, VersoAiProperties.class})
public class QaConfiguration {

    public static final String MODE_HEADER = "X-Rag-Mode";

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
