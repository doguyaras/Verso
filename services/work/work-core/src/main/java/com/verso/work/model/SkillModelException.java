package com.verso.work.model;

/** A model call that did not give an answer; the reason is a fixed word (MODEL_UNAVAILABLE, MODEL_TIMEOUT). */
public class SkillModelException extends RuntimeException {

    private final String reason;

    public SkillModelException(String reason) {
        super(reason, null, false, false);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
