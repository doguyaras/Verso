package com.verso.platform.security.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the caller's account to a controller parameter of type {@link AccountId} (reference 6.5). The account is the
 * {@code sub} of the validated access token and nothing else: never a path variable, query parameter, header or body
 * field (ADR-0005).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentAccount {}
