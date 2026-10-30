package com.verso.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * Runs the test's application context against the real PostgreSQL of {@link VersoPostgres}, the test identity
 * provider of platform-security (ADR-0005) and deterministic models ({@link TestEmbeddingModel}, {@link TestChatModel})
 * instead of Ollama. The scheduled ingestion poll is off: tests drive the worker themselves, so nothing runs behind them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ContextConfiguration(initializers = {VersoPostgres.Initializer.class, TestIdp.Initializer.class,
        VersoTestEnvironment.Models.class})
@Import({TestEmbeddingModel.Config.class, TestChatModel.Config.class})
public @interface VersoTestEnvironment {

    /**
     * The deployment inputs compose gives the application for models (prod.env.example), plus the poll switch. The AI
     * mode settings default to local mode; a cloud mode test sets its own first (they are kept).
     */
    /** A random KEK for the test JVM (ADR-0056). */
    final class LocalKek {
        public static final String SETTING = "SECRET_MODEL_GATEWAY_KEK";
        public static final String VALUE = java.util.HexFormat.of().formatHex(randomBytes());

        private LocalKek() {
        }

        private static byte[] randomBytes() {
            byte[] bytes = new byte[24];
            new java.security.SecureRandom().nextBytes(bytes);
            return bytes;
        }
    }

    final class Models implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        private static final Map<String, String> AI_DEFAULTS = Map.of(
                "VERSO_AI_MODE", "local",
                "VERSO_CHAT_PROVIDER", "ollama",
                "CLOUD_CHAT_MODEL", "test-cloud",
                "OPENAI_BASE_URL", "http://127.0.0.1:1/v1");

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            AI_DEFAULTS.forEach((name, value) -> {
                if (!context.getEnvironment().containsProperty(name)) TestPropertyValues.of(name + "=" + value).applyTo(context);
            });
            TestPropertyValues.of(
                    "OLLAMA_BASE_URL=http://127.0.0.1:1",
                    "OLLAMA_CHAT_BASE_URL=http://127.0.0.1:1",
                    "OLLAMA_EMBEDDING_MODEL=test-embedding",
                    "OLLAMA_CHAT_MODEL=test-chat",
                    "verso.document.ingestion.enabled=false",
                    // ... and the system-model backfill (ADR-0054): SystemModelApiTest drives runOnce() itself.
                    "verso.document.system-model.backfill-enabled=false",
                    // ... and the context-header backfill (ADR-0061): ContextHeaderTest drives runOnce() itself.
                    "verso.document.context-header.backfill-enabled=false",
                    // Connector syncs (ADR-0038) only when a test drives ConnectorSyncWorker.runDue(): a background run
                    // in another cached context would write audit rows other tests count.
                    "verso.connector.sync-enabled=false",
                    // Tests run the history retention themselves (HistoryRetentionTest), like the ingestion poll.
                    "verso.qa.history.purge-enabled=false",
                    // ... and the audit retention (AuditRetentionTest).
                    "verso.audit.purge-enabled=false",
                    // ... and the outbox poll (AuditOutboxTest, ADR-0039): several cached contexts share one database,
                    // and a background relay of one would deliver another test's rows. The eager delivery after each
                    // commit stays on, so audit rows exist when a response arrives, as in production.
                    "verso.outbox.enabled=false",
                    // Short circuit-breaker pauses, so tests can wait them out.
                    "verso.document.ingestion.model-unavailable-pause=1s",
                    "verso.document.ingestion.model-misconfigured-pause=1s",
                    "verso.qa.circuit-open=1s",
                    // Two slots: QuestionApiTest exercises the bulkhead with two calls in flight (production default 1).
                    "verso.qa.chat-concurrency=2",
                    "verso.qa.local-chat-timeout=8s",
                    // The model gateway's KEK (ADR-0056), as the config tree would give it: a test property, never an
                    // environment variable (refused). Random per JVM: no key value is ever written down.
                    LocalKek.SETTING + "=" + LocalKek.VALUE,
                    // Admin changes are seen at once by the instance that made them; a short TTL for the others.
                    "verso.qa.gateway.cache-ttl=0s",
                    "verso.document.retrieval.embedding-timeout=2s").applyTo(context);
        }
    }
}
