package com.thanhdatpb.listingguard.client;

/**
 * The closed list of API calls the SDK can make (ADR-023). Only {@code GET} and {@code POST}; nothing here writes anything but
 * the result the API itself stores, edits a listing or publishes. {@code {marketplace}} is the one path parameter and is
 * percent-encoded.
 */
public enum Operation {

    /** {@code POST /api/v1/checks}. */
    CHECKS("POST", "/api/v1/checks"),
    /** {@code POST /api/v1/trademark-checks}. */
    PHRASE_SCREENINGS("POST", "/api/v1/phrase-screenings"),
    TRADEMARK_CHECKS("POST", "/api/v1/trademark-checks"),
    /** {@code POST /api/v1/variation-checks}. */
    VARIATION_CHECKS("POST", "/api/v1/variation-checks"),
    /** {@code POST /api/v1/listing-edits} (ADR-033). */
    LISTING_EDITS("POST", "/api/v1/listing-edits"),
    /** {@code POST /api/v1/personalization-screenings}. */
    PERSONALIZATION_SCREENINGS("POST", "/api/v1/personalization-screenings"),
    /** {@code GET /api/v1/rulesets/{marketplace}/limits}. */
    RULESET_LIMITS("GET", "/api/v1/rulesets/{marketplace}/limits"),
    /** {@code GET /api/v1/usage}. */
    USAGE("GET", "/api/v1/usage"),
    /** {@code GET /api/v1/capabilities}. */
    CAPABILITIES("GET", "/api/v1/capabilities"),
    /** {@code GET /api/v1/trademark-watchlist} (ADR-031). */
    TRADEMARK_WATCHLIST("GET", "/api/v1/trademark-watchlist"),
    /**
     * {@code PUT /api/v1/trademark-watchlist} (ADR-031, which amends ADR-023): the one call that is neither a {@code GET} nor a
     * {@code POST}. It replaces the client's own watchlist, a configuration of the calling client; it never edits a listing and
     * never writes to a marketplace.
     */
    TRADEMARK_WATCHLIST_REPLACE("PUT", "/api/v1/trademark-watchlist");

    private final String method;
    private final String path;

    Operation(String method, String path) {
        this.method = method;
        this.path = path;
    }

    /** {@code GET}, {@code POST} or, for {@link #TRADEMARK_WATCHLIST_REPLACE} only, {@code PUT}. */
    public String method() {
        return method;
    }

    /** The path, with {@code {marketplace}} still in it for {@link #RULESET_LIMITS}. */
    public String path() {
        return path;
    }

    /** True for the check operations, the {@code POST} calls. */
    public boolean isPost() {
        return "POST".equals(method);
    }

    /** True for the calls that send a JSON body: the four {@code POST}s and the {@code PUT} of the watchlist. */
    public boolean hasBody() {
        return !"GET".equals(method);
    }
}
