package com.verso.qa.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The admin's price of one model of one connection (ADR-0056), per million tokens. Verso does not know list prices;
 * the cost on the value dashboard is this table times the tokens the provider reported.
 */
public record ModelPrice(@NotNull UUID connectionId,
                         @NotBlank @Size(max = 200) @Pattern(regexp = "[A-Za-z0-9._:/@-]+") String model,
                         @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 4) BigDecimal inputPerMillion,
                         @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 4) BigDecimal outputPerMillion,
                         @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
}
