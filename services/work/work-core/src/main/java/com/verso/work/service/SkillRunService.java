package com.verso.work.service;

import com.verso.document.api.dto.Reader;
import com.verso.work.api.dto.SkillOutput;
import com.verso.work.api.dto.SkillRunEvent;
import com.verso.work.api.dto.SkillRunPage;
import com.verso.work.api.dto.SkillRunResponse;
import com.verso.work.api.dto.SkillSummary;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The caller's side of the skill library (ADR-0057): the gallery, starting a run and the caller's own runs. Ownership
 * is the validated token's sub in every statement; another user's run is a 404.
 */
public interface SkillRunService {

    int MAX_PAGE_SIZE = 100;

    /** An export: the file's text, its media type and its name. */
    record Export(String body, String mediaType, String fileName) {

        @Override
        public String toString() {
            return "Export[mediaType=" + mediaType + "]";
        }
    }

    /** The enabled skills as gallery cards. */
    List<SkillSummary> gallery();

    /**
     * Validates the form, takes a model slot (503 MODEL_BUSY after the short wait), stores the RUNNING run (409 when
     * the caller has one running) and hands it to the runner; returns at once.
     */
    SkillRunResponse start(Reader reader, String skillId, Map<String, Object> inputs);

    SkillRunResponse get(Reader reader, UUID runId);

    SkillRunPage page(String owner, int page, int size);

    /** The stage of the caller's run for the progress stream; empty when it is gone or not the caller's. */
    Optional<SkillRunEvent> event(String owner, UUID runId);

    SkillRunResponse edit(Reader reader, UUID runId, SkillOutput output);

    SkillRunResponse approve(Reader reader, UUID runId);

    void rate(Reader reader, UUID runId, int rating);

    Export export(Reader reader, UUID runId, String format);

    void delete(Reader reader, UUID runId);
}
