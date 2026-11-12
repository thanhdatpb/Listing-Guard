package com.thanhdatpb.listingguard.client;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * When the SDK retries a call (ADR-023). Immutable.
 *
 * <p><b>Retried:</b> {@code 429 rate-limited}, after {@code Retry-After} (seconds or an HTTP date) when the API gave it,
 * otherwise after a backoff; a {@code 429} that is not a Listing Guard problem only when it carries {@code Retry-After}; and
 * {@code 503}, unless its code ends in {@code -not-configured} or is {@code corpus-model-mismatch}.
 *
 * <p><b>Never retried:</b> {@code 429 quota-exceeded} (not before its {@code resetAt}, even if a proxy added
 * {@code Retry-After}), every other {@code 4xx} and {@code 5xx}, a network error and a timeout. A {@code Retry-After} longer
 * than {@link #maxRetryAfter()} is not waited for: the call fails at once with {@link RateLimitedException}, whose
 * {@code retryAfter} says when to try again.
 */
public final class RetryPolicy {

    private static final RetryPolicy NONE = new RetryPolicy(1, Duration.ofMillis(500), Duration.ofSeconds(5),
            Duration.ofSeconds(30), true);
    private static final RetryPolicy DEFAULTS = new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(5),
            Duration.ofSeconds(30), true);

    /** The most attempts a policy may allow. */
    public static final int MAX_ATTEMPTS = 10;

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration maxRetryAfter;
    private final boolean jitter;

    private RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, Duration maxRetryAfter,
            boolean jitter) {
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.maxRetryAfter = maxRetryAfter;
        this.jitter = jitter;
    }

    /** One attempt, no retry. The MCP server uses it: the agent sees the answer once and decides (ADR-017). */
    public static RetryPolicy none() {
        return NONE;
    }

    /**
     * At most 3 attempts, backoff from 500 ms doubling up to 5 s with jitter, and a {@code Retry-After} of at most 30 s is
     * waited for.
     */
    public static RetryPolicy defaults() {
        return DEFAULTS;
    }

    /** A builder that starts from {@link #defaults()}. */
    public static Builder builder() {
        return new Builder();
    }

    /** Attempts in all, the first included (1 means no retry). */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** The wait before the first retry when the API gave no {@code Retry-After}; it doubles for each further retry. */
    public Duration initialBackoff() {
        return initialBackoff;
    }

    /** The longest backoff. */
    public Duration maxBackoff() {
        return maxBackoff;
    }

    /** The longest {@code Retry-After} the SDK waits for; a longer one ends the call with the answer's exception. */
    public Duration maxRetryAfter() {
        return maxRetryAfter;
    }

    /** True when a backoff is randomized between half and all of its value. */
    public boolean jitter() {
        return jitter;
    }

    @Override
    public String toString() {
        return "RetryPolicy[maxAttempts=" + maxAttempts + ", initialBackoff=" + initialBackoff + ", maxBackoff=" + maxBackoff
                + ", maxRetryAfter=" + maxRetryAfter + ", jitter=" + jitter + "]";
    }

    /**
     * How long to wait before the next attempt, or empty when the answer is final.
     *
     * @param status        the answer's status
     * @param code          the answer's problem code, or {@code null} when it is not a Listing Guard problem
     * @param retryAfter    the answer's {@code Retry-After} header, or {@code null}
     * @param attemptsSoFar the attempts made, the one that got this answer included
     * @param now           the current time, for an HTTP-date {@code Retry-After}
     */
    Optional<Duration> delayBeforeNextAttempt(int status, String code, String retryAfter, int attemptsSoFar, Instant now) {
        if (attemptsSoFar >= maxAttempts || !retryable(status, code, retryAfter != null)) {
            return Optional.empty();
        }
        Duration asked = parseRetryAfter(retryAfter, now);
        if (asked != null) {
            return asked.compareTo(maxRetryAfter) <= 0 ? Optional.of(asked) : Optional.empty();
        }
        return Optional.of(backoff(attemptsSoFar));
    }

    private static boolean retryable(int status, String code, boolean hasRetryAfter) {
        if (status == 429) {
            if (code == null) {
                return hasRetryAfter;
            }
            return "rate-limited".equals(code);
        }
        if (status == 503) {
            return code == null || !(code.endsWith("-not-configured") || "corpus-model-mismatch".equals(code));
        }
        return false;
    }

    private Duration backoff(int attemptsSoFar) {
        long base = initialBackoff.toMillis();
        for (int i = 1; i < attemptsSoFar && base < maxBackoff.toMillis(); i++) {
            base *= 2;
        }
        base = Math.min(base, maxBackoff.toMillis());
        if (!jitter || base < 2) {
            return Duration.ofMillis(base);
        }
        long half = base / 2;
        return Duration.ofMillis(half + ThreadLocalRandom.current().nextLong(base - half + 1));
    }

    /**
     * {@code Retry-After} as a wait: a number of seconds or an HTTP date (RFC 9110). {@code null} when absent, unreadable,
     * negative or longer than a week.
     */
    static Duration parseRetryAfter(String header, Instant now) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.strip();
        Duration wait;
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                wait = Duration.ofSeconds(Long.parseLong(value));
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                wait = Duration.between(now, at);
                if (wait.isNegative()) {
                    wait = Duration.ZERO;
                }
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return wait.isNegative() || wait.compareTo(Duration.ofDays(7)) > 0 ? null : wait;
    }

    /** Builds a {@link RetryPolicy}; every setting starts at the value of {@link #defaults()}. */
    public static final class Builder {

        private int maxAttempts = DEFAULTS.maxAttempts;
        private Duration initialBackoff = DEFAULTS.initialBackoff;
        private Duration maxBackoff = DEFAULTS.maxBackoff;
        private Duration maxRetryAfter = DEFAULTS.maxRetryAfter;
        private boolean jitter = DEFAULTS.jitter;

        private Builder() {
        }

        /** Attempts in all, 1 to {@value RetryPolicy#MAX_ATTEMPTS}; 1 means no retry. */
        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS) {
                throw new IllegalArgumentException("maxAttempts must be between 1 and " + MAX_ATTEMPTS);
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        /** The first backoff, more than zero. */
        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = positive(initialBackoff, "initialBackoff");
            return this;
        }

        /** The longest backoff, more than zero. */
        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = positive(maxBackoff, "maxBackoff");
            return this;
        }

        /** The longest {@code Retry-After} waited for, zero or more (zero: never wait for one). */
        public Builder maxRetryAfter(Duration maxRetryAfter) {
            Objects.requireNonNull(maxRetryAfter, "maxRetryAfter");
            if (maxRetryAfter.isNegative()) {
                throw new IllegalArgumentException("maxRetryAfter must not be negative");
            }
            this.maxRetryAfter = maxRetryAfter;
            return this;
        }

        /** Randomize each backoff between half and all of its value (on by default). */
        public Builder jitter(boolean jitter) {
            this.jitter = jitter;
            return this;
        }

        public RetryPolicy build() {
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("maxBackoff must not be shorter than initialBackoff");
            }
            return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff, maxRetryAfter, jitter);
        }

        private static Duration positive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be more than zero");
            }
            return value;
        }
    }
}
