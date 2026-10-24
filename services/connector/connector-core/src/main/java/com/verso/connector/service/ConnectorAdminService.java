package com.verso.connector.service;

import com.verso.connector.api.dto.ConnectorItemResponse;
import com.verso.connector.api.dto.ConnectorPageResponse;
import com.verso.connector.api.dto.ConnectorRequest;
import com.verso.connector.api.dto.ConnectorResponse;
import com.verso.connector.api.dto.SyncRunResponse;
import com.verso.connector.api.enums.ItemState;
import java.util.UUID;

/** /v1/admin/connectors use cases (ADR-0038); the controller has already required verso-admin. */
public interface ConnectorAdminService {

    ConnectorResponse create(String actorSub, ConnectorRequest request);

    ConnectorResponse update(UUID id, ConnectorRequest request);

    ConnectorResponse get(UUID id);

    ConnectorPageResponse<ConnectorResponse> list(int page, int size);

    /** Marks the connector deleted; the worker removes its documents and then the connector. Idempotent. */
    void delete(UUID id);

    /** Makes the connector due now; {@code confirmMassDeletion} lifts the deletion safety for that one run. */
    void requestSync(UUID id, boolean confirmMassDeletion);

    ConnectorPageResponse<SyncRunResponse> runs(UUID id, int page, int size);

    ConnectorPageResponse<ConnectorItemResponse> items(UUID id, ItemState state, int page, int size);
}
