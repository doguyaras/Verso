package com.verso.archfixture.tx;

/** Stands in for a Verso HTTP client: reference 4.3 names every *Client a remote call. */
public interface ModelClient {

    String complete(String prompt);
}
