package com.verso.archfixture.tx;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/** @Transactional on the interface method: Spring applies it to the implementation (phase 2 review A2). */
public interface TransactionalPort {

    @Transactional
    void fetch(RestClient client);
}
