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

    /**
     * The {@code title} of a problem. Spring Framework 7 renamed two reason phrases to the wording of RFC 9110 (413 "Payload Too
     * Large" became "Content Too Large", 422 "Unprocessable Entity" became "Unprocessable Content"). The published contract
     * ({@code ApiExamples}, {@code docs/api/openapi.json}) shows the earlier titles and the upgrade to Spring Boot 4 must not
     * change a response (ADR-020), so these two keep them. A client branches on {@code code}, never on the title.
     */
    static String title(HttpStatusCode status) {
        return switch (status.value()) {
            case 413 -> "Payload Too Large";
            case 422 -> "Unprocessable Entity";
            default -> {
                HttpStatus known = HttpStatus.resolve(status.value());
                yield known == null ? "Error" : known.getReasonPhrase();
            }
        };
    }
}
