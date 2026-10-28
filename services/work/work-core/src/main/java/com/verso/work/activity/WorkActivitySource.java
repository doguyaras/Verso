package com.verso.work.activity;

import com.verso.platform.core.activity.ActivityCategory;
import com.verso.platform.core.activity.ActivityEntry;
import com.verso.platform.core.activity.ActivityEntry.Actor;
import com.verso.platform.core.activity.ActivityEntry.AuditRef;
import com.verso.platform.core.activity.ActivityEntry.Link;
import com.verso.platform.core.activity.ActivityQuery;
import com.verso.platform.core.activity.ActivitySource;
import com.verso.platform.core.activity.ActivityStatus;
import com.verso.work.repository.WorkActivityRepository;
import com.verso.work.repository.WorkActivityRepository.RunDay;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The work module's part of the activity centre (ADR-0058): the task-skill runs ("Beceriler", ADR-0057) as one line
 * per day and skill, only where at least {@value #MIN_OWNERS} different accounts ran it. A run is one person's work;
 * the activity centre shows usage, never who ran what. Runs belong to no collection, so a collection filter leaves them
 * out. Being a bean is the feature detection: without this module the "Çalışmalar" tab says so.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class WorkActivitySource implements ActivitySource {

    /** k of per-skill usage: the panel's adoption floor (ADR-0048). */
    static final int MIN_OWNERS = 5;
    private static final Actor USERS = Actor.role("Kullanıcılar (en az 5 kişi)");

    private final WorkActivityRepository runs;

    WorkActivitySource(WorkActivityRepository runs) {
        this.runs = runs;
    }

    @Override
    public String key() {
        return "work";
    }

    @Override
    public List<ActivityEntry> entries(ActivityQuery q) {
        if (q.collection() != null) return List.of();
        return runs.runDays(q.from(), q.to(), MIN_OWNERS, q.limit()).stream().map(WorkActivitySource::entry).toList();
    }

    static ActivityEntry entry(RunDay d) {
        ActivityStatus status = d.running() > 0 ? ActivityStatus.RUNNING
                : d.failed() == 0 ? ActivityStatus.SUCCEEDED : d.failed() == d.runs() ? ActivityStatus.FAILED
                : ActivityStatus.PARTIAL;
        UUID id = UUID.nameUUIDFromBytes(("skill-runs:" + d.day() + ":" + d.skillId()).getBytes(StandardCharsets.UTF_8));
        String name = d.skillName() == null || d.skillName().isBlank() ? d.skillId() : d.skillName();
        return new ActivityEntry(id, ActivityCategory.RUN, "SKILL_RUN", status, d.latest(), null, USERS,
                name + " · " + d.runs() + " çalışma",
                d.approved() + " onaylı · " + d.failed() + " başarısız", null, new Link("beceriler", null),
                new AuditRef("TASK_SKILL_RUN", null, "TASK_SKILL_RUN_FINISHED"), d.failed() > 0);
    }
}
