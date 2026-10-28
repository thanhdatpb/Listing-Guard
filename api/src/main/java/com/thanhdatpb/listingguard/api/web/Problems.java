package com.thanhdatpb.listingguard.api.web;

import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds the RFC 7807 problem responses of the API. Every problem has a stable {@code code} (and a
 * {@code type} URN made from it) for machines, and the {@code requestId} of the failed request when known.
 */
public final class Problems {

    public static final String TYPE_PREFIX = "urn:listing-guard:problem:";
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title(status));
        decorate(problem, code);
        return problem;
    }

    /** Adds {@code type}, {@code code} and {@code requestId} unless the problem already has a code. */
    public static void decorate(ProblemDetail problem, String code) {
        if (problem.getProperties() != null && problem.getProperties().containsKey("code")) {
            return;
        }
        problem.setType(URI.create(TYPE_PREFIX + code));
        problem.setProperty("code", code);
        String requestId = MDC.get(REQUEST_ID_MDC_KEY);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
    }

    /** Stable code for a status when no more specific one applies. */
    public static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "invalid-request";
            case 401 -> "unauthorized";
            case 404 -> "not-found";
            case 405 -> "method-not-allowed";
            case 406 -> "not-acceptable";
            case 413 -> "payload-too-large";
            case 415 -> "unsupported-media-type";
            case 422 -> "unprocessable-request";
            case 429 -> "rate-limited";
            case 503 -> "service-unavailable";
            default -> status.is5xxServerError() ? "internal-error" : "error";
        };
    }

    private static String title(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known == null ? "Error" : known.getReasonPhrase();
    }
}
