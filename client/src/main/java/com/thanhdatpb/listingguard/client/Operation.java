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
    TRADEMARK_CHECKS("POST", "/api/v1/trademark-checks"),
    /** {@code POST /api/v1/variation-checks}. */
    VARIATION_CHECKS("POST", "/api/v1/variation-checks"),
    /** {@code POST /api/v1/personalization-screenings}. */
    PERSONALIZATION_SCREENINGS("POST", "/api/v1/personalization-screenings"),
    /** {@code GET /api/v1/rulesets/{marketplace}/limits}. */
    RULESET_LIMITS("GET", "/api/v1/rulesets/{marketplace}/limits"),
    /** {@code GET /api/v1/usage}. */
    USAGE("GET", "/api/v1/usage"),
    /** {@code GET /api/v1/capabilities}. */
    CAPABILITIES("GET", "/api/v1/capabilities");

    private final String method;
    private final String path;

    Operation(String method, String path) {
        this.method = method;
        this.path = path;
    }

    /** {@code GET} or {@code POST}. */
    public String method() {
        return method;
    }

    /** The path, with {@code {marketplace}} still in it for {@link #RULESET_LIMITS}. */
    public String path() {
        return path;
    }

    /** True for the four calls that send a JSON body. */
    public boolean isPost() {
        return "POST".equals(method);
    }
}
