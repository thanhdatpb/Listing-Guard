package com.thanhdatpb.listingguard.mcp;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A stand-in for the Listing Guard API: the JDK's own HTTP server on a free loopback port, the way the repository stubs
 * the embedding API. It records every request and answers what the test says. No real API, no database, no Docker.
 */
final class ApiStub implements AutoCloseable {

    /** The key every test uses. Recognizable on purpose: tests search outputs for it. */
    static final String KEY = "lg_SECRETKEY_5f3a9c1e7b2d4a6c8e0f1a3b5c7d9e2f4a6b8c0d";

    record Recorded(String method, String target, Map<String, List<String>> headers, String body) {

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }

        String path() {
            int q = target.indexOf('?');
            return q < 0 ? target : target.substring(0, q);
        }
    }

    record Reply(int status, String body, Map<String, String> headers, Duration delay) {

        static Reply json(int status, String body) {
            return new Reply(status, body, Map.of("Content-Type", "application/json"), Duration.ZERO);
        }

        static Reply problem(int status, String body) {
            return new Reply(status, body, Map.of("Content-Type", "application/problem+json"), Duration.ZERO);
        }

        Reply with(String name, String value) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(name, value);
            return new Reply(status, body, h, delay);
        }

        Reply after(Duration d) {
            return new Reply(status, body, headers, d);
        }
    }

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Recorded, Reply> handler = r -> Reply.json(200, "{}");

    ApiStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            Map<String, List<String>> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, new ArrayList<>(v)));
            String target = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            Recorded recorded = new Recorded(exchange.getRequestMethod(), target, headers,
                    new String(body, StandardCharsets.UTF_8));
            requests.add(recorded);

            Reply reply = handler.apply(recorded);
            if (!reply.delay().isZero()) {
                try {
                    Thread.sleep(reply.delay().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            String requestId = recorded.header("X-Request-Id");
            if (requestId != null && !reply.headers().containsKey("X-Request-Id")) {
                exchange.getResponseHeaders().add("X-Request-Id", requestId);
            }
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(out);
                    }
                }
            } catch (IOException e) {
                // The client gave up (a timeout test): nothing to answer.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    ApiStub replying(Reply reply) {
        this.handler = r -> reply;
        return this;
    }

    ApiStub replying(Function<Recorded, Reply> handler) {
        this.handler = handler;
        return this;
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    int port() {
        return server.getAddress().getPort();
    }

    List<Recorded> requests() {
        return requests;
    }

    Recorded only() {
        if (requests.size() != 1) {
            throw new AssertionError("expected exactly one request, got " + requests.size());
        }
        return requests.get(0);
    }

    /** A configuration for this stub: stdio, a short timeout, the recognizable key. */
    McpServerConfig config() {
        return config(Duration.ofSeconds(5));
    }

    McpServerConfig config(Duration timeout) {
        return new McpServerConfig(KEY, baseUri(), timeout, TransportKind.STDIO,
                InetAddress.getLoopbackAddress(), 0, false);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
