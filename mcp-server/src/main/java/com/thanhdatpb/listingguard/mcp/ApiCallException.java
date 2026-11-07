package com.thanhdatpb.listingguard.mcp;

/**
 * The call to the API did not produce an answer that can be used: nothing reachable, a timeout, an answer that is too
 * large. {@code code} is one of this server's own codes (ADR-017), the message is generic and holds no stack trace, no
 * header and no secret.
 */
final class ApiCallException extends Exception {

    private static final long serialVersionUID = 1L;

    static final String UNREACHABLE = "api-unreachable";
    static final String TIMEOUT = "api-timeout";
    static final String INVALID_RESPONSE = "api-invalid-response";
    static final String INTERNAL = "internal-error";

    private final String code;

    ApiCallException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    String code() {
        return code;
    }
}
