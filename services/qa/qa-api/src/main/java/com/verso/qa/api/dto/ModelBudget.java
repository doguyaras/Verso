package com.verso.qa.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A monthly budget (ADR-0056) of a connection (scopeKey = its id) or a team (scopeKey = a group name of the token,
 * ADR-0028). Reaching {@code softLimit} notifies (audit event, metric, panel); reaching {@code hardLimit} blocks the
 * calls it covers with 429 {@code MODEL_BUDGET_EXCEEDED}. Amounts are in the price table's currency.
 *
 * @param softLimit boxed: optional
 */
public record ModelBudget(@NotNull @Pattern(regexp = "CONNECTION|TEAM") String scope,
                          @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\p{Cntrl}]+") String scopeKey,
                          @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal hardLimit,
                          @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal softLimit) {
}
