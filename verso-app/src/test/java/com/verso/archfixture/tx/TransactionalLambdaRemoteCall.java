package com.verso.archfixture.tx;

import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/** The remote call sits in a lambda run inside the transaction (phase 2 test review T10). */
public class TransactionalLambdaRemoteCall {

    @Transactional
    public void restCallInLambdaInsideTransaction(RestClient client, List<String> ids) {
        ids.forEach(id -> client.get());
    }
}
