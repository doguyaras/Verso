package com.verso.qa.api.dto;

import java.time.Instant;

/**
 * The caller's rating of one answer (ADR-0023). One per turn: rating again replaces it.
 *
 * @param reason         only with {@link FeedbackRating#DOWN}, optional
 * @param citationNumber the {@code [n]} the caller says is the wrong source; only with
 *                       {@link FeedbackReason#WRONG_SOURCE}
 */
public record TurnFeedback(FeedbackRating rating, FeedbackReason reason, Integer citationNumber, Instant updatedAt) {
}
