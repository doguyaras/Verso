/**
 * Document module (ADR-0001, ADR-0011): upload, PDF text extraction, chunking, embeddings and the ingestion worker.
 * Other modules use only {@code com.verso.document.api} (named interface "api", ADR-0007 #24); this module depends on
 * no other application module.
 */
@ApplicationModule(displayName = "Document", allowedDependencies = {})
package com.verso.document;

import org.springframework.modulith.ApplicationModule;
