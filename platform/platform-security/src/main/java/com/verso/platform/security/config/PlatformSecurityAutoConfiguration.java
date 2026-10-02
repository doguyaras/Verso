package com.verso.platform.security.config;

import com.verso.platform.security.jwt.JwtValidation;
import com.verso.platform.security.jwt.VersoJwtProperties;
import com.verso.platform.security.web.CurrentAccountArgumentResolver;
import com.verso.platform.security.web.EnvelopeAccessDeniedHandler;
import com.verso.platform.security.web.EnvelopeAuthenticationEntryPoint;
import com.verso.platform.security.web.EnvelopeRequestRejectedHandler;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
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
 * <p>Runs before Boot's security auto-configurations through the bean conditions; the filter chain is an ordinary
 * bean so an application can still add a more specific chain in front of it.
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

    /** EndpointRequest needs actuator classes at match time; without actuator there is no health endpoint to open. */
    private static final boolean ACTUATOR_PRESENT = ClassUtils.isPresent(
            "org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints",
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
    EnvelopeAccessDeniedHandler envelopeAccessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return new EnvelopeAccessDeniedHandler(resolver);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain versoApiSecurity(HttpSecurity http, JwtDecoder decoder,
                                         EnvelopeAuthenticationEntryPoint entryPoint,
                                         EnvelopeAccessDeniedHandler deniedHandler) throws Exception {
        http.authorizeHttpRequests(requests -> {
                    // The container's error dispatch renders the envelope of a request that already failed.
                    requests.requestMatchers("/error").permitAll();
                    // Health probes (compose healthcheck, orchestrators) carry no token; only the health endpoint,
                    // only through the actuator's own matcher, which knows the separate management port.
                    if (ACTUATOR_PRESENT) requests.requestMatchers(EndpointRequest.to("health")).permitAll();
                    requests.anyRequest().authenticated();
                })
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
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
    WebMvcConfigurer currentAccountResolver() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CurrentAccountArgumentResolver());
            }
        };
    }
}
