package com.verso.connector.api.dto;

import com.verso.connector.api.enums.SharePointAuthMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The SharePoint Online / OneDrive part of a {@link ConnectorRequest} whose type is SHAREPOINT (ADR-0045). The client
 * secret or the private key is never part of it: the request's {@code credentialRef} names the secret file. The folder
 * inside each library is the request's {@code pathPrefix}, the filters its {@code includeGlobs}/{@code excludeGlobs}.
 *
 * @param siteUrl               the site, e.g. {@code https://contoso.sharepoint.com/sites/hr} or a OneDrive
 *                              {@code https://contoso-my.sharepoint.com/personal/ayse_contoso_com}; https, no port, user
 *                              info, query or fragment. Its host must be a SharePoint host the installation allows
 * @param siteId                or the Graph site id ({@code host,site-guid,web-guid}); one of the two is required, and
 *                              when both are given they must name the same host
 * @param drives                document library names or drive ids, at most 10; empty: the site's default library
 * @param tenantId              the Entra directory (tenant) id; null: the installation's Entra tenant (ADR-0041)
 * @param clientId              the connector's own app registration (application id), not the sign-in app's
 * @param authMethod            CERTIFICATE (recommended, private_key_jwt) or CLIENT_SECRET
 * @param skipUniquePermissions files whose permissions differ from the library's (unique permissions, sharing links)
 *                              are not synced; default true (the v1 safety rule: SharePoint permissions are not mirrored)
 */
public record SharePointRequest(
        @Size(max = 2048) String siteUrl,
        @Size(max = 400)
        @Pattern(regexp = "[A-Za-z0-9.-]{1,253},[0-9A-Fa-f-]{36},[0-9A-Fa-f-]{36}", message = "must be a Graph site id")
        String siteId,
        @Size(max = 10) List<@NotNull @Size(min = 1, max = 255)
        @Pattern(regexp = "[^\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]+",
                message = "must not contain control characters or bidirectional overrides") String> drives,
        @Pattern(regexp = "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}",
                message = "must be a directory (tenant) GUID") String tenantId,
        @NotNull
        @Pattern(regexp = "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}",
                message = "must be an application (client) GUID") String clientId,
        @NotNull SharePointAuthMethod authMethod,
        Boolean skipUniquePermissions) {
}
