package com.verso.qa.service;

import com.verso.document.api.DocumentChangeListener;
import com.verso.document.api.dto.DocumentChange;
import com.verso.qa.api.dto.HookTrigger;
import com.verso.qa.api.dto.SkillStaleReason;
import com.verso.qa.api.dto.SkillState;
import com.verso.qa.repository.QaDatabase;
import com.verso.qa.repository.SkillRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The skills' automatic transition (ADR-0053): it only ever <b>demotes</b>. A skill whose current version is bound to a
 * document that was deleted, replaced or moved goes STALE (document outbox, {@link DocumentChangeListener}, at least
 * once); the nightly golden-set check does the same when a bound hash is gone. Every transition is a conditional update
 * with its event, its audit row (system actor) and a {@code SKILL_STALE} hook event (notify the owners, ADR-0052) in one
 * qa transaction; a repeated delivery finds the skill already stale and changes nothing.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SkillTransitions implements DocumentChangeListener {

    private static final Logger log = LoggerFactory.getLogger(SkillTransitions.class);

    private final QaDatabase db;
    private final SkillRepository skills;
    private final QaAudit audit;
    private final HookTriggers hooks;
    private final Clock clock;

    public SkillTransitions(QaDatabase db, SkillRepository skills, QaAudit audit, HookTriggers hooks, Clock clock) {
        this.db = db;
        this.skills = skills;
        this.audit = audit;
        this.hooks = hooks;
        this.clock = clock;
    }

    @Override
    public void documentChanged(DocumentChange change) {
        SkillStaleReason reason = SkillRules.staleReason(change.kind());
        List<UUID> citing = skills.liveSkillsCiting(change.documentId());
        int stale = 0;
        for (UUID skillId : citing) {
            if (Boolean.TRUE.equals(db.inTransaction(status -> markStaleInTransaction(skillId, reason)))) stale++;
        }
        if (stale > 0) {
            log.info("Skills went stale: documentId={} change={} skills={}", change.documentId(), change.kind(), stale);
        }
    }

    /** One skill to STALE with its event, audit row and hook event; false when it was already stale or archived. */
    public boolean markStaleInTransaction(UUID skillId, SkillStaleReason reason) {
        Instant now = clock.instant();
        Optional<SkillState> before = skills.markStale(skillId, reason, now);
        if (before.isEmpty()) return false;
        int version = skills.find(skillId).map(SkillRepository.SkillRow::version).orElse(1);
        skills.insertEvent(skillId, version, before.get(), SkillState.STALE, reason.name(), now);
        audit.skillStaleInTransaction(skillId, reason.name());
        hooks.appendInTransaction(HookTrigger.SKILL_STALE, "SKILL", skillId, reason.name());
        return true;
    }
}
