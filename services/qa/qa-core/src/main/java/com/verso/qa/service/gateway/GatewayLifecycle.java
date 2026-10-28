package com.verso.qa.service.gateway;
import com.verso.qa.repository.ModelConnection;
import com.verso.qa.repository.ModelGatewayRepository;

import com.verso.qa.config.AiMode;
import com.verso.qa.config.QaGatewayProperties;
import com.verso.qa.config.QaHistoryProperties;
import com.verso.qa.config.UsageMetricsProperties;
import com.verso.qa.config.VersoAiProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The gateway's startup check and usage retention (ADR-0056).
 *
 * <p><b>Startup (llm-rules 1.1).</b> In local mode the application does not start while an enabled connection in the
 * database is a cloud provider or points outside the internal network (written by an older image, a restore or by hand:
 * the save refuses it). An unreachable database does not stop the start (the database outage has its own alerts); the
 * router and the client factory still refuse such a connection on every call.
 *
 * <p><b>Retention.</b> Usage rows and team digests older than {@code usage-retention-months} are deleted on the history
 * retention's schedule and switch ({@code verso.qa.history.purge-enabled}). The delete is idempotent, so several
 * instances running it at once is harmless.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GatewayLifecycle implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GatewayLifecycle.class);

    private final ModelGateway gateway;
    private final ModelGatewayRepository repository;
    private final VersoAiProperties ai;
    private final QaGatewayProperties properties;
    private final QaHistoryProperties history;
    private final UsageMetricsProperties zones;
    private final Clock clock;

    public GatewayLifecycle(ModelGateway gateway, ModelGatewayRepository repository, VersoAiProperties ai,
                            QaGatewayProperties properties, QaHistoryProperties history, UsageMetricsProperties zones,
                            Clock clock) {
        this.gateway = gateway;
        this.repository = repository;
        this.ai = ai;
        this.properties = properties;
        this.history = history;
        this.zones = zones;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (ai.mode() != AiMode.LOCAL) return;
        List<ModelConnection> forbidden;
        try {
            forbidden = gateway.forbiddenInLocalMode();
        } catch (DataAccessException e) {
            log.warn("Model connections not checked at startup: reason=DATABASE exceptionType={}",
                    e.getClass().getSimpleName());
            return;
        }
        refuse(forbidden);
    }

    /** Ids only: a connection's name or host is the operator's (llm-rules 2.1). */
    static void refuse(List<ModelConnection> forbidden) {
        if (forbidden.isEmpty()) return;
        throw new IllegalStateException("verso.ai.mode=local, but " + forbidden.size() + " enabled model connection(s) "
                + "are cloud providers or outside the internal network (ids " + forbidden.stream()
                .map(c -> c.id().toString()).toList() + "); disable them or switch to cloud mode (llm-rules 1.1, "
                + "ADR-0056)");
    }

    @Scheduled(fixedDelayString = "${verso.qa.history.purge-interval:PT1H}",
            initialDelayString = "${verso.qa.history.purge-initial-delay:PT5M}")
    public void purge() {
        if (!history.purgeEnabled()) return;
        ZonedDateTime now = clock.instant().atZone(zones.zone());
        Instant cutoff = now.minusMonths(properties.usageRetentionMonths()).toInstant();
        String cutoffMonth = YearMonth.from(now).minusMonths(properties.usageRetentionMonths()).toString();
        try {
            int deleted = repository.purgeBefore(cutoff, cutoffMonth);
            if (deleted > 0) log.info("Model usage purged: rows={}", deleted);
        } catch (DataAccessException e) {
            log.warn("Model usage purge failed: reason=DATABASE exceptionType={}", e.getClass().getSimpleName());
        }
    }
}
