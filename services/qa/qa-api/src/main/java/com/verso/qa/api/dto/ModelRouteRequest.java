package com.verso.qa.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The route of one task (ADR-0056): the primary first, then the fallbacks in order. An empty list goes back to the
 * built-in model alone.
 */
public record ModelRouteRequest(@NotNull @Size(max = 5) List<@Valid @NotNull ModelRouteEntry> entries) {
}
