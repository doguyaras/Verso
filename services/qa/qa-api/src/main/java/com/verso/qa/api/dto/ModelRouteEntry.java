package com.verso.qa.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * One step of a task's route (ADR-0056).
 *
 * @param connectionId the connection; null means the built-in model of the installation (config/verso.yml)
 * @param model        one of the connection's models (the built-in model's name for the built-in one)
 */
public record ModelRouteEntry(UUID connectionId,
                              @NotBlank @Size(max = 200) @Pattern(regexp = "[A-Za-z0-9._:/@-]+") String model) {
}
