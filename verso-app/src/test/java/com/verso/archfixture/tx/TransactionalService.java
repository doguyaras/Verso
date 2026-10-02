package com.verso.archfixture.tx;

import org.springframework.transaction.annotation.Transactional;

/** Deliberate violation: class-level @Transactional covers every public method, the call to a *Client included. */
@Transactional
public class TransactionalService {

    public String answer(ModelClient client) {
        return client.complete("prompt");
    }
}
