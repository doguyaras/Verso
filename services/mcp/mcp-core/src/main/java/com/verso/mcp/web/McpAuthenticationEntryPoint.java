package com.verso.mcp.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import jakarta.servlet.ServletException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * The platform's 401/503 in the common envelope (EnvelopeAuthenticationEntryPoint), plus what an MCP client needs to
 * find the authorization server (MCP authorization, RFC 9728 5.1): the challenge carries {@code resource_metadata} with
 * the metadata URL and the {@code scope} to ask for. Both come from configuration, never from the request.
 */
public final class McpAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String CHALLENGE = "WWW-Authenticate";

    private final AuthenticationEntryPoint envelope;
    private final String parameters;

    public McpAuthenticationEntryPoint(AuthenticationEntryPoint envelope, String metadataUrl, String scope) {
        this.envelope = envelope;
        this.parameters = "resource_metadata=\"" + metadataUrl + "\", scope=\"" + scope + "\"";
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException, ServletException {
        envelope.commence(request, new HttpServletResponseWrapper(response) {
            @Override
            public void setHeader(String name, String value) {
                super.setHeader(name, CHALLENGE.equalsIgnoreCase(name) ? extend(value) : value);
            }

            @Override
            public void addHeader(String name, String value) {
                super.addHeader(name, CHALLENGE.equalsIgnoreCase(name) ? extend(value) : value);
            }
        }, failure);
    }

    /** "Bearer" → "Bearer resource_metadata=..."; "Bearer error=..." → "Bearer error=..., resource_metadata=...". */
    String extend(String challenge) {
        if (challenge == null || !challenge.startsWith("Bearer")) return challenge;
        String rest = challenge.substring("Bearer".length()).strip();
        return rest.isEmpty() ? "Bearer " + parameters : "Bearer " + rest + ", " + parameters;
    }
}
