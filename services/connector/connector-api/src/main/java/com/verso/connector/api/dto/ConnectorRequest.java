package com.verso.connector.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Create or replace an SMB connector (ADR-0038, verso-admin only). The password is never part of a request: it lives in
 * a secret file under /run/secrets whose name {@code credentialRef} gives (security-rules 1). Shapes are checked here;
 * the path prefix, the globs, the host against the egress allowlist and the size against the pipeline limit are checked
 * by the server.
 *
 * @param name            shown to admins only; 1-100 characters, unique ignoring case
 * @param host            the file server: a DNS name or an IP literal, no scheme, port, path or credentials
 * @param port            SMB port, default 445
 * @param share           the share name, no slashes
 * @param pathPrefix      folder inside the share to mirror ("" or null: the whole share); relative, "/" or "\"
 *                        separated, no "." or ".." segment, no ":" (alternate data streams)
 * @param domain          NTLM domain of the service account; null or "" for a local account
 * @param username        the read-only service account
 * @param credentialRef   name of the secret file holding that account's password: {@code SECRET_CONNECTOR_*}
 * @param collectionId    target collection (ADR-0028): its grants decide who reads the synced files; fixed after
 *                        creation
 * @param includeGlobs    only matching paths are synced (empty: every supported file); "*", "**", "?", "{a,b}"
 * @param excludeGlobs    matching paths are never synced, even when included
 * @param maxFileBytes    larger files are skipped; default and upper bound: the pipeline's limit (ADR-0016)
 * @param scheduleMinutes how often a sync runs, 5-10080 (one week)
 * @param enabled         false pauses the schedule; default true
 */
public record ConnectorRequest(
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "[^\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]*",
                message = "must not contain control characters or bidirectional overrides")
        String name,
        @NotBlank @Size(max = 253)
        @Pattern(regexp = "[A-Za-z0-9.:-]+", message = "must be a host name or an IP address")
        String host,
        @Min(1) @Max(65535) Integer port,
        @NotBlank @Size(max = 80)
        @Pattern(regexp = "[A-Za-z0-9_.$ -]+", message = "must be a share name")
        String share,
        @Size(max = 1024) String pathPrefix,
        @Size(max = 253)
        @Pattern(regexp = "[A-Za-z0-9.-]*", message = "must be a domain name")
        String domain,
        @NotBlank @Size(max = 104)
        @Pattern(regexp = "[A-Za-z0-9._@$ -]+", message = "must be an account name")
        String username,
        @NotBlank
        @Pattern(regexp = "SECRET_CONNECTOR_[A-Z0-9_]{1,60}", message = "must name a SECRET_CONNECTOR_* secret file")
        String credentialRef,
        @NotNull UUID collectionId,
        @Size(max = 20) List<@NotBlank @Size(max = 200) String> includeGlobs,
        @Size(max = 20) List<@NotBlank @Size(max = 200) String> excludeGlobs,
        @Min(1) Long maxFileBytes,
        @NotNull @Min(5) @Max(10080) Integer scheduleMinutes,
        Boolean enabled) {
}
