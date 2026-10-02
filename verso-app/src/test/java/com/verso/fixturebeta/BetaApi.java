package com.verso.fixturebeta;

/** Fixture module "fixturebeta": its root package is its public API. Test sources only, never a bean. */
public final class BetaApi {

    private BetaApi() {}

    public static String name() {
        return "beta";
    }
}
