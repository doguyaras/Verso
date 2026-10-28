package com.verso.platform.core.activity;

import java.util.List;
import java.util.Objects;

/**
 * The drawer of one entry (ADR-0058): the entry, its facts, steps, the sources it used and its model calls, metadata
 * only. Never a prompt, a question, an answer or a document's text; those stay in the audit trail and the history,
 * behind their own access rules.
 */
public record ActivityDetail(ActivityEntry entry, List<Fact> facts, List<Step> steps, List<Source> sources,
                             List<ModelCall> modelCalls) {

    public ActivityDetail {
        Objects.requireNonNull(entry, "entry");
        facts = facts == null ? List.of() : List.copyOf(facts);
        steps = steps == null ? List.of() : List.copyOf(steps);
        sources = sources == null ? List.of() : List.copyOf(sources);
        modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
    }

    /** The entry with facts only (a run without recorded steps). */
    public static ActivityDetail of(ActivityEntry entry, List<Fact> facts) {
        return new ActivityDetail(entry, facts, List.of(), List.of(), List.of());
    }

    /** A label and a value, both short Turkish text from code ("Taranan", "1.284"). */
    public record Fact(String label, String value) {
        public Fact {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
        }
    }

    /** One step; {@code durationMs} null when not measured. */
    public record Step(String label, ActivityStatus status, String detail, Long durationMs) {
        public Step {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(status, "status");
        }
    }

    /** A source the entry used: its name only when readable; an unreadable source never carries one. */
    public record Source(String name, String format, boolean readable, Integer citations) {
        public Source {
            if (!readable) name = null;
        }
    }

    /** A model call: which purpose, local or cloud, the provider word, tokens and time when known. */
    public record ModelCall(String purpose, boolean local, String provider, Long inputTokens, Long outputTokens,
                            Long durationMs) {
        public ModelCall {
            Objects.requireNonNull(purpose, "purpose");
        }
    }
}
