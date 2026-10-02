package com.verso.archfixture.cycle.a;

import com.verso.archfixture.cycle.b.CycleB;

/** DELIBERATE PACKAGE CYCLE (test sources only): a -> b -> a. ArchitectureRulesTest proves the cycle rule sees it. */
public class CycleA {

    CycleB partner;
}
