package com.verso.document.service;

import com.verso.document.api.dto.CollectionGrantRequest;
import com.verso.document.api.dto.CollectionGrantResponse;
import com.verso.document.api.dto.CollectionRequest;
import com.verso.document.api.dto.CollectionResponse;
import com.verso.document.api.dto.PageResponse;
import java.util.List;
import java.util.UUID;

/**
 * Use cases of /v1/collections (ADR-0028). Reading: a collection exists for a caller whose principals match one of its
 * grants; verso-admin sees every collection to manage it. Managing (create, rename, delete, grants): verso-admin only;
 * the controller declares it and the service relies on that.
 */
public interface CollectionService {

    /** The grant and whether it was new (201) or already there (200). */
    record GrantResult(CollectionGrantResponse grant, boolean created) {
    }

    PageResponse<CollectionResponse> list(Caller caller, int page, int size);

    CollectionResponse get(Caller caller, UUID collectionId);

    CollectionResponse create(Caller caller, CollectionRequest request);

    CollectionResponse update(Caller caller, UUID collectionId, CollectionRequest request);

    void delete(Caller caller, UUID collectionId);

    List<CollectionGrantResponse> grants(Caller caller, UUID collectionId);

    GrantResult grant(Caller caller, UUID collectionId, CollectionGrantRequest request);

    void revoke(Caller caller, UUID collectionId, UUID grantId);
}
