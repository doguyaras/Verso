package com.verso.qa.api.dto;

/**
 * A step of the answer pipeline as the stream reports it (ADR-0035). Only steps that really run are reported, in this
 * order; nothing is invented to fill a wait (ADR-0017).
 */
public enum AnswerStage {
    /**
     * A follow-up question is rewritten with the conversation into a standalone search query (ADR-0046). Only when the
     * chat model is really asked to; a standalone question or the first one of a conversation skips it.
     */
    REWRITING,
    /** The question is embedded and the caller's documents are searched. */
    RETRIEVING,
    /**
     * An expert-verified answer card matched the question and the caller may read every document it cites (ADR-0049,
     * "Onaylı bilgi bulundu"): its answer follows in the {@code citations} event; no passages are searched and no model
     * writes. Added after ADR-0035; a client that does not know a stage shows the previous one.
     */
    MEMORY_FOUND,
    /** Retrieval ended: {@code retrieved} passages came back, {@code relevant} of them go to the model (0: none). */
    PASSAGES_FOUND,
    /** The chat model was asked and is writing; {@code delta} events follow. */
    WRITING
}
