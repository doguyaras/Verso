package com.verso.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A model provider's HTTP API on 127.0.0.1 for tests of the real, auto-configured clients (Ollama, Anthropic, OpenAI):
 * records every request (path, headers, body) and answers with a configurable status and body. No network.
 */
public final class FakeModelServer implements AutoCloseable {

    /** One request as the fake server saw it. */
    public record Recorded(String method, String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile String contentType = "application/json";
    private volatile long delayMillis;
    private volatile long trickleMillis;
    private final AtomicInteger abandoned = new AtomicInteger();

    public FakeModelServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders()),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (trickleMillis > 0) {
                trickle(exchange);
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        // One thread per request: a delayed answer must not hold up the next test's request.
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void answer(int status, String body) {
        answer(status, body, "application/json");
    }

    /** A body of another type: a provider's server-sent events for a streamed answer (ADR-0035). */
    public void answer(int status, String body, String contentType) {
        this.status = status;
        this.body = body;
        this.contentType = contentType;
    }

    /** Answers only after this long: a slow or hanging provider. */
    public void delay(java.time.Duration delay) {
        this.delayMillis = delay.toMillis();
    }

    /**
     * Sends the answer slowly, piece by piece, flushing each: the events of a server-sent stream one by one, or a JSON
     * body after a long run of leading blanks. A client that closes the connection makes a write fail: counted in
     * {@link #abandoned()}, the way a model server notices a cancelled request and stops generating.
     */
    public void trickle(java.time.Duration gap) {
        this.trickleMillis = gap.toMillis();
    }

    /** Answers the server could not finish because the client closed the connection. */
    public int abandoned() {
        return abandoned.get();
    }

    private void trickle(HttpExchange exchange) {
        List<String> pieces = new ArrayList<>();
        if (contentType.startsWith("text/event-stream")) {
            for (String event : body.split("\n\n")) if (!event.isBlank()) pieces.add(event + "\n\n");
        } else {
            for (int i = 0; i < 200; i++) pieces.add(" ");
            pieces.add(body);
        }
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, 0);
            OutputStream out = exchange.getResponseBody();
            for (String piece : pieces) {
                out.write(piece.getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(trickleMillis);
            }
        } catch (IOException e) {
            abandoned.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        requests.clear();
        status = 200;
        body = "{}";
        contentType = "application/json";
        delayMillis = 0;
        trickleMillis = 0;
        abandoned.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
