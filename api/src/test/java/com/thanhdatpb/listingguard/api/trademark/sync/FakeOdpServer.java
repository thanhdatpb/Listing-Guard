package com.thanhdatpb.listingguard.api.trademark.sync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the USPTO Open Data Portal Bulk Datasets API on the loopback address, shaped like the defaults of
 * {@link UsptoSyncProperties.Download} (which are themselves unverified). It lists files and serves their bytes, and each
 * file can be told to fail in a way the client must survive. It records every request, with whether the key header came.
 */
final class FakeOdpServer implements AutoCloseable {

    /** How a file answers. */
    enum Behaviour {
        OK,
        /** always HTTP 500 */
        FAIL,
        /** HTTP 404 although it is listed */
        MISSING,
        /** HTTP 429 with Retry-After: 1 once, then OK */
        RATE_LIMITED_ONCE,
        /** half of the body, then the connection is closed, once; then OK */
        DROP_ONCE,
        /** HTTP 403 */
        REFUSED
    }

    record Request(String path, String query, String key) {
    }

    static final String KEY_HEADER = "X-API-KEY";

    private final HttpServer server;
    private final Map<String, Map<String, byte[]>> products = new ConcurrentHashMap<>();
    private final Map<String, Behaviour> behaviours = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> served = new ConcurrentHashMap<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> redirects = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sinceBehaviour = new ConcurrentHashMap<>();
    private volatile String listingOverride;

    FakeOdpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/datasets/products/", this::product);
        server.createContext("/files/", this::file);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Lists {@code name} under the product and serves {@code bytes} for it. */
    FakeOdpServer offer(String product, String name, byte[] bytes) {
        products.computeIfAbsent(product, p -> Collections.synchronizedMap(new LinkedHashMap<>())).put(name, bytes);
        behaviours.putIfAbsent(name, Behaviour.OK);
        return this;
    }

    /** From now on {@code name} answers this way; a "once" behaviour counts from here. */
    FakeOdpServer behave(String name, Behaviour behaviour) {
        behaviours.put(name, behaviour);
        sinceBehaviour.put(name, new AtomicInteger());
        return this;
    }

    /** Answers the download of {@code name} with a 302 to {@code location}. */
    FakeOdpServer redirect(String name, String location) {
        redirects.put(name, location);
        return this;
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** Answers every listing with this body instead. */
    FakeOdpServer listing(String body) {
        listingOverride = body;
        return this;
    }

    int downloads(String name) {
        AtomicInteger count = served.get(name);
        return count == null ? 0 : count.get();
    }

    List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    private void record(HttpExchange exchange) {
        requests.add(new Request(exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst(KEY_HEADER)));
    }

    private void product(HttpExchange exchange) throws IOException {
        record(exchange);
        String product = exchange.getRequestURI().getPath().substring("/api/v1/datasets/products/".length());
        String body = listingOverride;
        if (body == null) {
            StringBuilder files = new StringBuilder();
            for (Map.Entry<String, byte[]> file : products.getOrDefault(product, Map.of()).entrySet()) {
                if (!files.isEmpty()) {
                    files.append(',');
                }
                files.append("{\"fileName\":\"").append(file.getKey()).append("\",\"fileSize\":")
                        .append(file.getValue().length).append(",\"fileDownloadURI\":\"").append(baseUrl())
                        .append("/files/").append(file.getKey()).append("\"}");
            }
            body = "{\"count\":1,\"bulkDataProductBag\":[{\"productIdentifier\":\"" + product
                    + "\",\"productFileBag\":{\"fileDataBag\":[" + files + "]}}]}";
        }
        send(exchange, 200, body.getBytes(StandardCharsets.UTF_8), null);
    }

    private void file(HttpExchange exchange) throws IOException {
        record(exchange);
        String name = exchange.getRequestURI().getPath().substring("/files/".length());
        served.computeIfAbsent(name, n -> new AtomicInteger()).incrementAndGet();
        int attempt = sinceBehaviour.computeIfAbsent(name, n -> new AtomicInteger()).incrementAndGet();
        String location = redirects.get(name);
        if (location != null) {
            exchange.getResponseHeaders().add("Location", location);
            send(exchange, 302, new byte[0], null);
            return;
        }
        byte[] bytes = products.values().stream().map(files -> files.get(name)).filter(b -> b != null).findFirst()
                .orElse(null);
        Behaviour behaviour = behaviours.getOrDefault(name, Behaviour.OK);
        if (bytes == null || behaviour == Behaviour.MISSING) {
            send(exchange, 404, new byte[0], null);
            return;
        }
        switch (behaviour) {
            case FAIL -> send(exchange, 500, "{\"error\":\"internal\"}".getBytes(StandardCharsets.UTF_8), null);
            case REFUSED -> send(exchange, 403, new byte[0], null);
            case RATE_LIMITED_ONCE -> {
                if (attempt == 1) {
                    send(exchange, 429, new byte[0], "1");
                } else {
                    send(exchange, 200, bytes, null);
                }
            }
            case DROP_ONCE -> {
                if (attempt == 1) {
                    exchange.sendResponseHeaders(200, bytes.length);
                    OutputStream out = exchange.getResponseBody();
                    out.write(bytes, 0, bytes.length / 2);
                    out.flush();
                    // the connection goes away in the middle of the body: the server refuses a short body and drops it
                    try {
                        exchange.close();
                    } catch (RuntimeException ignored) {
                        // "insufficient bytes written"; the client sees the connection end early
                    }
                } else {
                    send(exchange, 200, bytes, null);
                }
            }
            default -> send(exchange, 200, bytes, null);
        }
    }

    private static void send(HttpExchange exchange, int status, byte[] body, String retryAfter) throws IOException {
        if (retryAfter != null) {
            exchange.getResponseHeaders().add("Retry-After", retryAfter);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
