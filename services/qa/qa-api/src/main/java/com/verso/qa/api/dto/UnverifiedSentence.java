package com.verso.qa.api.dto;

import java.util.List;

/**
 * An answer sentence whose support the server could not verify (ADR-0047): it states a number, a number word or a code
 * that none of its cited passages states next to the same words. {@code start} and {@code end} are the sentence as
 * {@code [start, end)} in {@code answer}, in UTF-16 code units (JavaScript string indices), markers included;
 * {@code citations} are the citation numbers the sentence carries. The answer itself is never changed: a client shows
 * the sentence with a "dayanağı doğrulanamadı" marker and lets the reader compare it with the source.
 */
public record UnverifiedSentence(int start, int end, List<Integer> citations) {
}
