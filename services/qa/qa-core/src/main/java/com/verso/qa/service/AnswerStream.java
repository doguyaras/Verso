package com.verso.qa.service;

import com.verso.platform.core.exception.ServiceException;
import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.api.dto.AnswerStage;

/**
 * Where a streamed answer goes (ADR-0035), implemented by the SSE writer of POST /v1/questions/stream. Every method is
 * called from the one background thread that answers the question, in pipeline order; exactly one of
 * {@link #completed}, {@link #failed} and {@link #cancelled} ends it. A write that fails means the client is gone:
 * the method returns false and the answer is abandoned (the model call is cancelled, nothing is stored).
 */
public interface AnswerStream {

    /** False once the client is gone. {@code retrieved} and {@code relevant} matter for PASSAGES_FOUND only. */
    boolean stage(AnswerStage stage, int retrieved, int relevant);

    /**
     * ADR-0055: sub-question {@code step} of {@code steps} is being searched (the SUB_QUESTION stage with its
     * position, never its text); false once the client is gone.
     */
    default boolean subQuestion(int step, int steps) {
        return stage(AnswerStage.SUB_QUESTION, 0, 0);
    }

    /** The next piece of model text, unvalidated; false once the client is gone. */
    boolean delta(String text);

    /** Writes a keep-alive comment when nothing was written for a while (proxies); false once the client is gone. */
    boolean keepAlive();

    /** The validated, stored answer: its citations, then the end of the stream. */
    void completed(AnswerResponse answer);

    /** A safe error code as the last event; nothing was stored. */
    void failed(ServiceException failure);

    /** The client left: the stream is closed without another event; nothing was stored. */
    void cancelled();
}
