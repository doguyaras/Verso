package com.verso.connector.service.impl;

import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.connector.api.dto.ConfluenceRequest;
import com.verso.connector.api.dto.ConnectorItemResponse;
import com.verso.connector.api.dto.ConnectorPageResponse;
import com.verso.connector.api.dto.ConnectorRequest;
import com.verso.connector.api.dto.ConnectorResponse;
import com.verso.connector.api.dto.SyncRunResponse;
import com.verso.connector.api.enums.ConfluenceDeployment;
import com.verso.connector.api.enums.ConnectorType;
import com.verso.connector.api.enums.ItemState;
import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.confluence.ExternalEgressPolicy;
import com.verso.connector.domain.ConfluenceUrl;
import com.verso.connector.domain.GlobSet;
import com.verso.connector.domain.NetworkAllowlist;
import com.verso.connector.domain.SourcePaths;
import com.verso.connector.exception.ConnectorErrorCode;
import com.verso.connector.exception.ConnectorServiceException;
import com.verso.connector.outbox.ConnectorAudit;
import com.verso.connector.repository.ConnectorDatabase;
import com.verso.connector.repository.ConnectorItemRepository;
import com.verso.connector.repository.ConnectorRepository;
import com.verso.connector.repository.ConnectorRepository.Settings;
import com.verso.connector.repository.ConnectorRow;
import com.verso.connector.repository.SyncRunRepository;
import com.verso.connector.service.ConnectorAdminService;
import com.verso.connector.service.ConnectorMapper;
import com.verso.connector.smb.EgressGuard;
import com.verso.connector.smb.SourceException;
import com.verso.document.api.DocumentIngestion;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Connector administration (ADR-0038). Validates what the bean constraints cannot: the path prefix (no traversal), the
 * globs, an IP literal against the egress allowlist (a name is judged at sync time, its address may change), the size
 * against the pipeline limit and the target collection through document-api. Audits each change with the connector id
 * only (ADR-0026), in the transaction of the change (outbox, ADR-0039): never the host, share, account or credential
 * name.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConnectorAdminServiceImpl implements ConnectorAdminService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorAdminServiceImpl.class);
    private static final int MAX_PAGE = 10_000;
    private static final int MAX_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;
    /** An SMB account name: the API pattern minus "+", which only a Cloud e-mail may hold. */
    private static final Pattern SMB_ACCOUNT = Pattern.compile("[A-Za-z0-9._@$ -]{1,104}");
    /** A Confluence Cloud account: an e-mail address of the database column's characters. */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");

    private final ConnectorDatabase db;
    private final ConnectorRepository connectors;
    private final ConnectorItemRepository items;
    private final SyncRunRepository runs;
    private final DocumentIngestion documents;
    private final EgressGuard egress;
    private final ExternalEgressPolicy external;
    private final ConnectorProperties properties;
    private final ConnectorAudit audit;
    private final Clock clock;

    public ConnectorAdminServiceImpl(ConnectorDatabase db, ConnectorRepository connectors, ConnectorItemRepository items,
                                     SyncRunRepository runs, DocumentIngestion documents, EgressGuard egress,
                                     ExternalEgressPolicy external, ConnectorProperties properties, ConnectorAudit audit,
                                     Clock clock) {
        this.db = db;
        this.connectors = connectors;
        this.items = items;
        this.runs = runs;
        this.documents = documents;
        this.egress = egress;
        this.external = external;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public ConnectorResponse create(String actorSub, ConnectorRequest request) {
        Settings settings = validated(request);
        if (!documents.collectionExists(request.collectionId())) {
            throw rejected(ConnectorErrorCode.CONNECTOR_COLLECTION_NOT_FOUND, "COLLECTION_NOT_FOUND");
        }
        ConnectorRow created;
        try {
            created = db.inTransaction(status -> {
                connectors.lockInstallation();
                if (connectors.count() >= properties.maxConnectors()) {
                    throw rejected(ConnectorErrorCode.CONNECTOR_LIMIT_REACHED, "LIMIT_REACHED");
                }
                ConnectorRow row = connectors.insert(settings, request.collectionId(), actorSub, clock.instant());
                audit.append(AuditEvent.success(AuditAction.CONNECTOR_CREATED, AuditTargetType.CONNECTOR, row.id()));
                return row;
            });
        } catch (DuplicateKeyException e) {
            throw rejected(ConnectorErrorCode.CONNECTOR_NAME_TAKEN, "NAME_TAKEN");
        }
        log.info("Connector created: connectorId={} operation=create outcome=created", created.id());
        return response(created);
    }

    @Override
    public ConnectorResponse update(UUID id, ConnectorRequest request) {
        ConnectorRow current = existing(id);
        if (current.deleting()) throw rejected(ConnectorErrorCode.CONNECTOR_DELETING, "DELETING");
        if (!current.type().equals(type(request).name())) {
            // A connector's items, cursor and documents belong to one kind of source.
            throw rejected(ConnectorErrorCode.CONNECTOR_TYPE_IMMUTABLE, "TYPE_IMMUTABLE");
        }
        if (!current.collectionId().equals(request.collectionId())) {
            // Moving the target would leave the synced documents behind in the old collection under the old grants.
            throw rejected(ConnectorErrorCode.CONNECTOR_COLLECTION_IMMUTABLE, "COLLECTION_IMMUTABLE");
        }
        Settings settings = validated(request);
        ConnectorRow updated;
        try {
            updated = db.inTransaction(status -> {
                ConnectorRow row = connectors.update(id, settings, clock.instant())
                        .orElseThrow(() -> rejected(ConnectorErrorCode.CONNECTOR_DELETING, "DELETING"));
                audit.append(AuditEvent.success(AuditAction.CONNECTOR_UPDATED, AuditTargetType.CONNECTOR, id));
                return row;
            });
        } catch (DuplicateKeyException e) {
            throw rejected(ConnectorErrorCode.CONNECTOR_NAME_TAKEN, "NAME_TAKEN");
        }
        log.info("Connector updated: connectorId={} operation=update outcome=updated", id);
        return response(updated);
    }

    @Override
    public ConnectorResponse get(UUID id) {
        return response(existing(id));
    }

    @Override
    public ConnectorPageResponse<ConnectorResponse> list(int page, int size) {
        checkPage(page, size);
        List<ConnectorResponse> rows = connectors.list(page, size).stream().map(this::response).toList();
        return ConnectorPageResponse.of(rows, page, size, connectors.count());
    }

    @Override
    public void delete(UUID id) {
        ConnectorRow current = existing(id);
        if (current.deleting()) return;
        db.inTransaction(status -> {
            if (!connectors.markDeleting(id, clock.instant())) throw notFound();
            audit.append(AuditEvent.success(AuditAction.CONNECTOR_DELETED, AuditTargetType.CONNECTOR, id));
            return null;
        });
        log.info("Connector deletion requested: connectorId={} operation=delete outcome=accepted", id);
    }

    @Override
    public void requestSync(UUID id, boolean confirmMassDeletion) {
        boolean requested = db.inTransaction(status -> {
            if (!connectors.requestSync(id, confirmMassDeletion)) return false;
            audit.append(AuditEvent.success(AuditAction.CONNECTOR_SYNC_REQUESTED, AuditTargetType.CONNECTOR, id)
                    .withDetail(confirmMassDeletion ? "MASS_DELETION_CONFIRMED" : "SCHEDULED"));
            return true;
        });
        if (!requested) {
            if (existing(id).deleting()) throw rejected(ConnectorErrorCode.CONNECTOR_DELETING, "DELETING");
            throw notFound();
        }
        log.info("Connector sync requested: connectorId={} massDeletionConfirmed={} operation=sync outcome=accepted", id,
                confirmMassDeletion);
    }

    @Override
    public ConnectorPageResponse<SyncRunResponse> runs(UUID id, int page, int size) {
        checkPage(page, size);
        existing(id);
        return ConnectorPageResponse.of(runs.list(id, page, size).stream().map(ConnectorMapper::toResponse).toList(),
                page, size, runs.count(id));
    }

    @Override
    public ConnectorPageResponse<ConnectorItemResponse> items(UUID id, ItemState state, int page, int size) {
        checkPage(page, size);
        existing(id);
        String filter = state == null ? null : state.name();
        return ConnectorPageResponse.of(items.list(id, filter, page, size).stream().map(ConnectorMapper::toResponse)
                .toList(), page, size, items.count(id, filter));
    }

    private static ConnectorType type(ConnectorRequest r) {
        return r.type() == null ? ConnectorType.SMB : r.type();
    }

    private Settings validated(ConnectorRequest r) {
        return type(r) == ConnectorType.CONFLUENCE ? confluence(r) : smb(r);
    }

    /**
     * A Confluence connector (ADR-0044): the site URL strictly parsed (https, no credentials or query), Data Center
     * inside the private allowlist when given as an IP literal (a name is judged at sync time), Cloud only on a host the
     * installation's external egress allows; no SMB field.
     */
    private Settings confluence(ConnectorRequest r) {
        ConfluenceRequest c = r.confluence();
        boolean smbFields = !isBlank(r.host()) || !isBlank(r.share()) || !isBlank(r.pathPrefix()) || !isBlank(r.domain())
                || r.port() != null || (r.includeGlobs() != null && !r.includeGlobs().isEmpty())
                || (r.excludeGlobs() != null && !r.excludeGlobs().isEmpty());
        if (c == null || smbFields) throw rejected(ConnectorErrorCode.CONNECTOR_SETTINGS_INVALID, "SETTINGS_INVALID");
        ConfluenceUrl url;
        try {
            url = ConfluenceUrl.parse(c.baseUrl(), !properties.confluence().requireHttps());
        } catch (IllegalArgumentException e) {
            throw rejected(ConnectorErrorCode.CONNECTOR_URL_INVALID, e.getMessage());
        }
        boolean cloud = c.deployment() == ConfluenceDeployment.CLOUD;
        String username = isBlank(r.username()) ? null : r.username().strip();
        if (cloud) {
            if (username == null || !EMAIL.matcher(username).matches()) {
                throw rejected(ConnectorErrorCode.CONNECTOR_SETTINGS_INVALID, "SETTINGS_INVALID");
            }
            if (NetworkAllowlist.isLiteral(url.host()) || !external.allows(url.host())) {
                throw rejected(ConnectorErrorCode.CONNECTOR_EXTERNAL_EGRESS_DISABLED, "EXTERNAL_EGRESS_DISABLED");
            }
        } else {
            // A personal access token carries the identity; a user name would be ignored, so it is refused.
            if (username != null) throw rejected(ConnectorErrorCode.CONNECTOR_SETTINGS_INVALID, "SETTINGS_INVALID");
            checkLiteral(url.host());
        }
        List<String> keys = c.spaceKeys().stream().map(String::strip).distinct().toList();
        String label = isBlank(c.label()) ? null : c.label().strip();
        long maxFileBytes = maxFileBytes(r);
        return new Settings(r.name().strip(), url.host(), url.port(), null, "", null, username, r.credentialRef(),
                List.of(), List.of(), maxFileBytes, r.scheduleMinutes(), r.enabled() == null || r.enabled(),
                ConnectorRow.CONFLUENCE, new ConnectorRow.Confluence(url.text(), c.deployment().name(), keys, label,
                c.ancestorId(), Boolean.TRUE.equals(c.includeAttachments()), c.skipRestricted() == null || c.skipRestricted(),
                c.pageSize() == null ? DEFAULT_PAGE_SIZE : c.pageSize()));
    }

    private Settings smb(ConnectorRequest r) {
        if (r.confluence() != null || isBlank(r.host()) || isBlank(r.share()) || isBlank(r.username())
                || !SMB_ACCOUNT.matcher(r.username().strip()).matches()) {
            throw rejected(ConnectorErrorCode.CONNECTOR_SETTINGS_INVALID, "SETTINGS_INVALID");
        }
        String prefix;
        try {
            prefix = SourcePaths.normalizePrefix(r.pathPrefix());
        } catch (IllegalArgumentException e) {
            throw rejected(ConnectorErrorCode.CONNECTOR_PATH_INVALID, e.getMessage());
        }
        List<String> include = r.includeGlobs() == null ? List.of() : r.includeGlobs().stream().map(String::strip).toList();
        List<String> exclude = r.excludeGlobs() == null ? List.of() : r.excludeGlobs().stream().map(String::strip).toList();
        try {
            GlobSet.compile(include);
            GlobSet.compile(exclude);
        } catch (IllegalArgumentException e) {
            throw rejected(ConnectorErrorCode.CONNECTOR_GLOB_INVALID, "GLOB_INVALID");
        }
        String host = r.host().strip();
        checkLiteral(host);
        long maxFileBytes = maxFileBytes(r);
        String domain = r.domain() == null || r.domain().isBlank() ? null : r.domain().strip();
        return new Settings(r.name().strip(), host, r.port() == null ? 445 : r.port(), r.share().strip(), prefix, domain,
                r.username().strip(), r.credentialRef(), include, exclude, maxFileBytes, r.scheduleMinutes(),
                r.enabled() == null || r.enabled(), ConnectorRow.SMB, null);
    }

    /** An IP literal must lie in the private allowlist (a name is judged at every sync, its address may change). */
    private void checkLiteral(String host) {
        if (!NetworkAllowlist.isLiteral(host)) return;
        boolean allowed;
        try {
            allowed = egress.allowsLiteral(host);
        } catch (SourceException e) {
            allowed = false;
        }
        if (!allowed) throw rejected(ConnectorErrorCode.CONNECTOR_HOST_NOT_ALLOWED, "HOST_NOT_ALLOWED");
    }

    private long maxFileBytes(ConnectorRequest r) {
        long limit = documents.maxFileBytes();
        long maxFileBytes = r.maxFileBytes() == null ? limit : r.maxFileBytes();
        if (maxFileBytes > limit) throw rejected(ConnectorErrorCode.CONNECTOR_FILE_SIZE_INVALID, "FILE_SIZE_INVALID");
        return maxFileBytes;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ConnectorResponse response(ConnectorRow row) {
        return ConnectorMapper.toResponse(row, items.counts(row.id()), properties.staleFactor(), clock.instant());
    }

    private ConnectorRow existing(UUID id) {
        return connectors.find(id).orElseThrow(this::notFound);
    }

    private ConnectorServiceException notFound() {
        return rejected(ConnectorErrorCode.CONNECTOR_NOT_FOUND, "NOT_FOUND");
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw rejected(ConnectorErrorCode.CONNECTOR_PAGE_INVALID, "PAGE_INVALID");
        }
    }

    /** The global handler logs the rejection (code and reason) once (reference 7.4). */
    private static ConnectorServiceException rejected(ConnectorErrorCode code, String reason) {
        return new ConnectorServiceException(code, reason);
    }
}
