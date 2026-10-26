package com.verso.qa.config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import okhttp3.Interceptor;
import okhttp3.Response;
import org.springframework.boot.http.client.InetAddressFilter;

/**
 * The outbound address rule of local mode (ADR-0006 decision 1.2) for the OpenAI-compatible client, which brings its
 * own HTTP stack (OkHttp) and so escapes the {@link InetAddressFilter} bean that guards every client Boot builds. In
 * local mode the chat call may go to a model server on the internal network only (ADR-0051): every request resolves the
 * server's name first and is refused, before anything is sent, when any of its addresses is not internal (loopback,
 * link-local or private). The compose network (internal, no gateway) is the first guarantee; this is the second, for an
 * installation that puts the application on a network with a route out.
 */
final class LocalModelEgress implements Interceptor {

    private static final InetAddressFilter INTERNAL = InetAddressFilter.internalAddresses();

    @Override
    public Response intercept(Chain chain) throws IOException {
        requireInternal(chain.request().url().host());
        return chain.proceed(chain.request());
    }

    /** No address, no name in the message: the setting is known, the value is the operator's (llm-rules 2.1). */
    static void requireInternal(String host) throws IOException {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("Local model server refused: its name does not resolve");
        }
        for (InetAddress address : addresses) {
            if (!INTERNAL.matches(address)) {
                throw new IOException("Local model server refused: an address is outside the internal network "
                        + "(verso.ai.mode=local, ADR-0006)");
            }
        }
    }
}
