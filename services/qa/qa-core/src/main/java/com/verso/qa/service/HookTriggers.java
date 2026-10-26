package com.verso.qa.service;

import com.verso.qa.api.dto.HookTrigger;
import com.verso.qa.outbox.HookEvent;
import com.verso.qa.outbox.QaHookOutboxHandler;
import com.verso.qa.outbox.QaOutbox;
import com.verso.qa.repository.QaDatabase;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feeds the hook rule engine through the qa outbox (ADR-0039, ADR-0052): a {@code knowledge.hook.triggered} row is
 * written in the transaction of the change it reports (a card going stale or disputed, a diagnostics run's clusters and
 * contradictions, a golden-set alert), so the hook fires at least once exactly when the change committed. The poll
 * hands it to {@link HookEngine} (QaHookOutboxHandler). Must run inside {@link QaDatabase#inTransaction}.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HookTriggers {

    private final QaOutbox outbox;
    private final JsonMapper json;

    public HookTriggers(QaOutbox outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    public void appendInTransaction(HookTrigger trigger, String subjectType, UUID subjectId, String detail) {
        HookEvent event = HookEvent.of(trigger, subjectType, subjectId, detail);
        String payload = json.writeValueAsString(event);
        outbox.store().append(event.id(), QaHookOutboxHandler.AGGREGATE_TYPE, subjectId, QaHookOutboxHandler.EVENT_TYPE,
                payload);
        // Delivered right after the commit when it can be (the poll is the safety net).
        outbox.relay().deliverAfterCommit(event.id(), QaHookOutboxHandler.EVENT_TYPE, payload);
    }
}
