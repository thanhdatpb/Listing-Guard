package com.thanhdatpb.listingguard.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An error answer of the API, read as an RFC 7807 problem (ADR-005). When the answer is not a Listing Guard problem (an HTML
 * page of a proxy, an empty body) only {@code status} and perhaps {@code requestId} are set, and
 * {@link #isListingGuardProblem()} is false.
 *
 * @param status     the HTTP status
 * @param type       the {@code type} URI ({@code urn:listing-guard:problem:<code>}), or {@code null}
 * @param code       the stable code, read from {@code type} or the {@code code} member; {@code null} when the answer is not a
 *                   Listing Guard problem
 * @param title      the {@code title}, or {@code null}
 * @param detail     the {@code detail}, or {@code null}; for people, never branch on it
 * @param instance   the {@code instance}, or {@code null}
 * @param requestId  the {@code requestId} member or the {@code X-Request-Id} header, or {@code null}
 * @param properties every other member (for example {@code plan}, {@code known}, {@code errors}), as plain Java values
 *                   ({@code String}, {@code Number}, {@code Boolean}, {@code List}, {@code Map})
 */
public record ApiProblem(
        int status,
        String type,
        String code,
        String title,
        String detail,
        String instance,
        String requestId,
        Map<String, Object> properties) {

    /** The prefix of every {@code type} the API sends. */
    public static final String TYPE_PREFIX = "urn:listing-guard:problem:";

    public ApiProblem {
        properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /** True when the answer is a problem of the Listing Guard API (it has a {@code code}). */
    public boolean isListingGuardProblem() {
        return code != null;
    }

    /** A member as text, or {@code null} when it is absent or not text. */
    public String text(String name) {
        return properties.get(name) instanceof String s ? s : null;
    }

    /** A member as a whole number, or {@code null} when it is absent or not a whole number. */
    public Long number(String name) {
        Object value = properties.get(name);
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return ((Number) value).longValue();
        }
        if (value instanceof java.math.BigInteger big) {
            return big.longValue();
        }
        return null;
    }

    /** A member that is a list of texts (other items skipped), or an empty list. */
    public List<String> texts(String name) {
        if (!(properties.get(name) instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
