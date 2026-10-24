package com.verso.mcp.config;

import com.verso.mcp.McpProperties;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Two switches turn the MCP server on (ADR-0031): Verso's verso.mcp.enabled (security chain, tools, metadata) and Spring
 * AI's spring.ai.mcp.server.enabled (the transport). They must agree, or the transport could answer without Verso's
 * audience check or its tools. The server must be the stateless synchronous Streamable HTTP one with tools only: the
 * stateless server runs a tool on the request thread, where the caller's identity is (spring.ai.mcp.server type and
 * protocol); a session-based or asynchronous server would not.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(McpProperties.class)
public class McpModeCheck {

    @Bean
    InitializingBean mcpModeVerification(McpProperties properties, Environment environment) {
        return () -> verify(properties.enabled(), environment);
    }

    static void verify(boolean enabled, Environment environment) {
        boolean transport = environment.getProperty("spring.ai.mcp.server.enabled", Boolean.class, true);
        if (enabled != transport) {
            throw new IllegalStateException("verso.mcp.enabled and spring.ai.mcp.server.enabled must be the same");
        }
        if (!enabled) return;
        require(environment, "spring.ai.mcp.server.protocol", "STATELESS");
        require(environment, "spring.ai.mcp.server.type", "SYNC");
        require(environment, "spring.ai.mcp.server.stdio", "false");
        require(environment, "spring.ai.mcp.server.capabilities.resource", "false");
        require(environment, "spring.ai.mcp.server.capabilities.prompt", "false");
        require(environment, "spring.ai.mcp.server.capabilities.completion", "false");
        require(environment, "spring.ai.mcp.server.annotation-scanner.enabled", "false");
        require(environment, "spring.ai.mcp.server.tool-callback-converter", "false");
        require(environment, "spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp");
    }

    private static void require(Environment environment, String key, String expected) {
        String value = environment.getProperty(key);
        if (value == null || !value.strip().toUpperCase(Locale.ROOT).equals(expected.toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException(key + " must be " + expected + " for the MCP server (ADR-0031)");
        }
    }
}
