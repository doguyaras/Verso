package com.verso.qa.api.dto;

/**
 * How a question ended, so a client can tell "not in the documents" from "the model answered without a source"
 * (both have {@code found=false}).
 */
public enum AnswerOutcome {
    /** The answer cites at least one retrieved passage. */
    ANSWERED,
    /**
     * No passage was close enough (the model was not asked), or the model said the documents do not answer it (in the
     * fixed sentence or, ADR-0064, in its own words, cited or not), or its answer did not give what the question asks
     * for (ADR-0064). Always the not-found sentence and no citations.
     */
    NOT_FOUND,
    /** The model answered but cited no passage: shown with a "no source" warning. */
    UNCITED
}
