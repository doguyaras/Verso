package com.verso.qa.api.dto;

/**
 * The {@code delta} event (ADR-0035): the next piece of the model's text as it was generated. Provisional: its
 * {@code [n]} markers are not validated yet, so a client shows them as plain text (or not at all) and replaces the
 * whole text with the {@code answer} of the {@link StreamCitationsEvent} that ends the stream.
 */
public record StreamDeltaEvent(String text) {
}
