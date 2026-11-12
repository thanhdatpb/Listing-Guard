package com.thanhdatpb.listingguard.client;

import java.util.Optional;

/**
 * The answer of a conditional {@code GET} ({@code If-None-Match}): either a new body with its {@code ETag}, or "not modified"
 * ({@code 304}) when the caller's copy is still current.
 *
 * @param value       the new body, or {@code null} when not modified
 * @param etag        the {@code ETag} to send next time (the new one, or the one that still matches)
 * @param notModified true for a {@code 304}
 * @param <T>         the body type
 */
public record Conditional<T>(T value, String etag, boolean notModified) {

    /** A new body. */
    public static <T> Conditional<T> modified(T value, String etag) {
        return new Conditional<>(value, etag, false);
    }

    /** The caller's copy is still current. */
    public static <T> Conditional<T> unchanged(String etag) {
        return new Conditional<>(null, etag, true);
    }

    /** The new body, or empty when not modified. */
    public Optional<T> ifModified() {
        return Optional.ofNullable(value);
    }
}
