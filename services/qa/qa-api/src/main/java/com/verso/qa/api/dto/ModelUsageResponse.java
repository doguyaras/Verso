package com.verso.qa.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Token use and cost of one month (ADR-0056). Never per user (works-council rule): rows are per connection, model and
 * task, and per team only for teams with at least {@code minTeamUsers} distinct users that month; smaller teams are
 * only counted in {@code hiddenTeams}.
 */
public record ModelUsageResponse(String month, long requests, long promptTokens, long completionTokens,
                                 BigDecimal cost, List<Row> rows, List<Team> teams, int hiddenTeams, int minTeamUsers,
                                 List<BudgetState> budgets) {
    public ModelUsageResponse {
        rows = List.copyOf(rows);
        teams = List.copyOf(teams);
        budgets = List.copyOf(budgets);
    }

    /** @param connectionId null: the built-in model */
    public record Row(UUID connectionId, String connectionName, String model, ModelTask task, long requests,
                      long promptTokens, long completionTokens, BigDecimal cost, long failures) {
    }

    public record Team(String team, long requests, long promptTokens, long completionTokens, BigDecimal cost) {
    }

    /** @param state OK, SOFT (soft limit reached) or HARD (calls blocked) */
    public record BudgetState(String scope, String scopeKey, BigDecimal hardLimit, BigDecimal softLimit,
                              BigDecimal spent, String state) {
    }
}
