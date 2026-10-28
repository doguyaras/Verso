/**
 * Skill library (ADR-0057): task skills ("Beceriler") a user runs from a guided form. A run validates the form, searches
 * the caller's documents through document-api's {@code DocumentRetrieval} with the caller's own access, runs a bounded
 * prompt pipeline through the {@code SkillModel} port (the model gateway's integration point), enforces the output
 * schema with one repair, checks citations and the numbers and codes of every row (ADR-0047), and stores a DRAFT in
 * the module's own schema "work". Audited through audit-api. Only privacy depends on this module (work-api
 * {@code WorkSubjects}).
 */
@ApplicationModule(displayName = "Work", allowedDependencies = {"document :: api", "audit :: api"})
package com.verso.work;

import org.springframework.modulith.ApplicationModule;
