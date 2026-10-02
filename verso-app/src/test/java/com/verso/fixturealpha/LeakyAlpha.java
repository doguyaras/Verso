package com.verso.fixturealpha;

import com.verso.fixturebeta.internal.BetaInternal;

/**
 * DELIBERATE BOUNDARY VIOLATION (test sources only): alpha reaches into beta's internal package. ModuleStructureTest
 * proves that verify() reports exactly this dependency.
 */
final class LeakyAlpha {

    int peek() {
        return BetaInternal.secret();
    }
}
