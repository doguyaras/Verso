package com.verso.platform.security.config;

import com.verso.platform.security.jwt.JwtValidation;
import com.verso.platform.security.jwt.VersoJwtProperties;
import com.verso.platform.security.web.CurrentAccountArgumentResolver;
import com.verso.platform.security.web.CurrentAccountParameterCheck;
import com.verso.platform.security.web.EnvelopeAccessDeniedHandler;
import com.verso.platform.security.web.EnvelopeAuthenticationEntryPoint;
import com.verso.platform.security.web.EnvelopeRequestRejectedHandler;
import com.verso.platform.security.web.ManagementServerPort;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Starter entry point (reference 4.5, 9; ADR-0005): the application is an OAuth2 resource server. Every request needs a
 * valid access token, except the error dispatch. Stateless, no session, no CSRF (bearer tokens, no cookies), no form
 * or basic login. Boot's own resource server and default chain back off because both beans exist here; with a
 * JwtDecoder present Boot also never generates and logs a default user password.
 *
 * <p>The API chain is the catch-all and always present: it matches every request and runs last
 * ({@link #API_CHAIN_ORDER}). A module that needs other rules (a webhook, a panel) adds its own chain with a
 * {@code securityMatcher} and a lower {@code @Order}; it never replaces this one. A previous
 * {@code @ConditionalOnMissingBean(SecurityFilterChain.class)} made the first such chain switch the API's protection
 * off entirely (phase 3 reviews C1/S4: a token-less request reached a controller). Only a bean with this chain's name
 * replaces it.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(VersoJwtProperties.class)
public class PlatformSecurityAutoConfiguration {

    /** Order of the API chain: last, behind any more specific chain an application adds. */
    public static final int API_CHAIN_ORDER = Ordered.LOWEST_PRECEDENCE - 10;

    /**
     * EndpointRequest needs the actuator auto-configuration (ManagementPortType) at match time; without it there is no
     * health endpoint to open.
     */
    private static final boolean ACTUATOR_PRESENT = ClassUtils.isPresent(
            "org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType",
            PlatformSecurityAutoConfiguration.class.getClassLoader());

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(VersoJwtProperties properties, Clock clock) {
        return JwtValidation.decoder(properties, clock);
    }

    @Bean
    EnvelopeAuthenticationEntryPoint envelopeAuthenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return new EnvelopeAuthenticationEntryPoint(resolver);
    }

    @Bean
    ManagementServerPort managementServerPort() {
        return new ManagementServerPort();
    }

    @Bean
    EnvelopeAccessDeniedHandler envelopeAccessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return new EnvelopeAccessDeniedHandler(resolver);
    }

    @Bean
    @Order(API_CHAIN_ORDER)
    @ConditionalOnMissingBean(name = "versoApiSecurity")
    SecurityFilterChain versoApiSecurity(HttpSecurity http, JwtDecoder decoder,
                                         EnvelopeAuthenticationEntryPoint entryPoint,
                                         EnvelopeAccessDeniedHandler deniedHandler,
                                         ManagementServerPort managementPort) throws Exception {
        http.authorizeHttpRequests(requests -> {
                    // The container's error dispatch renders the envelope of a request that already failed.
                    requests.requestMatchers("/error").permitAll();
                    // Health probes (compose healthcheck, orchestrators) carry no token: only the health endpoint, only
                    // through the actuator's own matcher.
                    if (ACTUATOR_PRESENT) {
                        requests.requestMatchers(EndpointRequest.to("health")).permitAll();
                        // The Prometheus scrape carries none either, but only on the separate management port, which is
                        // never published (ADR-0004, ADR-0014); on a shared port it needs a token (phase 7 review B3).
                        requests.requestMatchers(new AndRequestMatcher(EndpointRequest.to("prometheus"),
                                managementPort::matches)).permitAll();
                    }
                    requests.anyRequest().authenticated();
                })
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler)
                        // The filter's default handler rethrows AuthenticationServiceException (keys unreachable),
                        // which ended as a 500; the entry point answers it with 503 IDP_UNAVAILABLE instead.
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                AuthenticationEntryPointFailureHandler failures =
                                        new AuthenticationEntryPointFailureHandler(entryPoint);
                                failures.setRethrowAuthenticationServiceException(false);
                                filter.setAuthenticationFailureHandler(failures);
                                return filter;
                            }
                        }))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /** Firewall rejections (non-normalised paths) in the envelope; picked up by Spring Security as a bean. */
    @Bean
    EnvelopeRequestRejectedHandler envelopeRequestRejectedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return new EnvelopeRequestRejectedHandler(resolver);
    }

    @Bean
    CurrentAccountParameterCheck currentAccountParameterCheck(ListableBeanFactory beans) {
        return new CurrentAccountParameterCheck(beans);
    }

    @Bean
    WebMvcConfigurer currentAccountResolver() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CurrentAccountArgumentResolver());
            }
        };
    }
}
