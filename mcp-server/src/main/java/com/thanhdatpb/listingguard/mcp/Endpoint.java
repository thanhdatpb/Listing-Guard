package com.thanhdatpb.listingguard.mcp;

import com.thanhdatpb.listingguard.client.Operation;

/**
 * The closed list of API calls this server can make (ADR-017). Only {@code GET} and {@code POST}; no tool takes a path or
 * a URL, so an agent can reach nothing else. Each one is an operation of the Java client SDK (ADR-023), which builds the
 * request; {@code {marketplace}} is the one path parameter and is percent-encoded there.
 */
enum Endpoint {
    CHECKS(Operation.CHECKS),
    PHRASE_SCREENINGS(Operation.PHRASE_SCREENINGS),
    TRADEMARK_CHECKS(Operation.TRADEMARK_CHECKS),
    VARIATION_CHECKS(Operation.VARIATION_CHECKS),
    LISTING_EDITS(Operation.LISTING_EDITS),
    PERSONALIZATION_SCREENINGS(Operation.PERSONALIZATION_SCREENINGS),
    RULESET_LIMITS(Operation.RULESET_LIMITS),
    USAGE(Operation.USAGE),
    CAPABILITIES(Operation.CAPABILITIES),
    /** Read only: the watchlist is changed in the console or with the API, never by an agent (ADR-031, ADR-017). */
    TRADEMARK_WATCHLIST(Operation.TRADEMARK_WATCHLIST);

    private final Operation operation;

    Endpoint(Operation operation) {
        this.operation = operation;
    }

    /** The SDK operation behind this endpoint. */
    Operation operation() {
        return operation;
    }

    String method() {
        return operation.method();
    }

    /** The path, with {@code {marketplace}} still in it for {@link #RULESET_LIMITS}. */
    String path() {
        return operation.path();
    }
}
