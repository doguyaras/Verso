package com.verso.fixturedelta;

import com.verso.fixturegamma.api.GammaApi;
import com.verso.fixturegamma.api.extra.GammaExtra;

/** Fixture: uses gamma's declared api (allowed) and its unmarked api sub-package (a violation). */
public class DeltaUsesGamma {
    public GammaApi allowed() {
        return new GammaApi("ok");
    }

    public GammaExtra leaked() {
        return new GammaExtra("leak");
    }
}
