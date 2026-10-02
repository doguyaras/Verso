package com.verso.archfixture.tx;

import org.springframework.web.client.RestClient;

/** Violation through the inherited class-level annotation. */
public class InheritedTransaction extends TransactionalBase {

    public void call(RestClient client) {
        client.get();
    }
}
