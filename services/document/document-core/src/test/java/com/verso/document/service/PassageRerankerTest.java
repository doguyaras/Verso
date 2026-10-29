package com.verso.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.verso.document.config.RerankerProperties;
import com.verso.document.service.RerankerClient.Failure;
import com.verso.document.service.RerankerClient.RerankException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;

/**
 * ADR-0043: the guard around the reranker on the hot path. The sidecar's order passes through; every failure (error,
 * timeout, busy, open circuit, broken answer) is an empty result, which keeps the fused order; the breaker opens after
 * three failures in a row and skips the sidecar until it closes; passages are cut before they leave; and neither the
 * question nor a passage reaches the log.
 */
class PassageRerankerTest {

    private static final String QUESTION = "Gizli soru: CAB toplantısı hangi gün?";
    private static final List<String> TEXTS = List.of("Gizli pasaj bir", "Gizli pasaj iki", "Gizli pasaj üç");

    /** Answers rank() from a script and records what it was sent. */
    static final class ScriptedClient extends RerankerClient {
        final AtomicInteger calls = new AtomicInteger();
        final List<List<String>> sent = new ArrayList<>();
        volatile Answer answer = texts -> List.of(2, 0, 1);
        volatile double score = 0.93;

        interface Answer {
            List<Integer> rank(List<String> texts) throws Exception;
        }

        @Override
        double[] scores(String query, List<String> texts) {
            // The same script decides failures, so both calls share the breaker's behaviour.
            rank(query, texts);
            return new double[] {score};
        }

        ScriptedClient(RerankerProperties properties) {
            super(properties, new StaticListableBeanFactory().getBeanProvider(RestClient.Builder.class),
                    new StaticListableBeanFactory().getBeanProvider(HttpClientSettings.class));
        }

        @Override
        List<Integer> rank(String query, List<String> texts) {
            calls.incrementAndGet();
            synchronized (sent) {
                sent.add(texts);
            }
            try {
                return answer.rank(texts);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private Logger root;
    private ListAppender<ILoggingEvent> logs;

    static RerankerProperties properties(boolean enabled, Duration timeout, int concurrency) {
        return new RerankerProperties(enabled, URI.create("http://reranker:80"), 20, Duration.ofMillis(500), timeout,
                2000, concurrency, 3, Duration.ofMinutes(1));
    }

    private final RerankerProperties on = properties(true, Duration.ofSeconds(2), 4);
    private final ScriptedClient client = new ScriptedClient(on);
    private final PassageReranker reranker = new PassageReranker(client, on, clock, registry);

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
    }

    private double count(String outcome) {
        return registry.get("verso.retrieval.rerank").tag("outcome", outcome).counter().count();
    }

    @Test
    void order_whenTheSidecarAnswers_returnsItsOrder() {
        assertThat(reranker.order(QUESTION, TEXTS)).contains(List.of(2, 0, 1));
        assertThat(count("reranked")).isEqualTo(1);
        assertThat(registry.get("verso.retrieval.rerank.duration").timer().count()).isEqualTo(1);
    }

    @Test
    void score_ADR0060_returnsTheSidecarsScore_andEveryFailureIsEmpty() {
        assertThat(reranker.score(QUESTION, "Gizli kart sorusu")).hasValue(0.93);
        assertThat(client.sent.getLast()).containsExactly("Gizli kart sorusu");

        client.answer = texts -> {
            throw new RerankException(Failure.TIMEOUT);
        };
        assertThat(reranker.score(QUESTION, "Gizli kart sorusu")).isEmpty();
        assertThat(count("reranked")).isEqualTo(1);
        assertThat(count("timeout")).isEqualTo(1);
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains("Gizli"));
    }

    @Test
    void score_sharesTheBreakerWithOrder_andIsEmptyWhenDisabled() {
        client.answer = texts -> {
            throw new RerankException(Failure.UNAVAILABLE);
        };
        reranker.order(QUESTION, TEXTS);
        reranker.order(QUESTION, TEXTS);
        reranker.score(QUESTION, "kart");
        assertThat(reranker.open()).isTrue();
        client.answer = texts -> List.of(0);
        assertThat(reranker.score(QUESTION, "kart")).as("open circuit").isEmpty();
        assertThat(client.calls).hasValue(3);

        RerankerProperties off = properties(false, Duration.ofSeconds(2), 4);
        ScriptedClient idle = new ScriptedClient(off);
        assertThat(new PassageReranker(idle, off, clock, new SimpleMeterRegistry()).score(QUESTION, "kart")).isEmpty();
        assertThat(idle.calls).hasValue(0);
    }

    @Test
    void order_whenDisabled_neverCallsTheSidecar() {
        RerankerProperties off = properties(false, Duration.ofSeconds(2), 4);
        ScriptedClient idle = new ScriptedClient(off);
        PassageReranker disabled = new PassageReranker(idle, off, clock, new SimpleMeterRegistry());

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.order(QUESTION, TEXTS)).isEmpty();
        assertThat(idle.calls).hasValue(0);
    }

    @Test
    void order_whenOnlyOnePassage_doesNotCallTheSidecar() {
        assertThat(reranker.order(QUESTION, List.of("tek"))).isEmpty();
        assertThat(client.calls).hasValue(0);
    }

    @Test
    void order_whenTheSidecarFails_fallsBackWithItsKind() {
        client.answer = texts -> {
            throw new RerankException(Failure.UNAVAILABLE);
        };
        assertThat(reranker.order(QUESTION, TEXTS)).isEmpty();
        client.answer = texts -> {
            throw new RerankException(Failure.INVALID_RESPONSE);
        };
        assertThat(reranker.order(QUESTION, TEXTS)).isEmpty();

        assertThat(count("unavailable")).isEqualTo(1);
        assertThat(count("invalid_response")).isEqualTo(1);
        assertThat(reranker.open()).isFalse();
    }

    @Test
    void order_whenTheSidecarHangs_givesUpAtTheDeadline() {
        RerankerProperties quick = properties(true, Duration.ofMillis(200), 4);
        ScriptedClient hanging = new ScriptedClient(quick);
        CountDownLatch never = new CountDownLatch(1);
        hanging.answer = texts -> {
            never.await();
            return List.of(0, 1, 2);
        };
        SimpleMeterRegistry own = new SimpleMeterRegistry();
        PassageReranker guarded = new PassageReranker(hanging, quick, clock, own);

        long started = System.nanoTime();
        Optional<List<Integer>> order = guarded.order(QUESTION, TEXTS);
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(order).isEmpty();
        assertThat(tookMs).isLessThan(1500);
        assertThat(own.get("verso.retrieval.rerank").tag("outcome", "timeout").counter().count()).isEqualTo(1);
    }

    @Test
    void order_afterThreeFailuresInARow_skipsTheSidecarUntilTheCircuitCloses() {
        client.answer = texts -> {
            throw new RerankException(Failure.TIMEOUT);
        };
        for (int i = 0; i < 3; i++) assertThat(reranker.order(QUESTION, TEXTS)).isEmpty();
        assertThat(reranker.open()).isTrue();
        client.answer = texts -> List.of(1, 0, 2);

        assertThat(reranker.order(QUESTION, TEXTS)).as("open: the fused order, no call").isEmpty();
        assertThat(client.calls).hasValue(3);
        assertThat(count("circuit_open")).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(reranker.order(QUESTION, TEXTS)).contains(List.of(1, 0, 2));
        assertThat(client.calls).hasValue(4);
    }

    @Test
    void order_aSuccessResetsTheFailureCount() {
        client.answer = texts -> {
            throw new RerankException(Failure.UNAVAILABLE);
        };
        reranker.order(QUESTION, TEXTS);
        reranker.order(QUESTION, TEXTS);
        client.answer = texts -> List.of(0, 1, 2);
        reranker.order(QUESTION, TEXTS);
        client.answer = texts -> {
            throw new RerankException(Failure.UNAVAILABLE);
        };
        reranker.order(QUESTION, TEXTS);
        reranker.order(QUESTION, TEXTS);

        assertThat(reranker.open()).isFalse();
    }

    @Test
    void order_whenTheBulkheadIsFull_keepsTheFusedOrderWithoutACall() throws Exception {
        RerankerProperties one = properties(true, Duration.ofSeconds(5), 1);
        ScriptedClient slow = new ScriptedClient(one);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        slow.answer = texts -> {
            inside.countDown();
            release.await();
            return List.of(0, 1, 2);
        };
        SimpleMeterRegistry own = new SimpleMeterRegistry();
        PassageReranker guarded = new PassageReranker(slow, one, clock, own);
        Thread first = Thread.ofVirtual().start(() -> guarded.order(QUESTION, TEXTS));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(guarded.order(QUESTION, TEXTS)).isEmpty();
        assertThat(own.get("verso.retrieval.rerank").tag("outcome", "busy").counter().count()).isEqualTo(1);
        release.countDown();
        first.join();
        assertThat(slow.calls).hasValue(1);
        assertThat(guarded.order(QUESTION, TEXTS)).as("the slot is free again").isPresent();
    }

    @Test
    void order_cutsLongPassagesBeforeTheyLeave() {
        String longText = "a".repeat(1999) + "😀" + "b".repeat(500);

        reranker.order(QUESTION, List.of(longText, "kısa"));

        List<String> sent = client.sent.getFirst();
        assertThat(sent.getFirst()).hasSize(1999).as("no lone surrogate").doesNotContain("\uD83D");
        assertThat(sent.get(1)).isEqualTo("kısa");
    }

    @Test
    void logs_neverCarryTheQuestionOrAPassage() {
        client.answer = texts -> {
            throw new RerankException(Failure.UNAVAILABLE);
        };
        for (int i = 0; i < 4; i++) reranker.order(QUESTION, TEXTS);

        assertThat(logs.list).as("the breaker opening is logged").isNotEmpty();
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("Gizli");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-24T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
