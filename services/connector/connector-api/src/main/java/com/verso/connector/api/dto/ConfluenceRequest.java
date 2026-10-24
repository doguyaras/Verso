package com.verso.connector.api.dto;

import com.verso.connector.api.enums.ConfluenceDeployment;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The Confluence part of a {@link ConnectorRequest} whose type is CONFLUENCE (ADR-0044). The token is never part of it:
 * the request's {@code credentialRef} names the secret file, and for Cloud the request's {@code username} is the
 * account's e-mail address.
 *
 * @param baseUrl            the site: {@code https://confluence.example.local} or {@code https://x.atlassian.net/wiki};
 *                           no user info, query or fragment. Data Center must resolve into the private allowlist; Cloud
 *                           must match the installation's external host allowlist
 * @param deployment         DATA_CENTER (personal access token) or CLOUD (e-mail + API token)
 * @param spaceKeys          the spaces to mirror, 1-20 keys
 * @param label              only pages with this label (optional)
 * @param ancestorId         only pages below this page id (optional)
 * @param includeAttachments also PDF and DOCX attachments of the synced pages; default false
 * @param skipRestricted     pages with any read restriction (own or inherited) are not synced; default true (the v1
 *                           safety rule: page restrictions are not mirrored)
 * @param pageSize           results per REST call, 1-250; default 50
 */
public record ConfluenceRequest(
        @NotBlank @Size(max = 2048) String baseUrl,
        @NotNull ConfluenceDeployment deployment,
        @NotEmpty @Size(max = 20)
        List<@NotBlank @Pattern(regexp = "[A-Za-z0-9_~.-]{1,100}", message = "must be a space key") String> spaceKeys,
        @Size(max = 255) @Pattern(regexp = "[\\p{L}\\p{N}_.:-]+", message = "must be a label") String label,
        @Positive Long ancestorId,
        Boolean includeAttachments,
        Boolean skipRestricted,
        @Min(1) @Max(250) Integer pageSize) {
}
