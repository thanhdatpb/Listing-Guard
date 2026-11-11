package com.thanhdatpb.listingguard.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

class ProblemsTest {

    /**
     * The titles of the published contract ({@code ApiExamples}, {@code docs/api/openapi.json}). Spring Framework 7 words these two
     * statuses after RFC 9110 ("Content Too Large", "Unprocessable Content"); the API keeps the earlier titles (ADR-020).
     */
    @Test
    void thePublishedTitlesOfTheTwoRenamedStatusesAreKept() {
        assertThat(title(413)).isEqualTo("Payload Too Large");
        assertThat(title(422)).isEqualTo("Unprocessable Entity");
    }

    @Test
    void otherStatusesUseTheReasonPhraseOfTheFramework() {
        assertThat(title(400)).isEqualTo("Bad Request");
        assertThat(title(401)).isEqualTo("Unauthorized");
        assertThat(title(403)).isEqualTo("Forbidden");
        assertThat(title(404)).isEqualTo("Not Found");
        assertThat(title(405)).isEqualTo("Method Not Allowed");
        assertThat(title(415)).isEqualTo("Unsupported Media Type");
        assertThat(title(429)).isEqualTo("Too Many Requests");
        assertThat(title(500)).isEqualTo("Internal Server Error");
        assertThat(title(503)).isEqualTo("Service Unavailable");
    }

    @Test
    void anUnknownStatusIsJustAnError() {
        assertThat(title(499)).isEqualTo("Error");
    }

    private static String title(int status) {
        ProblemDetail problem = Problems.of(HttpStatusCode.valueOf(status), Problems.codeFor(HttpStatusCode.valueOf(status)), "detail");
        assertThat(problem.getStatus()).isEqualTo(status);
        return problem.getTitle();
    }
}
