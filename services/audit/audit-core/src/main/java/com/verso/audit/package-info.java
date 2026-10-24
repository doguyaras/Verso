/**
 * Audit log (ADR-0026): who did what, as ids only. Other modules publish {@code AuditEvent}s from audit-api; this
 * module stores them append-only in its own schema "audit" and lets verso-admin read and export them. Depends on no
 * other application module (the document and qa modules depend on its api, so the reverse would be a cycle).
 */
@ApplicationModule(displayName = "Audit", allowedDependencies = {})
package com.verso.audit;

import org.springframework.modulith.ApplicationModule;
