package com.verso.platform.security.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * The port of the separate management server, learned when it starts (its WebServerInitializedEvent, published in
 * the management child context, also reaches this listener in the parent). The Prometheus scrape is open without a
 * token only for requests that arrived on that port: when the actuator shares the API port (management.server.port
 * unset or equal to server.port), there is no such port and the scrape needs a token like everything else (phase 7
 * review B3; EndpointRequest matches the path only).
 */
public final class ManagementServerPort implements ApplicationListener<WebServerInitializedEvent> {

    static final String NAMESPACE = "management";

    private volatile int port = -1;

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (NAMESPACE.equals(event.getApplicationContext().getServerNamespace())) {
            port = event.getWebServer().getPort();
        }
    }

    /** True only for a request that arrived on the separate management server. */
    public boolean matches(HttpServletRequest request) {
        return port > 0 && request.getLocalPort() == port;
    }
}
