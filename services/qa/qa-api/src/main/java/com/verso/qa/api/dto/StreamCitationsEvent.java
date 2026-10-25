package com.verso.qa.api.dto;

import java.util.List;

/**
 * The {@code citations} event (ADR-0035): the validated answer, exactly as POST /v1/questions returns it in
 * {@code answer} and {@code citations} (llm-rules 3.3: markers mapped on the server to retrieved passages, invalid
 * numbers removed). It replaces the provisional text of the {@code delta} events. An empty list is an answer without a
 * source (the {@code done} event says UNCITED or NOT_FOUND). {@code unverified} (ADR-0047, added last) is the JSON
 * answer's field of the same name: the groundedness check runs after the citations are validated, before this event.
 */
public record StreamCitationsEvent(String answer, List<Citation> citations, List<UnverifiedSentence> unverified) {

    public static StreamCitationsEvent of(AnswerResponse answer) {
        return new StreamCitationsEvent(answer.answer(), answer.citations(), answer.unverified());
    }
}
