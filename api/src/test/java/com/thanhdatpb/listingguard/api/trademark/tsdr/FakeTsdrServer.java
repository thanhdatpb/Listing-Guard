package com.thanhdatpb.listingguard.api.trademark.tsdr;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the USPTO TSDR status document on the loopback address, shaped like the defaults of
 * {@link TsdrProperties.Download} (themselves partly unverified). Each serial number can be told to answer in a way the client
 * and the job must survive. It records every request with the key header it carried.
 */
final class FakeTsdrServer implements AutoCloseable {

    static final String KEY_HEADER = "USPTO-API-KEY";

    /** How a serial number answers. */
    enum Behaviour {
        /** HTTP 200 with a status document */
        OK,
        /** HTTP 404 */
        NOT_FOUND,
        /** HTTP 429 with Retry-After: 0 on the first request, then OK */
        RATE_LIMITED_ONCE,
        /** HTTP 429 every time */
        RATE_LIMITED_ALWAYS,
        /** HTTP 429 with Retry-After: 99999 */
        RATE_LIMITED_LONG,
        /** HTTP 500 every time */
        SERVER_ERROR,
        /** HTTP 403 */
        REFUSED,
        /** HTTP 200 with something that is not a status document */
        GARBAGE,
        /** HTTP 200 whose body is too long for the settings of the test */
        TOO_BIG,
        /** a 302 to somewhere else */
        REDIRECT,
        /** no answer within the read timeout */
        STALL
    }

    record Request(String path, String key) {
    }

    private final HttpServer server;
    private final Map<String, Behaviour> behaviours = new ConcurrentHashMap<>();
    private final Map<String, String> documents = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> seen = new ConcurrentHashMap<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile String requiredKey;

    FakeTsdrServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // a stalled answer must not hold up the next one
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/ts/cd/casestatus/", this::status);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The key the server accepts; any other (or none) is answered with 401. */
    FakeTsdrServer requireKey(String key) {
        requiredKey = key;
        return this;
    }

    FakeTsdrServer serve(String serial, Behaviour behaviour, String document) {
        behaviours.put(serial, behaviour);
        if (document != null) {
            documents.put(serial, document);
        }
        return this;
    }

    FakeTsdrServer serve(String serial, Behaviour behaviour) {
        return serve(serial, behaviour, null);
    }

    /** Requests of this serial number. */
    int calls(String serial) {
        AtomicInteger count = seen.get(serial);
        return count == null ? 0 : count.get();
    }

    List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    void clearRequests() {
        requests.clear();
        seen.clear();
    }

    /**
     * Settings as an operator would write them, bound the way Spring binds them. The calls are fast and unpaced unless a test
     * says otherwise (nothing here sleeps for real: the tests that need time use a recording sleeper).
     */
    static TsdrProperties properties(Map<String, String> extra) {
        String prefix = "listing-guard.trademark.tsdr.";
        Map<String, String> values = new java.util.HashMap<>(Map.of(
                prefix + "download.min-interval", "0s",
                prefix + "download.initial-backoff", "0s",
                prefix + "download.max-backoff", "1m",
                prefix + "download.max-attempts", "3",
                prefix + "download.read-timeout", "300ms",
                prefix + "download.allowed-hosts", "127.0.0.1"));
        extra.forEach((key, value) -> values.put(prefix + key, value));
        return new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(values))
                .bindOrCreate("listing-guard.trademark.tsdr", TsdrProperties.class);
    }

    /** A status document in the shape the default field names read. */
    static String xml(String code, String description, String date) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Transaction xmlns=\"urn:synthetic\"><TrademarkBag><Trademark>"
                + "<MarkCurrentStatusCode>" + code + "</MarkCurrentStatusCode>"
                + (description == null ? "" : "<MarkCurrentStatusExternalDescriptionText>" + description
                + "</MarkCurrentStatusExternalDescriptionText>")
                + (date == null ? "" : "<MarkCurrentStatusDate>" + date + "</MarkCurrentStatusDate>")
                + "</Trademark></TrademarkBag></Transaction>";
    }

    private void status(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String key = exchange.getRequestHeaders().getFirst(KEY_HEADER);
        requests.add(new Request(path, key));
        // /ts/cd/casestatus/sn<serial>/info.xml
        String serial = path.substring("/ts/cd/casestatus/sn".length(), "/ts/cd/casestatus/sn".length() + 8);
        int nth = seen.computeIfAbsent(serial, s -> new AtomicInteger()).incrementAndGet();
        if (requiredKey != null && !requiredKey.equals(key)) {
            send(exchange, 401, "", null);
            return;
        }
        Behaviour behaviour = behaviours.getOrDefault(serial, Behaviour.NOT_FOUND);
        switch (behaviour) {
            case OK -> send(exchange, 200, documents.getOrDefault(serial, xml("700", "REGISTERED", "2020-01-02")), null);
            case NOT_FOUND -> send(exchange, 404, "", null);
            case RATE_LIMITED_ONCE -> {
                if (nth == 1) {
                    send(exchange, 429, "", "0");
                } else {
                    send(exchange, 200, documents.getOrDefault(serial, xml("700", "REGISTERED", "2020-01-02")), null);
                }
            }
            case RATE_LIMITED_ALWAYS -> send(exchange, 429, "", "0");
            case RATE_LIMITED_LONG -> send(exchange, 429, "", "99999");
            case SERVER_ERROR -> send(exchange, 500, "", null);
            case REFUSED -> send(exchange, 403, "", null);
            case GARBAGE -> send(exchange, 200, "no status here", null);
            case TOO_BIG -> send(exchange, 200, "<a>" + "x".repeat(4096) + "</a>", null);
            case REDIRECT -> {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
                send(exchange, 302, "", null);
            }
            case STALL -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                send(exchange, 200, xml("700", "REGISTERED", "2020-01-02"), null);
            }
            default -> throw new IllegalStateException();
        }
    }

    private static void send(HttpExchange exchange, int code, String body, String retryAfter) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (retryAfter != null) {
            exchange.getResponseHeaders().add("Retry-After", retryAfter);
        }
        try {
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (IOException e) {
            // a client that gave up (the stall case) closed the connection
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
