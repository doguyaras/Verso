package com.verso.qa.api.dto;

import java.util.List;

/**
 * GET /v1/models (ADR-0056): the answer models a user may pick, only when a verso-admin allowed it; otherwise
 * {@code enabled=false} and no choices. A choice id goes into {@link QuestionRequest#model()}.
 */
public record ModelChoicesResponse(boolean enabled, List<Choice> choices) {
    public ModelChoicesResponse {
        choices = List.copyOf(choices);
    }

    /**
     * @param id       opaque ({@code <connection id>/<model>}, or {@code built-in})
     * @param label    connection name and model
     * @param external true when the question and passages leave the installation with this choice
     */
    public record Choice(String id, String label, boolean external) {
    }
}
