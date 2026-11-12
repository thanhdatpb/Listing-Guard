package com.thanhdatpb.listingguard.client;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the version and deprecation headers of every answer and tells the listener once per value (ADR-023). Bounded: at most
 * {@link #MAX_REMEMBERED} values of each kind are remembered, so a server that sends a new value on every answer cannot make
 * the client grow. A listener that throws is ignored.
 */
final class Notices {

    static final int MAX_REMEMBERED = 64;
    private static final int MAX_HEADER_CHARS = 200;

    private final ListingGuardListener listener;
    private final Set<String> versions = ConcurrentHashMap.newKeySet();
    private final Set<String> deprecations = ConcurrentHashMap.newKeySet();

    Notices(ListingGuardListener listener) {
        this.listener = listener;
    }

    void observe(Operation operation, RawResponse response) {
        if (listener == null) {
            return;
        }
        String version = clean(response.header("X-Listing-Guard-Version"));
        if (version != null && remember(versions, version)) {
            safely(() -> listener.onServerVersion(new ServerVersionNotice(version, SdkVersion.VALUE, operation)));
        }
        String deprecation = clean(response.header("Deprecation"));
        if (deprecation != null && remember(deprecations, operation + " " + deprecation)) {
            DeprecationNotice notice = new DeprecationNotice(operation, deprecation, clean(response.header("Sunset")),
                    clean(response.header("Link")), clean(response.requestId()));
            safely(() -> listener.onDeprecation(notice));
        }
    }

    void retry(Operation operation, int nextAttempt, int status, String code, Duration delay, String requestId) {
        if (listener != null) {
            RetryNotice notice = new RetryNotice(operation, nextAttempt, status, code, delay, requestId);
            safely(() -> listener.onRetry(notice));
        }
    }

    private static boolean remember(Set<String> seen, String value) {
        if (seen.contains(value)) {
            return false;
        }
        if (seen.size() >= MAX_REMEMBERED) {
            return false;
        }
        return seen.add(value);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip().replaceAll("\\p{Cntrl}", " ");
        return text.length() <= MAX_HEADER_CHARS ? text : text.substring(0, MAX_HEADER_CHARS);
    }

    private static void safely(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException ignored) {
            // A listener can never break a call.
        }
    }
}
