package com.verso.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.ContextConfiguration;

/**
 * Runs the test's application context against the real PostgreSQL of {@link VersoPostgres} and the test identity
 * provider of platform-security (ADR-0005): the application cannot start without both, as in production.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ContextConfiguration(initializers = {VersoPostgres.Initializer.class, TestIdp.Initializer.class})
public @interface VersoTestEnvironment {}
