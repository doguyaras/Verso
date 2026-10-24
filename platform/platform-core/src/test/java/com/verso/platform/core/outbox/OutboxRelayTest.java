package com.verso.platform.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.platform.core.outbox.OutboxRelay.BatchResult;
import com.verso.platform.core.outbox.OutboxRelay.OutboxHandler;
import com.verso.platform.core.outbox.OutboxStore.OutboxRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The relay's decisions without a database (ADR-0039, reference 11.2): delete on delivery, back off and never give up
 * on a transient failure, DEAD after the attempts on a permanent one, hand back what the lease margin cannot cover,
 * and an age gauge that keeps growing when the database cannot be read. The SQL itself runs against PostgreSQL in
 * verso-app's AuditOutboxTest (CI).
 */
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final String TYPE = "audit.event.recorded";

    private final OutboxStore store = mock(OutboxStore.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    static OutboxProperties properties(String... pairs) {
        Map<String, String> values = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put("verso.outbox." + pairs[i], pairs[i + 1]);
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("verso.outbox", Bindable.of(OutboxProperties.class));
    }

    private OutboxRelay relay(OutboxHandler handler, OutboxProperties properties) {
        return new OutboxRelay("document", store, Map.of(TYPE, handler), properties, clock, registry);
    }

    private static OutboxRow row(int retries) {
        return new OutboxRow(UUID.randomUUID(), TYPE, "{}", retries, NOW.minusSeconds(5), UUID.randomUUID());
    }

    @Test
    void runOnce_whenHandlerSucceeds_deletesTheRowWithItsClaimToken() {
        OutboxRow row = row(0);
        when(store.claimPending(anyInt(), any(), any())).thenReturn(List.of(row));
        BatchResult result = relay(r -> { }, properties()).runOnce();
        assertThat(result.delivered()).isEqualTo(1);
        verify(store).deleteProcessed(row.id(), row.claimToken());
        verify(store, never()).markFailed(any(), any(), anyString(), any());
    }

    @Test
    void runOnce_whenTheTargetIsDown_retriesWithBackoffAndNeverGivesUp() {
        OutboxRow row = row(500);
        when(store.claimPending(anyInt(), any(), any())).thenReturn(List.of(row));
        BatchResult result = relay(r -> { throw new CannotGetJdbcConnectionException("down"); },
                properties("max-attempts", "3")).runOnce();
        assertThat(result.retried()).isEqualTo(1);
        verify(store).markFailed(row.id(), row.claimToken(), "CannotGetJdbcConnectionException", Duration.ofSeconds(600));
        verify(store, never()).markDead(any(), any(), anyString());
        verify(store, never()).deleteProcessed(any(), any());
    }

    @Test
    void runOnce_whenAPermanentFailureReachesMaxAttempts_marksTheRowDead() {
        OutboxRow early = row(0);
        OutboxRow last = row(2);
        when(store.claimPending(anyInt(), any(), any())).thenReturn(List.of(early, last));
        BatchResult result = relay(r -> { throw new DataIntegrityViolationException("check"); },
                properties("max-attempts", "3")).runOnce();
        assertThat(result.retried()).isEqualTo(1);
        assertThat(result.dead()).isEqualTo(1);
        verify(store).markFailed(early.id(), early.claimToken(), "DataIntegrityViolationException", Duration.ofSeconds(30));
        verify(store).markDead(last.id(), last.claimToken(), "DataIntegrityViolationException");
    }

    @Test
    void runOnce_whenNoHandlerKnowsTheType_treatsItAsPermanent() {
        OutboxRow unknown = new OutboxRow(UUID.randomUUID(), "other.type", "{}", 0, NOW, UUID.randomUUID());
        when(store.claimPending(anyInt(), any(), any())).thenReturn(List.of(unknown));
        relay(r -> { }, properties("max-attempts", "1")).runOnce();
        verify(store).markDead(unknown.id(), unknown.claimToken(), "IllegalStateException");
    }

    @Test
    void runOnce_whenTheLeaseMarginIsReached_handsTheRestBackWithoutAnAttempt() {
        OutboxRow first = row(0);
        OutboxRow second = row(0);
        when(store.claimPending(anyInt(), any(), any())).thenReturn(List.of(first, second));
        when(store.release(any(), any())).thenReturn(true);
        // lease 10s, margin 9s: one second of budget, the first row takes longer.
        BatchResult result = relay(r -> Thread.sleep(1_100), properties("lease", "10s", "lease-margin", "9s")).runOnce();
        assertThat(result.delivered()).isEqualTo(1);
        assertThat(result.deferred()).isEqualTo(1);
        verify(store).release(second.id(), second.claimToken());
        verify(store, never()).markFailed(eq(second.id()), any(), anyString(), any());
    }

    @Test
    void ageGauge_whenTheDatabaseCannotBeRead_keepsGrowingFromTheLastOldestRow() {
        when(store.oldestPending()).thenReturn(Optional.of(NOW.minusSeconds(30)));
        OutboxRelay relay = relay(r -> { }, properties());
        relay.refreshGauges();
        assertThat(age()).isEqualTo(30.0);

        when(store.oldestPending()).thenThrow(new CannotGetJdbcConnectionException("down"));
        now.set(NOW.plusSeconds(60));
        relay.refreshGauges();
        assertThat(age()).as("stale value, growing age").isEqualTo(90.0);
    }

    @Test
    void ageGauge_whenNothingIsPending_isZero() {
        when(store.oldestPending()).thenReturn(Optional.empty());
        relay(r -> { }, properties()).refreshGauges();
        assertThat(age()).isZero();
        assertThat(registry.get("outbox.oldest.pending.age").gauge().getId().getBaseUnit()).isEqualTo("seconds");
    }

    @Test
    void deliverNow_whenTheTargetFails_pausesEagerDeliveryForEveryone() {
        AtomicInteger calls = new AtomicInteger();
        when(store.claimById(any(), any(), any())).thenAnswer(i -> Optional.of(row(0)));
        OutboxRelay relay = relay(r -> {
            calls.incrementAndGet();
            throw new CannotGetJdbcConnectionException("down");
        }, properties("eager-pause", "1m"));
        assertThat(relay.deliverNow(UUID.randomUUID())).isFalse();
        assertThat(relay.deliverNow(UUID.randomUUID())).isFalse();
        assertThat(calls).as("one wait per outage, not one per request").hasValue(1);
    }

    @Test
    void deliverNow_whenTakenByThePoller_doesNothing() {
        when(store.claimById(any(), any(), any())).thenReturn(Optional.empty());
        assertThat(relay(r -> { throw new AssertionError("not called"); }, properties()).deliverNow(UUID.randomUUID()))
                .isFalse();
    }

    @Test
    void properties_whenOutOfRange_areRefused() {
        assertThatThrownBy(() -> properties("lease-margin", "200s")).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(properties().backoffFor(0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties().backoffFor(3)).isEqualTo(Duration.ofSeconds(240));
        assertThat(properties().backoffFor(30)).isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void append_whenNoTransactionOfTheModuleIsOpen_isRefused() {
        OutboxStore real = new OutboxStore("document.outbox_event", mock(JdbcClient.class), () -> false);
        assertThatThrownBy(() -> real.append(UUID.randomUUID(), "audit", UUID.randomUUID(), TYPE, "{}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OutboxStore("document.event; drop", mock(JdbcClient.class), () -> true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private double age() {
        return registry.get("outbox.oldest.pending.age").tag("outbox", "document").gauge().value();
    }
}
