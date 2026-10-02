package com.verso.fixturealpha;

import com.verso.fixturebeta.BetaApi;

/** Allowed dependency: alpha -> beta's public API. Must not be reported. */
final class AlphaUsesBetaApi {

    String describe() {
        return BetaApi.name();
    }
}
