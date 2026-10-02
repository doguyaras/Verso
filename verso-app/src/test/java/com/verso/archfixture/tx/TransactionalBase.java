package com.verso.archfixture.tx;

import org.springframework.transaction.annotation.Transactional;

/** Class-level @Transactional is @Inherited: subclasses run in transactions too (phase 2 review A2). */
@Transactional
public abstract class TransactionalBase {}
