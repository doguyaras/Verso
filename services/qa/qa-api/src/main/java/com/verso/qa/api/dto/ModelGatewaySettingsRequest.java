package com.verso.qa.api.dto;

import jakarta.validation.constraints.NotNull;

/** Gateway settings a verso-admin changes (ADR-0056). Boxed and required: never a silent default. */
public record ModelGatewaySettingsRequest(@NotNull Boolean userModelChoice) {
}
