package com.verso.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.Passage;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.mcp.McpProperties;
import com.verso.mcp.service.McpAudit;
import com.verso.mcp.service.McpText;
import com.verso.mcp.service.McpToolService;
import com.verso.mcp.web.McpAuthenticationEntryPoint;
import com.verso.mcp.web.McpRequestGuard;
import com.verso.platform.core.exception.ServiceException;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.authority.VersoAuthorityProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** The MCP server's pure parts (ADR-0031): output text cleaning, the 401 challenge, the configuration bounds. */
class McpUnitTest {

    @Test
    void text_whenItHidesCharacters_dropsControlFormatAndTagCharactersButKeepsTurkish() {
        String hidden = "Yıllık‮ izin​ ğüşİÖç\u0007 󠁁󠁂 son";
        assertThat(McpText.line(hidden, 100)).isEqualTo("Yıllık izin ğüşİÖç son");
        assertThat(McpText.passage("satır 1\r\n\n\n\nsatır\t2 son", 100)).isEqualTo("satır 1\n\nsatır\t2\nson");
        assertThat(McpText.line("a\nb\tc", 100)).isEqualTo("a b c");
        assertThat(McpText.line(null, 10)).isEmpty();
        assertThat(McpText.line("lone \uD800 surrogate", 100)).isEqualTo("lone surrogate");
    }

    @Test
    void text_whenLongerThanTheLimit_isCutByCodePointsWithAnEllipsisAndNeverSplitsAPair() {
        String emoji = "😀".repeat(10);
        String cut = McpText.line(emoji, 5);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(5);
        assertThat(cut).isEqualTo("😀😀😀😀…");
        assertThat(McpText.passage("abcdef", 6)).isEqualTo("abcdef");
        assertThat(McpText.passage("abcdefg", 6)).isEqualTo("abcde…");
    }

    @Test
    void challenge_whenUnauthenticated_pointsToTheMetadataAndTheScope() throws Exception {
        McpAuthenticationEntryPoint entryPoint = new McpAuthenticationEntryPoint(
                (request, response, failure) -> response.setHeader("WWW-Authenticate", "Bearer"),
                "https://verso.example.com/.well-known/oauth-protected-resource/mcp", "verso-mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        entryPoint.commence(new MockHttpServletRequest(), response, new BadCredentialsException("x"));
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer resource_metadata="
                + "\"https://verso.example.com/.well-known/oauth-protected-resource/mcp\", scope=\"verso-mcp\"");

        McpAuthenticationEntryPoint invalid = new McpAuthenticationEntryPoint(
                (request, r, failure) -> r.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\""),
                "https://h/.well-known/oauth-protected-resource/mcp", "verso-mcp");
        MockHttpServletResponse second = new MockHttpServletResponse();
        invalid.commence(new MockHttpServletRequest(), second, new BadCredentialsException("x"));
        assertThat(second.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\", resource_metadata="
                + "\"https://h/.well-known/oauth-protected-resource/mcp\", scope=\"verso-mcp\"");
    }

    @Test
    void properties_whenEnabled_requireAnHttpsResourceOfTheirOwnAudience() {
        McpProperties ok = properties(true, "https://verso.example.com/mcp", "verso-mcp");
        assertThat(ok.metadataUrl()).isEqualTo("https://verso.example.com/.well-known/oauth-protected-resource/mcp");
        assertThat(properties(true, "http://localhost:8080/mcp", "verso-mcp").metadataUrl())
                .isEqualTo("http://localhost:8080/.well-known/oauth-protected-resource/mcp");
        assertThatThrownBy(() -> properties(true, "http://verso.example.com/mcp", "verso-mcp"))
                .hasMessageContaining("https");
        assertThatThrownBy(() -> properties(true, "https://verso.example.com/mcp", "verso-api"))
                .hasMessageContaining("audience");
        assertThatThrownBy(() -> properties(true, "https://verso.example.com/api", "verso-mcp"))
                .hasMessageContaining("resource");
        assertThatThrownBy(() -> properties(true, null, "verso-mcp")).hasMessageContaining("resource");
        // Off: nothing is required.
        assertThat(properties(false, null, "verso-api").enabled()).isFalse();
    }

    /** Security review B1: an unexpected failure (a database outage) never hands its message to the client's model. */
    @Test
    void tools_whenRetrievalFailsUnexpectedly_returnAFixedErrorWithoutTheExceptionMessage() {
        String leak = "jdbc:postgresql://db-internal.example:5432 SELECT content FROM document.document_chunk";
        DocumentRetrieval failing = new DocumentRetrieval() {
            @Override
            public List<RetrievedPassage> search(Reader reader, String question, int topK) {
                throw new IllegalStateException(leak);
            }

            @Override
            public com.verso.document.api.dto.QuestionEmbedding embed(String question) {
                throw new IllegalStateException(leak);
            }

            @Override
            public List<RetrievedPassage> search(Reader reader, String question,
                                                 com.verso.document.api.dto.QuestionEmbedding embedding, int topK) {
                throw new IllegalStateException(leak);
            }

            @Override
            public Optional<Passage> passage(Reader reader, UUID passageId) {
                throw new IllegalStateException(leak);
            }
        };
        List<Object> events = new java.util.ArrayList<>();
        McpToolService service = new McpToolService(failing,
                new CallerGroups(new VersoAuthorityProperties("realm_access.roles", "", "groups", "ROLE_", "GROUP_")),
                properties(true, "https://verso.example.com/mcp", "verso-mcp"), new McpAudit(events::add),
                JsonMapper.builder().build(), new SimpleMeterRegistry());
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject("acct-unit").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        try {
            for (CallToolResult result : List.of(service.search(Map.of("query", "izin")),
                    service.fetch(Map.of("id", UUID.randomUUID().toString())))) {
                assertThat(result.isError()).isTrue();
                assertThat(((TextContent) result.content().getFirst()).text())
                        .isEqualTo("The request could not be completed; try again shortly.").doesNotContain("jdbc");
            }
            assertThat(events).as("nothing was read, nothing audited").isEmpty();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Hata avı 2026-10-30: the guard decides by method, not by the path's spelling. The transport also answers
     * "/mcp;a=b" (path matching ignores matrix parameters), so that spelling must meet the same bounds.
     */
    @Test
    void guard_boundsEverySpellingOfACall_byBodySizeAndConcurrency() throws Exception {
        HandlerExceptionResolver resolver = (request, response, handler, failure) -> {
            response.setStatus(((ServiceException) failure).getErrorCode().getHttpStatus().value());
            return new ModelAndView();
        };
        McpRequestGuard guard = new McpRequestGuard(DataSize.ofKilobytes(1).toBytes(), 1, resolver,
                new SimpleMeterRegistry());

        MockHttpServletRequest large = call("/mcp;a=b", 2048);
        MockHttpServletResponse tooLarge = new MockHttpServletResponse();
        guard.doFilter(large, tooLarge, (request, response) -> fail("the body bound was not applied"));
        assertThat(tooLarge.getStatus()).isEqualTo(413);

        MockHttpServletResponse busy = new MockHttpServletResponse();
        List<String> seen = new ArrayList<>();
        guard.doFilter(call("/mcp", 10), new MockHttpServletResponse(), (request, response) -> {
            seen.add("first");
            // The only slot is held by this call: the next spelling of the same endpoint is refused at once.
            guard.doFilter(call("/mcp;a=b", 10), busy, (r, s) -> fail("the concurrency bound was not applied"));
        });
        assertThat(seen).containsExactly("first");
        assertThat(busy.getStatus()).isEqualTo(429);
        assertThat(busy.getHeader("Retry-After")).isEqualTo("1");

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/mcp");
        guard.doFilter(get, new MockHttpServletResponse(), (request, response) -> seen.add("get"));
        assertThat(seen).as("a GET is the transport's to refuse").containsExactly("first", "get");
    }

    private static MockHttpServletRequest call(String uri, int bodyBytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContent(new byte[bodyBytes]);
        return request;
    }

    private static McpProperties properties(boolean enabled, String resource, String audience) {
        return new McpProperties(enabled, resource == null ? null : URI.create(resource), audience, "verso-mcp", 5, 10,
                1000, 600, 4000, 0.3, 2, DataSize.ofKilobytes(32), true, List.of());
    }
}
