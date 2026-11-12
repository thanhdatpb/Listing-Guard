package com.thanhdatpb.listingguard.api.trademark.tsdr;

import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.CallResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/**
 * The client of the USPTO TSDR status document for the {@code tsdr-status} job (ADR-009, "TSDR status check"). It asks about
 * one serial number at a time and never goes beyond what the TSDR allows.
 *
 * <ul>
 *   <li>The key goes only in its header, only to the host of {@code base-url}, and never into a log, an exception, a stored
 *       value or {@code toString}; a message that would carry it has it replaced.</li>
 *   <li>The serial number is checked to be eight digits before it is put in the path, and the address must be on an allowed
 *       host over HTTPS (plain HTTP only to this machine). <b>Redirects are not followed</b>: the status document has none, so
 *       one is a failure and the key is never sent anywhere else.</li>
 *   <li>At least {@code min-interval} between the starts of two requests (the TSDR limits a key to 60 a minute). {@code 408},
 *       {@code 429}, {@code 500}, {@code 502}, {@code 503}, {@code 504}, a timeout and a broken connection are tried again, up to
 *       {@code max-attempts}, after a delay that doubles from {@code initial-backoff} to {@code max-backoff}. A
 *       {@code Retry-After} is honored; one longer than {@code max-backoff}, or a {@code 429} that lasts, ends the run
 *       ({@link Failure#stopsRun()}) so the next scheduled run continues. {@code 404} and {@code 410} are "not found" and are not
 *       tried again; {@code 401} and {@code 403} end the run.</li>
 *   <li>The answer is read up to {@code max-response-size}; the job stores three values, never the answer.</li>
 * </ul>
 * {@link HttpURLConnection} is used because it has a read timeout per read, so a stalled body ends.
 */
public class TsdrClient {

    /** What a call gave. */
    public sealed interface Reply permits Body, Failure {
    }

    /** An answer with HTTP 200. */
    public record Body(byte[] bytes, int attempts) implements Reply {
    }

    /** A call that did not give a document. {@code message} never holds the key or the answer's text. */
    public record Failure(CallResult result, Integer httpStatus, int attempts, String message, boolean stopsRun)
            implements Reply {
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Set<Integer> RETRIED = Set.of(408, 429, 500, 502, 503, 504);
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final int BUFFER_BYTES = 16 * 1024;

    private final TsdrProperties.Download settings;
    private final Clock clock;
    private final Sleeper sleeper;
    private final URI base;
    private Instant lastRequest;

    public TsdrClient(TsdrProperties.Download settings, Clock clock, Sleeper sleeper) {
        this.settings = settings;
        this.clock = clock;
        this.sleeper = sleeper;
        this.base = URI.create(settings.baseUrl());
    }

    static boolean isLoopback(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
    }

    /** The status document of a serial number (eight digits). */
    public Reply fetch(String serial) {
        if (serial == null || !serial.matches("[0-9]{8}")) {
            throw new IllegalArgumentException("A TSDR serial number is eight digits.");
        }
        String root = settings.baseUrl().endsWith("/") ? settings.baseUrl().substring(0, settings.baseUrl().length() - 1)
                : settings.baseUrl();
        URI uri = URI.create(root + settings.statusPath().replace("{serial}", serial));
        String refusal = notAllowed(uri);
        if (refusal != null) {
            return new Failure(CallResult.ERROR, null, 0, refusal, false);
        }
        CallResult last = CallResult.ERROR;
        Integer lastStatus = null;
        String lastMessage = "no attempt";
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            Duration wait = backoff(attempt);
            try {
                pace();
                HttpURLConnection connection = open(uri);
                try {
                    int code = connection.getResponseCode();
                    if (code == 200) {
                        return read(connection, attempt);
                    }
                    lastStatus = code;
                    if (code == 404 || code == 410) {
                        return new Failure(CallResult.NOT_FOUND, code, attempt, "The TSDR has no such serial number (HTTP "
                                + code + ").", false);
                    }
                    if (code == 401 || code == 403) {
                        return new Failure(CallResult.REFUSED, code, attempt, "The TSDR refused the request (HTTP " + code
                                + "); check LISTING_GUARD_TSDR_API_KEY and api-key-header.", true);
                    }
                    if (REDIRECTS.contains(code)) {
                        return new Failure(CallResult.ERROR, code, attempt, "The TSDR answered with a redirect (HTTP " + code
                                + "), which is not followed.", false);
                    }
                    if (!RETRIED.contains(code)) {
                        return new Failure(CallResult.ERROR, code, attempt, "The TSDR answered HTTP " + code + ".", false);
                    }
                    last = code == 429 ? CallResult.RATE_LIMITED : CallResult.ERROR;
                    lastMessage = "HTTP " + code;
                    Duration asked = retryAfter(connection.getHeaderField("Retry-After"));
                    if (asked != null) {
                        if (asked.compareTo(settings.maxBackoff()) > 0) {
                            return new Failure(code == 429 ? CallResult.RATE_LIMITED : CallResult.ERROR, code, attempt,
                                    "The TSDR asks to wait " + asked.toSeconds() + " s, longer than max-backoff ("
                                            + settings.maxBackoff().toSeconds() + " s); the next run continues.", true);
                        }
                        wait = asked;
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (SocketTimeoutException e) {
                last = CallResult.TIMEOUT;
                lastStatus = null;
                lastMessage = "timeout";
            } catch (IOException e) {
                last = CallResult.ERROR;
                lastStatus = null;
                lastMessage = scrub(e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Failure(CallResult.ERROR, null, attempt, "Interrupted.", true);
            }
            if (attempt < settings.maxAttempts() && !sleep(wait)) {
                return new Failure(CallResult.ERROR, lastStatus, attempt, "Interrupted.", true);
            }
        }
        // a limit that lasts through every try means asking more only makes it worse: end the run
        return new Failure(last, lastStatus, settings.maxAttempts(), "Failed after " + settings.maxAttempts()
                + " attempts; last: " + lastMessage + ".", last == CallResult.RATE_LIMITED);
    }

    private Reply read(HttpURLConnection connection, int attempt) throws IOException {
        long max = settings.maxResponseSize().toBytes();
        if (connection.getContentLengthLong() > max) {
            return new Failure(CallResult.ERROR, 200, attempt, "The answer is larger than max-response-size (" + max
                    + " bytes).", false);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream in = connection.getInputStream()) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                body.write(buffer, 0, n);
                if (body.size() > max) {
                    return new Failure(CallResult.ERROR, 200, attempt, "The answer is larger than max-response-size (" + max
                            + " bytes).", false);
                }
            }
        }
        return new Body(body.toByteArray(), attempt);
    }

    private HttpURLConnection open(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(timeout(settings.connectTimeout()));
        connection.setReadTimeout(timeout(settings.readTimeout()));
        connection.setRequestProperty("User-Agent", settings.userAgent());
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Accept", "application/xml, application/json;q=0.9");
        if (settings.hasKey()) {
            connection.setRequestProperty(settings.apiKeyHeader(), settings.apiKey());
        }
        return connection;
    }

    private String notAllowed(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme()) && isLoopback(host);
        if (!(https || loopbackHttp) || uri.getRawUserInfo() != null || !settings.allowedHosts().contains(host)
                || !host.equalsIgnoreCase(base.getHost())) {
            return "An address on host '" + host + "' is not allowed; listing-guard.trademark.tsdr.download.allowed-hosts "
                    + "lists the hosts a call may use, over HTTPS.";
        }
        return null;
    }

    private Duration backoff(int attempt) {
        Duration delay = settings.initialBackoff();
        for (int i = 1; i < attempt && delay.compareTo(settings.maxBackoff()) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(settings.maxBackoff()) > 0 ? settings.maxBackoff() : delay;
    }

    private boolean sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return true;
        }
        try {
            sleeper.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Waits until {@code min-interval} has passed since the start of the previous request. */
    private void pace() throws InterruptedException {
        Instant now = clock.instant();
        if (lastRequest != null) {
            Duration since = Duration.between(lastRequest, now);
            if (since.compareTo(settings.minInterval()) < 0) {
                sleeper.sleep(settings.minInterval().minus(since));
            }
        }
        lastRequest = clock.instant();
    }

    private Duration retryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.matches("[0-9]{1,9}")) {
            return Duration.ofSeconds(Long.parseLong(text));
        }
        try {
            Duration wait = Duration.between(clock.instant(),
                    ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int timeout(Duration duration) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, duration.toMillis()));
    }

    /** Replaces the key wherever a message would carry it. */
    private String scrub(String text) {
        return settings.hasKey() && text != null ? text.replace(settings.apiKey(), "[redacted]") : text;
    }
}
