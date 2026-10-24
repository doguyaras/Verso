package com.verso.connector.api.enums;

/**
 * Where a Confluence site runs (ADR-0044); it decides the authentication and the egress rule.
 * <ul>
 *   <li>{@code DATA_CENTER}: Data Center or Server inside the organisation; a personal access token (Bearer); only the
 *       installation's private-network allowlist is reachable, like SMB.</li>
 *   <li>{@code CLOUD}: {@code *.atlassian.net}; the account's e-mail and an API token (Basic); internet egress, allowed
 *       only when the installation enabled external connector egress for that host, never in strict local mode.</li>
 * </ul>
 */
public enum ConfluenceDeployment {
    DATA_CENTER,
    CLOUD
}
