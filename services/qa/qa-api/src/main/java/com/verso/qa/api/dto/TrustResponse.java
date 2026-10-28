package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * GET /v1/admin/trust (ADR-0050): what the running application knows about where data can go, for verso-admin. Facts
 * only: configuration the admin already set and counts. No secret, no secret file name, no content, no address beyond
 * the hosts an admin configured. Fields may be added; clients ignore ones they do not know.
 *
 * @param generatedAt when the answer was made
 * @param ai          the AI mode and models
 * @param egress      connector paths to the internet
 * @param connectors  configured connector types
 * @param mcp         the MCP server state
 * @param identity    the trusted token issuers
 * @param build       version and image
 * @param audit       audit log state
 * @param backup      backup state
 * @param guarantee   the strict local mode statement of the installation (fixed Turkish text, llm-rules 1.1)
 * @param models      the enabled model provider connections of the gateway and where each sends data (ADR-0056)
 */
public record TrustResponse(Instant generatedAt, Ai ai, Egress egress, Connectors connectors, Mcp mcp, Identity identity,
                            Build build, Audit audit, Backup backup, String guarantee, Models models) {

    /**
     * @param known       false when the connection list could not be read; then the list says nothing
     * @param connections the enabled connections, by name, type, locality and the host they reach
     */
    public record Models(boolean known, List<Connection> connections) {
        public Models {
            connections = List.copyOf(connections);
        }

        /** @param locality LOCAL or EXTERNAL; region: the provider region or data zone the admin set (may be null) */
        public record Connection(String name, String type, String locality, String host, String region) {
        }
    }

    /**
     * @param mode           {@code local} or {@code cloud}
     * @param chatProvider   the provider of the chat model ({@code ollama}, {@code anthropic}, {@code openai})
     * @param chatModel      the chat model id
     * @param embeddingModel the embedding model id (always local, llm-rules 1.2)
     */
    public record Ai(String mode, String chatProvider, String chatModel, String embeddingModel) {
    }

    /**
     * @param externalConnectorEgress whether connectors may reach the internet (never true in local mode)
     * @param allowedHosts            the host patterns they may reach; empty when egress is off
     */
    public record Egress(boolean externalConnectorEgress, List<String> allowedHosts) {
        public Egress {
            allowedHosts = List.copyOf(allowedHosts);
        }
    }

    /**
     * @param types                 distinct types of the configured connectors ({@code SMB}, {@code CONFLUENCE}, {@code SHAREPOINT})
     * @param cloudConnectorPresent whether one of them reads from the internet (Confluence Cloud, SharePoint Online)
     * @param known                 false when the connector list could not be read; types and the flag then say nothing
     */
    public record Connectors(List<String> types, boolean cloudConnectorPresent, boolean known) {
        public Connectors {
            types = List.copyOf(types);
        }
    }

    /**
     * @param enabled  whether /mcp is served
     * @param audience the token audience /mcp requires; null when it is off
     */
    public record Mcp(boolean enabled, String audience) {
    }

    /** @param issuers the trusted issuers, by fixed label and host only (never a path, a key address or a client id) */
    public record Identity(List<Issuer> issuers) {
        public Identity {
            issuers = List.copyOf(issuers);
        }

        public record Issuer(String name, String host) {
        }
    }

    /**
     * @param version     the application version; null without build information
     * @param builtAt     when it was built; null without build information
     * @param imageDigest {@code sha256:<64 hex>} of the running image when the installation passes it
     *                    ({@code VERSO_IMAGE_DIGEST}); null otherwise
     */
    public record Build(String version, Instant builtAt, String imageDigest) {
    }

    /**
     * @param lastEventAt            time of the newest audit event; null: none yet or unreadable
     * @param outboxOldestAgeSeconds age of the oldest undelivered outbox row of any module; null: nothing waits
     */
    public record Audit(Instant lastEventAt, Long outboxOldestAgeSeconds) {
    }

    /**
     * @param lastSuccessAt the last successful backup; null: the application cannot read it
     * @param verification  where the answer is found when null (fixed text)
     */
    public record Backup(Instant lastSuccessAt, String verification) {
    }
}
