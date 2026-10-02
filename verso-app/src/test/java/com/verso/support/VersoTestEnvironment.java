package com.verso.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * Runs the test's application context against the real PostgreSQL of {@link VersoPostgres}, the test identity
 * provider of platform-security (ADR-0005) and a deterministic embedding model ({@link TestEmbeddingModel}) instead
 * of Ollama. The scheduled ingestion poll is off: tests drive the worker themselves, so nothing runs behind them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ContextConfiguration(initializers = {VersoPostgres.Initializer.class, TestIdp.Initializer.class,
        VersoTestEnvironment.Models.class})
@Import(TestEmbeddingModel.Config.class)
public @interface VersoTestEnvironment {

    /** The two deployment inputs compose gives the application for models (prod.env.example), plus the poll switch. */
    final class Models implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "OLLAMA_BASE_URL=http://127.0.0.1:1",
                    "OLLAMA_EMBEDDING_MODEL=test-embedding",
                    "verso.document.ingestion.enabled=false").applyTo(context);
        }
    }
}
