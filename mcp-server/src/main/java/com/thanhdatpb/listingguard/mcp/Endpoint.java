package com.thanhdatpb.listingguard.mcp;

/**
 * The closed list of API calls this server can make (ADR-017). Only {@code GET} and {@code POST}; no tool takes a path or
 * a URL, so an agent can reach nothing else. {@code {marketplace}} is the one path parameter and is percent-encoded.
 */
enum Endpoint {
    CHECKS("POST", "/api/v1/checks"),
    TRADEMARK_CHECKS("POST", "/api/v1/trademark-checks"),
    VARIATION_CHECKS("POST", "/api/v1/variation-checks"),
    PERSONALIZATION_SCREENINGS("POST", "/api/v1/personalization-screenings"),
    RULESET_LIMITS("GET", "/api/v1/rulesets/{marketplace}/limits"),
    USAGE("GET", "/api/v1/usage");

    private final String method;
    private final String path;

    Endpoint(String method, String path) {
        this.method = method;
        this.path = path;
    }

    String method() {
        return method;
    }

    /** The path, with {@code {marketplace}} still in it for {@link #RULESET_LIMITS}. */
    String path() {
        return path;
    }
}
