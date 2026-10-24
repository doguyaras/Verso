/**
 * Question answering (ADR-0006, ADR-0008): retrieval through the document module's contract, a prompt that treats the
 * passages as data, the configured chat model, citations built on the server. Reads no table of its own and none of
 * another module (ADR-0003). It publishes audit events through audit-api only (ADR-0026).
 */
@ApplicationModule(displayName = "Question answering", allowedDependencies = {"document :: api", "audit :: api"})
package com.verso.qa;

import org.springframework.modulith.ApplicationModule;
