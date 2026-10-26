package com.verso.qa.outbox;

import com.verso.platform.core.outbox.OutboxRelay.OutboxHandler;
import com.verso.platform.core.outbox.OutboxStore.OutboxRow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers a {@value #EVENT_TYPE} row of the qa outbox to the hook rule engine (ADR-0052). The engine is looked up per
 * delivery as a {@link Consumer} (it depends on the audit path, which depends on this outbox: no construction cycle,
 * and this package does not depend on the service package). At least once: the engine's actions are idempotent per
 * event id. A payload that no longer reads as a {@link HookEvent} is not transient and ends DEAD after the attempts.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QaHookOutboxHandler implements OutboxHandler {

    public static final String EVENT_TYPE = "knowledge.hook.triggered";
    public static final String AGGREGATE_TYPE = "knowledge-hook";

    /** What consumes a hook event: the rule engine. */
    public interface Consumer {
        void handle(HookEvent event);
    }

    private final ObjectProvider<Consumer> engine;
    private final JsonMapper json;

    public QaHookOutboxHandler(ObjectProvider<Consumer> engine, JsonMapper json) {
        this.engine = engine;
        this.json = json;
    }

    @Override
    public void handle(OutboxRow row) {
        engine.getObject().handle(json.readValue(row.payload(), HookEvent.class));
    }
}
