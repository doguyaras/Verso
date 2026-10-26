/**
 * Read-only MCP server (ADR-0031): approved AI clients of the employees (Claude, ChatGPT, Copilot Studio, internal
 * agents) search and read the caller's passages through verso_search and verso_fetch, and list and read the approved
 * procedures (skills, ADR-0053) through verso_skills and verso_skill. Every call runs as the calling user (token sub and
 * groups, ADR-0028) through the document and qa modules' contracts; there is no service identity and no shared key.
 * Reads no table of its own and none of another module; publishes audit events through audit-api only (ADR-0026). Off
 * unless verso.mcp.enabled.
 */
@ApplicationModule(displayName = "MCP server", allowedDependencies = {"document :: api", "qa :: api", "audit :: api"})
package com.verso.mcp;

import org.springframework.modulith.ApplicationModule;
