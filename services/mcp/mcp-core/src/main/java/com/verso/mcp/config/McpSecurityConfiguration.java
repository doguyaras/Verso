package com.verso.mcp.config;

import com.verso.mcp.McpProperties;
import com.verso.mcp.web.McpAuthenticationEntryPoint;
import com.verso.mcp.web.McpRequestGuard;
import com.verso.platform.security.config.PlatformSecurityAutoConfiguration;
import com.verso.platform.security.jwt.JwtValidation;
import com.verso.platform.security.jwt.TrustedIssuer;
import com.verso.platform.security.jwt.TrustedIssuers;
import com.verso.platform.security.jwt.VersoJwtProperties;
import com.verso.platform.security.web.EnvelopeAccessDeniedHandler;
import com.verso.platform.security.web.EnvelopeAuthenticationEntryPoint;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The MCP endpoint's own security chain (ADR-0031, security-rules 2): an OAuth 2.1 resource server built from the
 * platform's token rules (the trusted issuers of ADR-0041, each with its own algorithms, {@code typ} and claims, bounded
 * {@code sub}, the same cached JWKS settings) with one difference, the audience: an MCP token must carry
 * {@code verso.mcp.audience} (Entra ID: {@code verso.security.jwt.entra.mcp-audiences}). The API's chain still requires
 * the API audience, so a token for one surface is refused on the other, for every issuer.
 *
 * <p>The chain matches /mcp and the RFC 9728 metadata only, with a lower order than the platform's catch-all, which it
 * never replaces (ADR-0010). Its decoder is not a {@link JwtDecoder} bean: one would make the platform's
 * {@code @ConditionalOnMissingBean} decoder back off and put the MCP audience on the whole API. Roles and groups come
 * from the platform's converter, so the caller's principals are exactly the panel's (ADR-0019, ADR-0028).
 *
 * <p>No dynamic client registration and no token passthrough: Verso is the resource, not an authorization server, and
 * it calls nothing on the caller's behalf.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "verso.mcp", name = "enabled", havingValue = "true")
public class McpSecurityConfiguration {

    /** Before the platform's API chain, which stays the last catch-all. */
    public static final int MCP_CHAIN_ORDER = PlatformSecurityAutoConfiguration.API_CHAIN_ORDER - 10;

    @Bean
    @Order(MCP_CHAIN_ORDER)
    SecurityFilterChain versoMcpSecurity(HttpSecurity http, VersoJwtProperties jwt, TrustedIssuers trustedIssuers,
                                         McpProperties mcp, Clock clock,
                                         JwtAuthenticationConverter authenticationConverter,
                                         EnvelopeAuthenticationEntryPoint envelope,
                                         EnvelopeAccessDeniedHandler deniedHandler,
                                         @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
                                         MeterRegistry meters) throws Exception {
        // Every trusted issuer with its MCP audience only (ADR-0041): the primary issuer's is verso.mcp.audience, Entra
        // ID's its own application id; an issuer without one is not trusted here.
        List<TrustedIssuer> issuers = trustedIssuers.forMcp(mcp.audience());
        JwtDecoder decoder = JwtValidation.decoder(issuers, TrustedIssuer::mcpAudiences, jwt, clock);
        McpAuthenticationEntryPoint entryPoint = new McpAuthenticationEntryPoint(envelope, mcp.metadataUrl(), mcp.scope());
        String metadata = McpProperties.METADATA_PATH;
        // Before the platform's chain for the same metadata path (PlatformSecurityAutoConfiguration, 404 there).
        http.securityMatcher("/mcp", "/mcp/**", metadata, metadata + "/**")
                .authorizeHttpRequests(requests -> {
                    // Discovery happens before sign-in: the metadata is public and the same for everyone.
                    requests.requestMatchers(metadata, metadata + "/**").permitAll();
                    requests.anyRequest().authenticated();
                })
                .oauth2ResourceServer(resource -> resource
                        .jwt(token -> token.decoder(decoder).jwtAuthenticationConverter(authenticationConverter))
                        // RFC 9728 metadata (MCP authorization: servers MUST serve it), written by Spring Security's
                        // own filter before authentication. Its defaults are replaced: the resource comes from
                        // configuration, not the request's Host, and Verso issues no certificate-bound tokens.
                        .protectedResourceMetadata(prm -> prm.protectedResourceMetadataCustomizer(builder -> {
                            builder.resource(mcp.resource().toString());
                            issuers.forEach(issuer -> builder.authorizationServer(issuer.issuer()));
                            builder.scope(mcp.scope())
                                    .resourceName("Verso")
                                    .claims(claims -> claims.remove("tls_client_certificate_bound_access_tokens"));
                        }))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler)
                        // As on the API chain: keys unreachable is 503 IDP_UNAVAILABLE, not a 500.
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
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterAfter(new McpRequestGuard(mcp.maxBodySize().toBytes(), mcp.maxConcurrentCalls(), resolver,
                        meters), AuthorizationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
