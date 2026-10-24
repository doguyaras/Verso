package com.verso.qa.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A rating of one of the caller's own answers (ADR-0023). No free text in v1.
 *
 * <ul>
 *   <li>{@code reason} only with {@code DOWN}, and optional there.</li>
 *   <li>{@code citationNumber} exactly with {@code WRONG_SOURCE}; it must be one of the answer's citation numbers
 *       (checked by the server against the stored turn).</li>
 * </ul>
 */
public record FeedbackRequest(@NotNull FeedbackRating rating, FeedbackReason reason,
                              @Min(1) @Max(100) Integer citationNumber) {

    @AssertTrue(message = "reason is only allowed with DOWN")
    public boolean isReasonOnlyWithDown() {
        return reason == null || rating == FeedbackRating.DOWN;
    }

    @AssertTrue(message = "citationNumber is required with WRONG_SOURCE and allowed only there")
    public boolean isCitationNumberOnlyWithWrongSource() {
        return (reason == FeedbackReason.WRONG_SOURCE) == (citationNumber != null);
    }
}
