package com.verso.fixturegamma.api.extra;

/**
 * Fixture: a sub-package of the api package without its own @NamedInterface. Modulith does not extend a named
 * interface to sub-packages, so using this type from another module is a violation (ADR-0007 #24).
 */
public record GammaExtra(String value) {
}
