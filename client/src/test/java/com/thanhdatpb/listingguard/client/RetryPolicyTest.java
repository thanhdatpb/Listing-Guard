package com.thanhdatpb.listingguard.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The retry decision and the reading of {@code Retry-After}, without any HTTP. */
class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    @Test
    void retryAfterIsReadAsSecondsOrAsAnHttpDate() {
        assertThat(RetryPolicy.parseRetryAfter("7", NOW)).isEqualTo(Duration.ofSeconds(7));
        assertThat(RetryPolicy.parseRetryAfter(" 0 ", NOW)).isEqualTo(Duration.ZERO);
        assertThat(RetryPolicy.parseRetryAfter("Thu, 12 Nov 2026 10:00:30 GMT", NOW)).isEqualTo(Duration.ofSeconds(30));
        assertThat(RetryPolicy.parseRetryAfter("Thu, 12 Nov 2026 09:00:00 GMT", NOW)).as("a past date: now").isEqualTo(Duration.ZERO);
    }

    @Test
    void anUnreadableNegativeOrHugeRetryAfterIsIgnored() {
        assertThat(RetryPolicy.parseRetryAfter(null, NOW)).isNull();
        assertThat(RetryPolicy.parseRetryAfter("", NOW)).isNull();
        assertThat(RetryPolicy.parseRetryAfter("-5", NOW)).isNull();
        assertThat(RetryPolicy.parseRetryAfter("soon", NOW)).isNull();
        assertThat(RetryPolicy.parseRetryAfter("99999999999999999999", NOW)).isNull();
        assertThat(RetryPolicy.parseRetryAfter(String.valueOf(8L * 24 * 3600), NOW)).isNull();
    }

    @Test
    void onlyRateLimitedAndRetryableUnavailableAnswersAreRetried() {
        RetryPolicy p = RetryPolicy.defaults();
        assertThat(p.delayBeforeNextAttempt(429, "rate-limited", "3", 1, NOW)).contains(Duration.ofSeconds(3));
        assertThat(p.delayBeforeNextAttempt(429, "quota-exceeded", "3", 1, NOW)).isEmpty();
        assertThat(p.delayBeforeNextAttempt(429, "some-new-code", null, 1, NOW)).isEmpty();
        assertThat(p.delayBeforeNextAttempt(429, null, "3", 1, NOW)).as("a proxy's 429 with Retry-After").isPresent();
        assertThat(p.delayBeforeNextAttempt(429, null, null, 1, NOW)).isEmpty();
        assertThat(p.delayBeforeNextAttempt(503, "service-unavailable", null, 1, NOW)).isPresent();
        assertThat(p.delayBeforeNextAttempt(503, null, null, 1, NOW)).isPresent();
        assertThat(p.delayBeforeNextAttempt(503, "trademark-not-configured", null, 1, NOW)).isEmpty();
        assertThat(p.delayBeforeNextAttempt(503, "corpus-model-mismatch", null, 1, NOW)).isEmpty();
        for (int status : new int[] {400, 401, 403, 404, 409, 413, 422, 500, 502, 504}) {
            assertThat(p.delayBeforeNextAttempt(status, "x", "1", 1, NOW)).as("HTTP %d", status).isEmpty();
        }
    }

    @Test
    void theLastAttemptIsNeverFollowedByAnother() {
        RetryPolicy p = RetryPolicy.builder().maxAttempts(2).build();
        assertThat(p.delayBeforeNextAttempt(503, null, null, 1, NOW)).isPresent();
        assertThat(p.delayBeforeNextAttempt(503, null, null, 2, NOW)).isEmpty();
        assertThat(RetryPolicy.none().delayBeforeNextAttempt(503, null, null, 1, NOW)).isEmpty();
    }

    @Test
    void theBuilderRefusesValuesOutsideItsBounds() {
        assertThatThrownBy(() -> RetryPolicy.builder().maxAttempts(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().maxAttempts(RetryPolicy.MAX_ATTEMPTS + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().initialBackoff(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().maxRetryAfter(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().initialBackoff(Duration.ofSeconds(9)).maxBackoff(Duration.ofSeconds(1))
                .build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDefaultsAreTheDocumentedOnes() {
        RetryPolicy d = RetryPolicy.defaults();
        assertThat(d.maxAttempts()).isEqualTo(3);
        assertThat(d.initialBackoff()).isEqualTo(Duration.ofMillis(500));
        assertThat(d.maxBackoff()).isEqualTo(Duration.ofSeconds(5));
        assertThat(d.maxRetryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(d.jitter()).isTrue();
        assertThat(RetryPolicy.none().maxAttempts()).isEqualTo(1);
    }
}
