package com.verso.qa.service;

import com.verso.document.api.dto.PageResponse;
import com.verso.qa.api.dto.HookNotificationResponse;
import com.verso.qa.api.dto.HookRuleResponse;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.repository.HookRepository;
import com.verso.qa.repository.HookRepository.Notification;
import com.verso.qa.repository.HookRepository.Rule;
import com.verso.qa.repository.QaDatabase;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The admin side of the hooks (ADR-0052): the rules (switched on or off, nothing else) and the notifications (listed,
 * marked read). Every change is audited in its own transaction (ids, the rule key).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KnowledgeHookAdmin {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeHookAdmin.class);

    private final QaDatabase db;
    private final HookRepository hooks;
    private final QaAudit audit;
    private final Clock clock;

    public KnowledgeHookAdmin(QaDatabase db, HookRepository hooks, QaAudit audit, Clock clock) {
        this.db = db;
        this.hooks = hooks;
        this.audit = audit;
        this.clock = clock;
    }

    public List<HookRuleResponse> rules() {
        return hooks.rules().stream().map(KnowledgeHookAdmin::rule).toList();
    }

    public HookRuleResponse setEnabled(String key, boolean enabled) {
        Optional<Rule> changed = db.inTransaction(status -> {
            if (!hooks.setEnabled(key, enabled, clock.instant())) return Optional.<Rule>empty();
            audit.hookRuleChangedInTransaction(key, enabled);
            return hooks.rule(key);
        });
        Rule rule = changed.orElseThrow(() -> {
            log.warn("Hook rule change rejected: code={} reason=UNKNOWN_RULE", QaErrorCode.KNOWLEDGE_HOOK_RULE_NOT_FOUND.getCode());
            return new QaServiceException(QaErrorCode.KNOWLEDGE_HOOK_RULE_NOT_FOUND, "UNKNOWN_RULE");
        });
        log.info("Hook rule changed: rule={} enabled={}", rule.key(), rule.enabled());
        return rule(rule);
    }

    public PageResponse<HookNotificationResponse> notifications(boolean unreadOnly, int page, int size) {
        return PageResponse.of(hooks.notifications(unreadOnly, page, size).stream()
                .map(KnowledgeHookAdmin::notification).toList(), page, size, hooks.countNotifications(unreadOnly));
    }

    public HookNotificationResponse markRead(UUID id) {
        Optional<Notification> read = db.inTransaction(status -> {
            Optional<Notification> row = hooks.markRead(id, clock.instant());
            row.ifPresent(n -> audit.notificationReadInTransaction(n.id()));
            return row;
        });
        return notification(read.orElseThrow(() -> {
            log.warn("Notification read rejected: code={} reason=NOT_FOUND", QaErrorCode.KNOWLEDGE_NOTIFICATION_NOT_FOUND.getCode());
            return new QaServiceException(QaErrorCode.KNOWLEDGE_NOTIFICATION_NOT_FOUND, "NOT_FOUND");
        }));
    }

    private static HookRuleResponse rule(Rule rule) {
        return new HookRuleResponse(rule.key(), rule.trigger(), rule.action(), rule.enabled(), rule.defaultEnabled(),
                KnowledgeHealthFindings.ruleDescription(rule.key()), rule.updatedAt());
    }

    private static HookNotificationResponse notification(Notification n) {
        return new HookNotificationResponse(n.id(), n.ruleKey(), n.action(), n.trigger(), n.subjectType(), n.subjectId(),
                n.detail(), n.severity(), KnowledgeHealthFindings.notificationTitle(n.trigger(), n.action(), n.detail()),
                n.createdAt(), n.readAt());
    }
}
