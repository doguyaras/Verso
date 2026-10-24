/**
 * File-share connector (ADR-0038): mirrors an SMB share into a collection. Settings and per-file sync state live in the
 * module's own schema "connector"; documents are created and deleted only through document-api's
 * {@code DocumentIngestion} (the document module keeps parsing, chunking, embeddings and the reader rows), and every
 * admin change and sync is audited through audit-api. Nothing depends on this module.
 */
@ApplicationModule(displayName = "Connector", allowedDependencies = {"document :: api", "audit :: api"})
package com.verso.connector;

import org.springframework.modulith.ApplicationModule;
