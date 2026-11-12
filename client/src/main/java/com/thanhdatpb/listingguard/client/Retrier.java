package com.thanhdatpb.listingguard.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Runs the attempts of one call under a {@link RetryPolicy} (ADR-023), with the same request id on every attempt, and tells
 * {@link Notices} about every answer. It never blocks a thread while it waits: the next attempt is scheduled.
 */
final class Retrier {

    /** Where a delayed attempt runs; tests replace it to record the waits without sleeping. */
    interface Delayer {
        Executor after(Duration delay);

        Delayer DEFAULT = delay -> CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private final HttpTransport transport;
    private final RetryPolicy policy;
    private final Notices notices;
    private final ObjectMapper mapper;
    private final Delayer delayer;
    private final Clock clock;

    Retrier(HttpTransport transport, RetryPolicy policy, Notices notices, ObjectMapper mapper, Delayer delayer, Clock clock) {
        this.transport = transport;
        this.policy = policy;
        this.notices = notices;
        this.mapper = mapper;
        this.delayer = delayer;
        this.clock = clock;
    }

    CompletableFuture<RawResponse> execute(RawRequest request, String requestId) {
        return attempt(request, requestId, 1);
    }

    private CompletableFuture<RawResponse> attempt(RawRequest request, String requestId, int attempt) {
        return transport.exchange(request, requestId).thenCompose(response -> {
            notices.observe(request.operation(), response);
            int status = response.status();
            if (status != 429 && status != 503) {
                return CompletableFuture.completedFuture(response);
            }
            String code = Problems.code(mapper, response.body());
            Optional<Duration> delay = policy.delayBeforeNextAttempt(status, code, response.retryAfter(), attempt,
                    clock.instant());
            if (delay.isEmpty()) {
                return CompletableFuture.completedFuture(response);
            }
            notices.retry(request.operation(), attempt + 1, status, code, delay.get(), requestId);
            return CompletableFuture.runAsync(() -> { }, delayer.after(delay.get()))
                    .thenCompose(ignored -> attempt(request, requestId, attempt + 1));
        });
    }
}
