package com.verso.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds of shared collections (ADR-0028). Starting values, not business rules a reader sees: they keep the
 * denormalised access list (documents x grants rows in document_principal) and one grant change's transaction bounded.
 *
 * @param maxCollections            collections per installation
 * @param maxGrantsPerCollection    groups and users one collection is granted to
 * @param maxDocumentsPerCollection documents one collection holds; a grant change writes one row per document
 */
@ConfigurationProperties("verso.document.collections")
public record CollectionProperties(
        @DefaultValue("500") int maxCollections,
        @DefaultValue("100") int maxGrantsPerCollection,
        @DefaultValue("5000") int maxDocumentsPerCollection) {

    public CollectionProperties {
        if (maxCollections < 1 || maxCollections > 10_000) {
            throw new IllegalStateException("verso.document.collections.max-collections must be 1..10000");
        }
        if (maxGrantsPerCollection < 1 || maxGrantsPerCollection > 1_000) {
            throw new IllegalStateException("verso.document.collections.max-grants-per-collection must be 1..1000");
        }
        if (maxDocumentsPerCollection < 1 || maxDocumentsPerCollection > 50_000) {
            throw new IllegalStateException("verso.document.collections.max-documents-per-collection must be 1..50000");
        }
    }
}
