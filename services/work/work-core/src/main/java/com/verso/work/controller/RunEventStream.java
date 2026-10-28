package com.verso.work.controller;

import com.verso.work.api.dto.SkillRunEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The progress stream of one run (ADR-0057): a virtual thread re-reads the run's stage every {@code poll} and sends
 * {@code stage} when it changes and {@code done} when the run is no longer RUNNING (or is gone). Holds no database
 * connection between reads and no run state of its own beyond the last event sent, so any instance can serve it.
 */
final class RunEventStream {

    private static final Logger log = LoggerFactory.getLogger(RunEventStream.class);
    private static final Duration KEEP_ALIVE = Duration.ofSeconds(10);
    static final int MAX_OPEN = 200;
    private static final java.util.concurrent.atomic.AtomicInteger OPEN = new java.util.concurrent.atomic.AtomicInteger();

    private final Supplier<Optional<SkillRunEvent>> source;
    private final Duration poll;
    private final Duration max;
    private final SseEmitter emitter;
    private final AtomicBoolean open = new AtomicBoolean(true);

    RunEventStream(Supplier<Optional<SkillRunEvent>> source, Duration poll, Duration max) {
        this.source = source;
        this.poll = poll;
        this.max = max;
        this.emitter = new SseEmitter(max.plusSeconds(5).toMillis());
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(e -> open.set(false));
    }

    SseEmitter emitter() {
        return emitter;
    }

    /**
     * Starts the polling thread; false when this instance already serves {@value #MAX_OPEN} streams (each polls the
     * database, so their number is bounded per instance; the caller answers 503 and the panel polls instead).
     */
    boolean start() {
        if (OPEN.incrementAndGet() > MAX_OPEN) {
            OPEN.decrementAndGet();
            return false;
        }
        Thread.ofVirtual().name("skill-run-events").start(() -> {
            try {
                loop();
            } finally {
                OPEN.decrementAndGet();
            }
        });
        return true;
    }

    private void loop() {
        long deadline = System.nanoTime() + max.toNanos();
        long lastSent = System.nanoTime();
        SkillRunEvent last = null;
        try {
            while (open.get() && System.nanoTime() - deadline < 0) {
                Optional<SkillRunEvent> now = source.get();
                if (now.isEmpty()) {
                    send("done", new SkillRunEvent(null, null, null, null, null, "RUN_GONE"));
                    break;
                }
                SkillRunEvent event = now.get();
                if (event.status() != null) {
                    send("done", event);
                    break;
                }
                if (!Objects.equals(event, last)) {
                    send("stage", event);
                    last = event;
                    lastSent = System.nanoTime();
                } else if (System.nanoTime() - lastSent > KEEP_ALIVE.toNanos()) {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                    lastSent = System.nanoTime();
                }
                LockSupport.parkNanos(poll.toNanos());
            }
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            // The client left or the container completed the response: nothing left to send.
            open.set(false);
        } catch (RuntimeException e) {
            log.warn("Skill run events ended: reason=READ_FAILED exceptionType={}", e.getClass().getSimpleName());
            emitter.complete();
        }
    }

    private void send(String name, SkillRunEvent event) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(event, MediaType.APPLICATION_JSON));
    }
}
