package com.verso.work.service;

import com.verso.work.api.WorkSubjects;
import com.verso.work.repository.SkillRunRepository;
import com.verso.work.repository.WorkDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * KVKK requests for the skill library (ADR-0034, ADR-0057): the privacy module counts and erases a subject's runs here,
 * never in the work schema itself. The privacy module audits the request; this side logs nothing about the subject.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkSubjectsService implements WorkSubjects {

    private final SkillRunRepository runs;
    private final WorkDatabase db;

    public WorkSubjectsService(SkillRunRepository runs, WorkDatabase db) {
        this.runs = runs;
        this.db = db;
    }

    @Override
    public long countRuns(String sub) {
        return runs.count(sub);
    }

    @Override
    public long deleteRuns(String sub) {
        return db.transactionally(() -> runs.deleteOwner(sub));
    }
}
