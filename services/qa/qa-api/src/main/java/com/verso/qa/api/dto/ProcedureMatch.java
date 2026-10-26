package com.verso.qa.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * An approved skill whose example questions match the question (ADR-0053), shown above the answer as "Bu bir
 * prosedür". Only for a caller who reads every source of the skill now; its steps and citations, nothing else of it.
 */
public record ProcedureMatch(UUID skillId, String name, String description, List<Step> steps) {

    public record Step(int position, String instruction, List<Source> sources) {
    }

    /** A cited page; {@code passageId} is the verso_fetch id of a chunk with the bound text. */
    public record Source(UUID documentId, String fileName, int page, CitationUnit unit, UUID passageId) {
    }

    @Override
    public String toString() {
        return "ProcedureMatch[skillId=" + skillId + "]";
    }
}
