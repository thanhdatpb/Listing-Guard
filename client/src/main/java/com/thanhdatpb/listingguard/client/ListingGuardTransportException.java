package com.thanhdatpb.listingguard.client;

import java.util.Objects;

/**
 * There is no answer of the API that can be used: nothing reachable, a timeout, an answer that is too large, a redirect (never
 * followed, so the key is never sent to another host), a {@code 2xx} that is not a JSON object, or an interrupted call. The
 * SDK does not retry these (ADR-023).
 */
public class ListingGuardTransportException extends ListingGuardException {

    private static final long serialVersionUID = 1L;

    /** Why there is no usable answer. */
    public enum Kind {
        /** No connection: wrong base URL, the API is down, a firewall. */
        UNREACHABLE,
        /** No complete answer within the configured timeout. */
        TIMEOUT,
        /** The answer is larger than the configured maximum and was refused. */
        RESPONSE_TOO_LARGE,
        /** The answer was a redirect, which is not followed. Configure the final URL. */
        REDIRECT,
        /** The answer could not be read as what the call expects (not JSON, an unexpected status). */
        INVALID_RESPONSE,
        /** The calling thread was interrupted while it waited. */
        INTERRUPTED,
        /** Something else failed inside the SDK. */
        FAILED
    }

    private final Kind kind;

    public ListingGuardTransportException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** Why there is no usable answer. */
    public Kind kind() {
        return kind;
    }
}
