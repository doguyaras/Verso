package com.verso.connector.service;

import com.verso.connector.api.dto.ConfluenceResponse;
import com.verso.connector.api.dto.ConnectorItemResponse;
import com.verso.connector.api.dto.ConnectorResponse;
import com.verso.connector.api.dto.SharePointResponse;
import com.verso.connector.api.dto.SyncRunResponse;
import com.verso.connector.api.enums.ConfluenceDeployment;
import com.verso.connector.api.enums.ConnectorType;
import com.verso.connector.api.enums.ItemState;
import com.verso.connector.api.enums.ListingMode;
import com.verso.connector.api.enums.SharePointAuthMethod;
import com.verso.connector.api.enums.SyncRunStatus;
import com.verso.connector.domain.HealthRule;
import com.verso.connector.repository.ConnectorItemRepository.Counts;
import com.verso.connector.repository.ConnectorItemRepository.ItemRow;
import com.verso.connector.repository.ConnectorRow;
import com.verso.connector.repository.SyncRunRepository.RunRow;
import java.time.Instant;

/** Rows to responses (ADR-0038). There is no password to map: the row never held one. */
public final class ConnectorMapper {

    private ConnectorMapper() {}

    public static ConnectorResponse toResponse(ConnectorRow c, Counts counts, int staleFactor, Instant now) {
        return new ConnectorResponse(c.id(), c.name(), c.host(), c.port(), c.share(), c.pathPrefix(), c.domain(),
                c.username(), c.credentialRef(), c.collectionId(), c.includeGlobs(), c.excludeGlobs(), c.maxFileBytes(),
                c.scheduleMinutes(), c.enabled(),
                HealthRule.health(c.enabled(), c.deleting(), c.createdAt(), c.lastSuccessAt(), c.consecutiveFailures(),
                        c.scheduleMinutes(), staleFactor, now),
                c.lastSuccessAt(), c.lastFailureAt(), c.consecutiveFailures(), c.lastErrorCode(), c.nextRunAt(),
                new ConnectorResponse.ItemCounts(counts.synced(), counts.failed(), counts.skipped()), c.createdAt(),
                c.updatedAt(), ConnectorType.valueOf(c.type()), confluence(c), sharePoint(c));
    }

    private static SharePointResponse sharePoint(ConnectorRow c) {
        ConnectorRow.SharePoint s = c.sharePoint();
        if (s == null) return null;
        return new SharePointResponse(s.siteUrl(), s.siteId(), s.drives(), s.tenantId(), s.clientId(),
                SharePointAuthMethod.valueOf(s.authMethod()), s.skipUnique(), c.fullListingAt());
    }

    private static ConfluenceResponse confluence(ConnectorRow c) {
        ConnectorRow.Confluence s = c.confluence();
        if (s == null) return null;
        return new ConfluenceResponse(s.baseUrl(), ConfluenceDeployment.valueOf(s.deployment()), s.spaceKeys(), s.label(),
                s.ancestorId(), s.includeAttachments(), s.skipRestricted(), s.pageSize(), c.fullListingAt());
    }

    public static SyncRunResponse toResponse(RunRow r) {
        return new SyncRunResponse(r.id(), SyncRunStatus.valueOf(r.status()), r.startedAt(), r.finishedAt(),
                r.errorCode(), r.listingComplete(), r.listed(), r.added(), r.updated(), r.deleted(), r.unchanged(),
                r.failed(), r.skipped(), r.deferred(), ListingMode.valueOf(r.listingMode()),
                r.pendingDeletions());
    }

    public static ConnectorItemResponse toResponse(ItemRow i) {
        return new ConnectorItemResponse(i.path(), ItemState.valueOf(i.state()), i.failureCode(), i.attempts(),
                i.nextAttemptAt(), i.sizeBytes(), i.modifiedAt(), i.documentId(), i.updatedAt());
    }
}
