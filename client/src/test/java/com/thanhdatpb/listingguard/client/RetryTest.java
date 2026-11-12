package com.thanhdatpb.listingguard.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.thanhdatpb.listingguard.client.ApiStub.Reply;
import com.thanhdatpb.listingguard.client.model.CheckRequest;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The retry policy against the stub: what is retried, how long the SDK waits (recorded instead of slept), and that every
 * attempt of one call carries the same request id.
 */
class RetryTest {

    private static final String OK = "{\"plan\":\"FREE\",\"packs\":[],\"enforced\":false,\"used\":0}";
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    /** Records each wait and runs the next attempt at once. */
    private final List<Duration> waits = new CopyOnWriteArrayList<>();
    private final Retrier.Delayer recording = delay -> {
        waits.add(delay);
        return Runnable::run;
    };

    private ListingGuardClient client(ApiStub stub, RetryPolicy policy) {
        return stub.builder().retry(policy).delayer(recording).clock(Clock.fixed(NOW, ZoneOffset.UTC)).build();
    }

    private static Reply rateLimited(String retryAfter) {
        Reply reply = Reply.problem(429, ApiStub.problem(429, "rate-limited", ""));
        return retryAfter == null ? reply : reply.with("Retry-After", retryAfter);
    }

    @Test
    void aRateLimitIsRetriedAfterTheSecondsTheApiGaveAndTheCallSucceeds() {
        try (ApiStub stub = new ApiStub().then(rateLimited("7")).replying(Reply.json(200, OK))) {
            var usage = client(stub, RetryPolicy.defaults()).usage();

            assertThat(usage.plan()).isEqualTo("FREE");
            assertThat(stub.requests()).hasSize(2);
            assertThat(waits).containsExactly(Duration.ofSeconds(7));
        }
    }

    @Test
    void anHttpDateRetryAfterIsWaitedForUntilThatTime() {
        String at = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(NOW.plusSeconds(12), ZoneOffset.UTC));
        try (ApiStub stub = new ApiStub().then(rateLimited(at)).replying(Reply.json(200, OK))) {
            client(stub, RetryPolicy.defaults()).usage();

            assertThat(waits).containsExactly(Duration.ofSeconds(12));
        }
    }

    @Test
    void withoutRetryAfterTheWaitIsABackoffThatGrowsAndStaysWithinItsBounds() {
        RetryPolicy policy = RetryPolicy.builder().maxAttempts(4).initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(250)).jitter(false).build();
        try (ApiStub stub = new ApiStub().then(rateLimited(null), rateLimited(null), rateLimited(null))
                .replying(Reply.json(200, OK))) {
            client(stub, policy).usage();

            assertThat(waits).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(250));
        }
    }

    @Test
    void jitterKeepsEachBackoffBetweenHalfAndAllOfIt() {
        RetryPolicy policy = RetryPolicy.builder().maxAttempts(2).initialBackoff(Duration.ofMillis(400))
                .maxBackoff(Duration.ofMillis(400)).build();
        for (int i = 0; i < 20; i++) {
            waits.clear();
            try (ApiStub stub = new ApiStub().then(rateLimited(null)).replying(Reply.json(200, OK))) {
                client(stub, policy).usage();
            }
            assertThat(waits).singleElement().satisfies(d -> assertThat(d.toMillis()).isBetween(200L, 400L));
        }
    }

    @Test
    void aServiceUnavailableIsRetriedAndANonProblem503Too() {
        try (ApiStub stub = new ApiStub()
                .then(Reply.problem(503, ApiStub.problem(503, "service-unavailable", "")),
                        Reply.json(503, "<html>upstream down</html>"))
                .replying(Reply.json(200, OK))) {
            client(stub, RetryPolicy.defaults()).usage();

            assertThat(stub.requests()).hasSize(3);
            assertThat(waits).hasSize(2);
        }
    }

    @Test
    void aNotConfiguredAnswerIsNeverRetried() {
        try (ApiStub stub = new ApiStub().replying(Reply.problem(503, ApiStub.problem(503, "trademark-not-configured", "")))) {
            Throwable thrown = catchThrowable(() -> client(stub, RetryPolicy.defaults())
                    .trademarkChecks(TrademarkCheckRequest.of("ETSY", Map.of())));

            assertThat(thrown).isInstanceOf(NotConfiguredException.class);
            assertThat(stub.requests()).hasSize(1);
            assertThat(waits).isEmpty();
        }
    }

    @Test
    void aQuotaRefusalIsNeverRetriedEvenWithARetryAfterFromAProxy() {
        try (ApiStub stub = new ApiStub().replying(
                Reply.problem(429, ApiStub.problem(429, "quota-exceeded", "")).with("Retry-After", "1"))) {
            Throwable thrown = catchThrowable(() -> client(stub, RetryPolicy.defaults())
                    .checks(CheckRequest.of("ETSY", Map.of())));

            assertThat(thrown).isInstanceOf(QuotaExceededException.class);
            assertThat(stub.requests()).hasSize(1);
            assertThat(waits).isEmpty();
        }
    }

    @Test
    void otherClientAndServerErrorsAreNotRetried() {
        int[][] cases = {{400}, {401}, {403}, {404}, {413}, {422}, {500}, {502}, {504}};
        for (int[] c : cases) {
            int status = c[0];
            waits.clear();
            try (ApiStub stub = new ApiStub().replying(Reply.problem(status, ApiStub.problem(status, "some-code", "")))) {
                Throwable thrown = catchThrowable(() -> client(stub, RetryPolicy.defaults()).usage());

                assertThat(thrown).as("HTTP %d", status).isInstanceOf(ListingGuardApiException.class);
                assertThat(stub.requests()).as("HTTP %d", status).hasSize(1);
                assertThat(waits).isEmpty();
            }
        }
    }

    @Test
    void aTimeoutIsNotRetried() {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, OK).after(Duration.ofSeconds(2)))) {
            ListingGuardClient client = stub.builder().timeout(Duration.ofMillis(200)).retry(RetryPolicy.defaults())
                    .delayer(recording).build();

            Throwable thrown = catchThrowable(client::usage);

            assertThat(thrown).isInstanceOfSatisfying(ListingGuardTransportException.class,
                    e -> assertThat(e.kind()).isEqualTo(ListingGuardTransportException.Kind.TIMEOUT));
            assertThat(stub.requests()).hasSize(1);
        }
    }

    @Test
    void aRetryAfterLongerThanTheLimitIsNotWaitedFor() {
        try (ApiStub stub = new ApiStub().replying(rateLimited("120"))) {
            Throwable thrown = catchThrowable(() -> client(stub, RetryPolicy.defaults()).usage());

            assertThat(thrown).isInstanceOfSatisfying(RateLimitedException.class,
                    e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(120)));
            assertThat(stub.requests()).hasSize(1);
            assertThat(waits).isEmpty();
        }
    }

    @Test
    void theAttemptsAreBoundedAndTheLastAnswerIsThrown() {
        try (ApiStub stub = new ApiStub().replying(rateLimited("1"))) {
            Throwable thrown = catchThrowable(() -> client(stub, RetryPolicy.defaults()).usage());

            assertThat(thrown).isInstanceOf(RateLimitedException.class);
            assertThat(stub.requests()).hasSize(RetryPolicy.defaults().maxAttempts());
            assertThat(waits).hasSize(RetryPolicy.defaults().maxAttempts() - 1);
        }
    }

    @Test
    void noneMakesExactlyOneAttempt() {
        try (ApiStub stub = new ApiStub().replying(rateLimited("1"))) {
            catchThrowable(() -> client(stub, RetryPolicy.none()).usage());

            assertThat(stub.requests()).hasSize(1);
        }
    }

    @Test
    void everyAttemptOfOneCallCarriesTheSameRequestIdAndTheListenerHearsOfEachRetry() {
        List<RetryNotice> notices = new CopyOnWriteArrayList<>();
        try (ApiStub stub = new ApiStub().then(rateLimited("2"), Reply.problem(503, ApiStub.problem(503,
                "service-unavailable", ""))).replying(Reply.json(200, OK))) {
            ListingGuardClient client = stub.builder().retry(RetryPolicy.defaults()).delayer(recording)
                    .listener(new ListingGuardListener() {
                        @Override
                        public void onRetry(RetryNotice notice) {
                            notices.add(notice);
                        }
                    }).build();

            client.usage();

            assertThat(stub.requests()).extracting(r -> r.header("X-Request-Id")).hasSize(3).containsOnly(
                    stub.requests().get(0).header("X-Request-Id"));
            assertThat(notices).extracting(RetryNotice::nextAttempt).containsExactly(2, 3);
            assertThat(notices).extracting(RetryNotice::status).containsExactly(429, 503);
            assertThat(notices).extracting(RetryNotice::code).containsExactly("rate-limited", "service-unavailable");
            assertThat(notices.get(0).delay()).isEqualTo(Duration.ofSeconds(2));
            assertThat(notices.get(0).operation()).isEqualTo(Operation.USAGE);
        }
    }

    @Test
    void aRawCallIsRetriedTooAndReturnsTheFinalAnswer() {
        try (ApiStub stub = new ApiStub().then(rateLimited("1")).replying(Reply.json(200, OK))) {
            RawResponse response = client(stub, RetryPolicy.defaults()).send(RawRequest.usage());

            assertThat(response.status()).isEqualTo(200);
            assertThat(stub.requests()).hasSize(2);
        }
    }

    @Test
    void aRetryReallyWaitsWithTheDefaultDelayer() {
        try (ApiStub stub = new ApiStub().then(rateLimited("1")).replying(Reply.json(200, OK))) {
            ListingGuardClient client = stub.builder().retry(RetryPolicy.defaults()).build();
            long started = System.nanoTime();

            client.usage();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(950));
            assertThat(stub.requests()).hasSize(2);
        }
    }
}
