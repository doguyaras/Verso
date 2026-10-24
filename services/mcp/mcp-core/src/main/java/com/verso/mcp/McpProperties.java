package com.verso.mcp;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The MCP server (ADR-0031). Checked only when enabled, so a disabled installation needs none of it.
 *
 * @param enabled            the switch; it must agree with spring.ai.mcp.server.enabled ({@link McpModeCheck})
 * @param resource           the public URL of the MCP endpoint, e.g. https://verso.example.com/mcp: the resource of the
 *                           RFC 9728 metadata and where its pointer in WWW-Authenticate leads. From configuration, never
 *                           from a request header (forward-headers-strategy none)
 * @param audience           the {@code aud} an MCP access token must carry; never the API's own audience, so a panel
 *                           token is refused here and an MCP token is refused on /v1 (ADR-0010)
 * @param scope              the client scope the IdP maps to {@code audience}, advertised to clients
 * @param defaultResults     passages verso_search returns when the client does not say
 * @param maxResults         upper bound of the client's {@code top_k}
 * @param maxQueryChars      longest query, as for /v1/questions (llm-rules 3.5)
 * @param excerptMaxChars    code points of a search excerpt
 * @param passageMaxChars    code points of a fetched passage
 * @param minScore           passages below this cosine similarity are not returned: data minimisation, since the
 *                           client's model may run outside the installation
 * @param maxConcurrentCalls MCP requests in flight per instance; the rest answer 429 at once, so agents cannot take
 *                           every question-embedding slot of the panel's users (verso.document.retrieval)
 * @param maxBodySize        largest request body; a JSON-RPC call with a 1000 character query fits many times
 * @param showDocumentNames  whether results carry the document and collection names the caller may read (as the
 *                           panel's citations do); off, a result names a document by a short id only
 * @param allowedOrigins     browser origins allowed to call (DNS rebinding protection); empty: a request with an
 *                           Origin header is refused, remote and desktop MCP clients send none
 */
@ConfigurationProperties("verso.mcp")
public record McpProperties(
        @DefaultValue("false") boolean enabled,
        URI resource,
        @DefaultValue("verso-mcp") String audience,
        @DefaultValue("verso-mcp") String scope,
        @DefaultValue("5") int defaultResults,
        @DefaultValue("10") int maxResults,
        @DefaultValue("1000") int maxQueryChars,
        @DefaultValue("600") int excerptMaxChars,
        @DefaultValue("4000") int passageMaxChars,
        @DefaultValue("0.30") double minScore,
        @DefaultValue("2") int maxConcurrentCalls,
        @DefaultValue("32KB") DataSize maxBodySize,
        @DefaultValue("true") boolean showDocumentNames,
        @DefaultValue List<String> allowedOrigins) {

    /** RFC 9728 well-known prefix of the protected resource metadata; served public while the server is on. */
    public static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    /** The API's audience (config/verso.yml verso.security.jwt.audience); an MCP token must not carry only that. */
    static final String API_AUDIENCE = "verso-api";

    public McpProperties {
        allowedOrigins = allowedOrigins == null ? List.of()
                : allowedOrigins.stream().filter(o -> o != null && !o.isBlank()).map(String::strip).toList();
        if (enabled) {
            if (resource == null || !resource.isAbsolute() || resource.getHost() == null
                    || resource.getRawQuery() != null || resource.getRawFragment() != null
                    || resource.getRawPath() == null || !resource.getRawPath().endsWith("/mcp")) {
                throw new IllegalStateException("verso.mcp.resource must be the absolute URL of the MCP endpoint");
            }
            String scheme = resource.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !("http".equals(scheme) && isLoopback(resource.getHost()))) {
                throw new IllegalStateException("verso.mcp.resource must be https (http only on a loopback host)");
            }
            if (audience == null || audience.isBlank() || API_AUDIENCE.equals(audience)) {
                throw new IllegalStateException("verso.mcp.audience must be its own audience, not the API's");
            }
            if (scope == null || !scope.matches("[A-Za-z0-9._:-]{1,64}")) {
                throw new IllegalStateException("verso.mcp.scope must be a scope token");
            }
            if (defaultResults < 1 || maxResults < defaultResults || maxResults > 20) {
                throw new IllegalStateException("verso.mcp results must satisfy 1 <= default-results <= max-results <= 20");
            }
            if (maxQueryChars < 1 || maxQueryChars > 4000 || excerptMaxChars < 50 || passageMaxChars < excerptMaxChars
                    || passageMaxChars > 20_000) {
                throw new IllegalStateException("verso.mcp size limits are out of range");
            }
            if (minScore < 0 || minScore >= 1) throw new IllegalStateException("verso.mcp.min-score must be in [0, 1)");
            if (maxConcurrentCalls < 1) throw new IllegalStateException("verso.mcp.max-concurrent-calls must be positive");
            if (maxBodySize.toBytes() < 1024 || maxBodySize.toBytes() > DataSize.ofMegabytes(1).toBytes()) {
                throw new IllegalStateException("verso.mcp.max-body-size must be between 1KB and 1MB");
            }
        }
    }

    /** RFC 9728 3.1: the metadata of resource https://host/mcp lives at https://host/.well-known/oauth-protected-resource/mcp. */
    public String metadataUrl() {
        String path = resource.getRawPath() == null ? "" : resource.getRawPath();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return resource.getScheme() + "://" + resource.getRawAuthority() + METADATA_PATH + path;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }
}
