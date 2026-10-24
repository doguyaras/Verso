package com.verso.document.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;

/**
 * The ingestion worker's beans exist only while {@code verso.ingestion.enabled} is true (default). It is the process
 * role switch of ADR-0039: the same image can run API-only ({@code false}: uploads are queued, the worker beans,
 * the extractors and the docling client are absent) or, later, as a separate ingestion process (ADR-0001's
 * {@code verso-ingest} trigger). Not the same as {@code verso.document.ingestion.enabled}, which keeps the beans and
 * only pauses the scheduled poll (tests drive {@code runOnce()} themselves).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnBooleanProperty(name = "verso.ingestion.enabled", matchIfMissing = true)
public @interface ConditionalOnIngestion {
}
