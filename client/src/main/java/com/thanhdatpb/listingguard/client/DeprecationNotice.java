package com.thanhdatpb.listingguard.client;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The API answered an operation with a {@code Deprecation} header, perhaps with {@code Sunset} and a {@code Link}. ADR-022
 * (section 6) writes them as {@code @<seconds since the epoch>} (RFC 9745), an IMF-fixdate (RFC 8594) and
 * {@code <url>; rel="deprecation"; type="text/html"}. The values are passed on as sent (each at most 200 characters);
 * {@link #deprecatedAt()} and {@link #sunsetAt()} read the two dates when they have that form.
 *
 * @param operation   the deprecated call
 * @param deprecation the {@code Deprecation} header
 * @param sunset      the {@code Sunset} header, or {@code null}
 * @param link        the {@code Link} header, or {@code null}
 * @param requestId   the request id of the answer, or {@code null}
 */
public record DeprecationNotice(Operation operation, String deprecation, String sunset, String link, String requestId) {

    /** The Deprecation date, when the header is {@code @<seconds>}; empty for any other form. */
    public Optional<Instant> deprecatedAt() {
        if (deprecation == null || !deprecation.matches("@-?\\d{1,12}")) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochSecond(Long.parseLong(deprecation.substring(1))));
    }

    /** The Sunset date, when the header is an HTTP date; empty when absent or unreadable. */
    public Optional<Instant> sunsetAt() {
        if (sunset == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZonedDateTime.parse(sunset, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
