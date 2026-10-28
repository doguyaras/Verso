/**
 * KVKK data-subject requests (ADR-0034): verso-admin finds, exports and erases what Verso holds about one token
 * subject, and redacts the stored answers that cited a deleted document. The module has no table and no schema: each
 * owner reads and writes its own schema behind its api contract ({@code DocumentSubjects}, {@code QaSubjects},
 * {@code AuditSubjects}, {@code WorkSubjects} of ADR-0057), so no module reaches into another's data (AGENTS.md section 4).
 */
@ApplicationModule(displayName = "Privacy", allowedDependencies = {"document :: api", "qa :: api", "audit :: api",
        "work :: api"})
package com.verso.privacy;

import org.springframework.modulith.ApplicationModule;
