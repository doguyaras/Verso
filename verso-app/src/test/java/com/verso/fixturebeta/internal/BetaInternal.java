package com.verso.fixturebeta.internal;

/** Internal type of the fixture module: other modules must not reach it. */
public final class BetaInternal {

    private BetaInternal() {}

    public static int secret() {
        return 42;
    }
}
