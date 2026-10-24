package com.verso.mcp.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** ADR-0031: the two switches agree, and an enabled server is the stateless, synchronous, tools-only one. */
class McpModeCheckTest {

    @Test
    void verify_whenTheSwitchesDisagree_refusesToStart() {
        assertThatThrownBy(() -> McpModeCheck.verify(false, new MockEnvironment()))
                .hasMessageContaining("must be the same");
        assertThatThrownBy(() -> McpModeCheck.verify(true, new MockEnvironment()
                .withProperty("spring.ai.mcp.server.enabled", "false"))).hasMessageContaining("must be the same");
        assertThatCode(() -> McpModeCheck.verify(false, new MockEnvironment()
                .withProperty("spring.ai.mcp.server.enabled", "false"))).doesNotThrowAnyException();
    }

    @Test
    void verify_whenEnabled_requiresStatelessSyncToolsOnly() {
        assertThatCode(() -> McpModeCheck.verify(true, valid())).doesNotThrowAnyException();
        assertThatThrownBy(() -> McpModeCheck.verify(true, valid().withProperty("spring.ai.mcp.server.protocol", "STREAMABLE")))
                .hasMessageContaining("protocol");
        assertThatThrownBy(() -> McpModeCheck.verify(true, valid().withProperty("spring.ai.mcp.server.type", "ASYNC")))
                .hasMessageContaining("type");
        assertThatThrownBy(() -> McpModeCheck.verify(true,
                valid().withProperty("spring.ai.mcp.server.annotation-scanner.enabled", "true")))
                .hasMessageContaining("annotation-scanner");
        assertThatThrownBy(() -> McpModeCheck.verify(true,
                valid().withProperty("spring.ai.mcp.server.capabilities.resource", "true"))).hasMessageContaining("resource");
    }

    private static MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("spring.ai.mcp.server.enabled", "true")
                .withProperty("spring.ai.mcp.server.protocol", "STATELESS")
                .withProperty("spring.ai.mcp.server.type", "SYNC")
                .withProperty("spring.ai.mcp.server.stdio", "false")
                .withProperty("spring.ai.mcp.server.capabilities.resource", "false")
                .withProperty("spring.ai.mcp.server.capabilities.prompt", "false")
                .withProperty("spring.ai.mcp.server.capabilities.completion", "false")
                .withProperty("spring.ai.mcp.server.annotation-scanner.enabled", "false")
                .withProperty("spring.ai.mcp.server.tool-callback-converter", "false")
                .withProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp");
    }
}
