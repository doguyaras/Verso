/**
 * Document module (ADR-0001, ADR-0011): upload, PDF text extraction, chunking, embeddings and the ingestion worker.
 * Other modules use only {@code com.verso.document.api} (named interface "api", ADR-0007 #24); this module depends on
 * no other application module except the api of the audit module, where it publishes audit events (ADR-0026).
 */
@ApplicationModule(displayName = "Document", allowedDependencies = {"audit :: api"})
package com.verso.document;

import org.springframework.modulith.ApplicationModule;
