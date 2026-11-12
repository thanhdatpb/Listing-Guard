package com.thanhdatpb.listingguard.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.thanhdatpb.listingguard.client.ApiStub.Reply;
import com.thanhdatpb.listingguard.client.model.CheckRequest;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/** Each kind of problem answer becomes its typed exception with its members. */
class ClientErrorsTest {

    private static Throwable checkFails(ApiStub stub) {
        return catchThrowable(() -> stub.client().checks(CheckRequest.of("ETSY", Map.of("title", "x"))));
    }

    @Test
    void quotaExceededCarriesPlanQuotaUsedAndReset() {
        String extra = "\"plan\":\"SELLER\",\"quota\":1000,\"used\":1000,\"resetAt\":\"2026-12-01T00:00:00Z\"";
        try (ApiStub stub = new ApiStub().replying(Reply.problem(429, ApiStub.problem(429, "quota-exceeded", extra)))) {
            assertThat(checkFails(stub)).isInstanceOfSatisfying(QuotaExceededException.class, e -> {
                assertThat(e.status()).isEqualTo(429);
                assertThat(e.code()).isEqualTo("quota-exceeded");
                assertThat(e.type()).isEqualTo("urn:listing-guard:problem:quota-exceeded");
                assertThat(e.plan()).isEqualTo("SELLER");
                assertThat(e.quota()).isEqualTo(1000L);
                assertThat(e.used()).isEqualTo(1000L);
                assertThat(e.resetAt()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
                assertThat(e.requestId()).isEqualTo("api-req-1");
                assertThat(e.isProblem()).isTrue();
            });
        }
    }

    @Test
    void planNotAllowedCarriesPlanAndPack() {
        String extra = "\"plan\":\"FREE\",\"pack\":\"trademark-risk\"";
        try (ApiStub stub = new ApiStub().replying(Reply.problem(403, ApiStub.problem(403, "plan-not-allowed", extra)))) {
            assertThat(checkFails(stub)).isInstanceOfSatisfying(PlanNotAllowedException.class, e -> {
                assertThat(e.plan()).isEqualTo("FREE");
                assertThat(e.pack()).isEqualTo("trademark-risk");
                assertThat(e.status()).isEqualTo(403);
            });
        }
    }

    @Test
    void rateLimitedCarriesRetryAfter() {
        try (ApiStub stub = new ApiStub().replying(
                Reply.problem(429, ApiStub.problem(429, "rate-limited", "")).with("Retry-After", "7"))) {
            assertThat(checkFails(stub)).isInstanceOfSatisfying(RateLimitedException.class,
                    e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(7)));
        }
    }

    @Test
    void rateLimitedWithoutRetryAfterHasNoWait() {
        try (ApiStub stub = new ApiStub().replying(Reply.problem(429, ApiStub.problem(429, "rate-limited", "")))) {
            assertThat(checkFails(stub)).isInstanceOfSatisfying(RateLimitedException.class,
                    e -> assertThat(e.retryAfter()).isEmpty());
        }
    }

    @Test
    void everyNotConfiguredCodeIsNotConfigured() {
        for (String code : new String[] {"trademark-not-configured", "image-checks-not-configured",
                "embedding-not-configured"}) {
            try (ApiStub stub = new ApiStub().replying(Reply.problem(503, ApiStub.problem(503, code, "")))) {
                Throwable thrown = catchThrowable(() -> stub.client()
                        .trademarkChecks(TrademarkCheckRequest.of("ETSY", Map.of("title", "x"))));

                assertThat(thrown).as(code).isInstanceOfSatisfying(NotConfiguredException.class,
                        e -> assertThat(e.code()).isEqualTo(code));
            }
        }
    }

    @Test
    void invalidRequestCarriesTheFieldErrors() {
        String extra = "\"errors\":[{\"field\":\"listing\",\"message\":\"must not be null\"},{\"field\":\"marketplace\"}]";
        try (ApiStub stub = new ApiStub().replying(Reply.problem(400, ApiStub.problem(400, "invalid-request", extra)))) {
            assertThat(checkFails(stub)).isInstanceOfSatisfying(ValidationException.class, e -> {
                assertThat(e.errors()).containsExactly(new FieldError("listing", "must not be null"),
                        new FieldError("marketplace", null));
                assertThat(e.detail()).isEqualTo("A detail.");
            });
        }
    }

    @Test
    void unprocessableRequestIsAValidationError() {
        try (ApiStub stub = new ApiStub().replying(Reply.problem(422, ApiStub.problem(422, "unprocessable-request", "")))) {
            assertThat(checkFails(stub)).isInstanceOfSatisfying(ValidationException.class,
                    e -> assertThat(e.errors()).isEmpty());
        }
    }

    @Test
    void unknownProductTypeCarriesTheKnownList() {
        String extra = "\"known\":[\"apparel\",\"mug\",\"poster\"]";
        try (ApiStub stub = new ApiStub().replying(Reply.problem(400, ApiStub.problem(400, "unknown-product-type", extra)))) {
            Throwable thrown = catchThrowable(() -> stub.client()
                    .trademarkChecks(TrademarkCheckRequest.of("ETSY", Map.of()).withProductType("spaceship")));

            assertThat(thrown).isInstanceOfSatisfying(UnknownProductTypeException.class,
                    e -> assertThat(e.known()).containsExactly("apparel", "mug", "poster"));
        }
    }

    @Test
    void anyOtherProblemIsTheBaseExceptionWithItsMembers() {
        String extra = "\"known\":[\"latin_basic\",\"latin_extended\"]";
        record Case(int status, String code) {
        }
        for (Case c : new Case[] {new Case(400, "unknown-marketplace"), new Case(400, "unknown-print-profile"),
                new Case(401, "unauthorized"), new Case(404, "ruleset-not-found"), new Case(413, "payload-too-large"),
                new Case(500, "internal-error"), new Case(503, "corpus-model-mismatch")}) {
            try (ApiStub stub = new ApiStub().replying(Reply.problem(c.status(), ApiStub.problem(c.status(), c.code(), extra)))) {
                Throwable thrown = checkFails(stub);

                assertThat(thrown).as(c.code()).isExactlyInstanceOf(ListingGuardApiException.class);
                ListingGuardApiException e = (ListingGuardApiException) thrown;
                assertThat(e.status()).isEqualTo(c.status());
                assertThat(e.code()).isEqualTo(c.code());
                assertThat(e.title()).isEqualTo("A title");
                assertThat(e.detail()).isEqualTo("A detail.");
                assertThat(e.instance()).isEqualTo("/api/v1/checks");
                assertThat(e.problem().texts("known")).containsExactly("latin_basic", "latin_extended");
                assertThat(e.getMessage()).contains(c.code()).contains(String.valueOf(c.status())).contains("api-req-1");
            }
        }
    }

    @Test
    void theCodeIsReadFromTheTypeAndFromTheCodeMemberAndMembersUnderProperties() {
        String body = "{\"title\":\"Too Many Requests\",\"status\":429,\"properties\":{\"code\":\"quota-exceeded\","
                + "\"plan\":\"PRO\"}}";
        try (ApiStub stub = new ApiStub().replying(Reply.problem(429, body))) {
            assertThat(checkFails(stub)).isInstanceOfSatisfying(QuotaExceededException.class,
                    e -> assertThat(e.plan()).isEqualTo("PRO"));
        }
    }

    @Test
    void anErrorThatIsNotAProblemComesFromSomethingInFrontOfTheApi() {
        try (ApiStub stub = new ApiStub().replying(
                Reply.json(403, "<html><body>Forbidden by Access nginx/1.25</body></html>"))) {
            Throwable thrown = checkFails(stub);

            assertThat(thrown).isExactlyInstanceOf(ListingGuardApiException.class);
            ListingGuardApiException e = (ListingGuardApiException) thrown;
            assertThat(e.isProblem()).isFalse();
            assertThat(e.code()).isNull();
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.detail()).isNull();
            assertThat(e.properties()).isEmpty();
            assertThat(e.getMessage()).doesNotContain("<html>").doesNotContain("nginx");
        }
    }

    @Test
    void aMalformedCodeIsNotTakenAndTheRequestIdFallsBackToTheHeader() {
        String body = "{\"code\":\"Bad Code <script>\",\"detail\":\"x\",\"requestId\":\"bad id!\"}";
        try (ApiStub stub = new ApiStub().replying(Reply.problem(500, body))) {
            ListingGuardApiException e = (ListingGuardApiException) checkFails(stub);

            assertThat(e.code()).isNull();
            assertThat(e.isProblem()).isFalse();
            assertThat(e.requestId()).as("the id the SDK sent, echoed in the header").isNotNull().doesNotContain(" ");
        }
    }

    @Test
    void theAsyncFormCompletesWithTheBareExceptionAndJoinWrapsIt() {
        try (ApiStub stub = new ApiStub().replying(Reply.problem(401, ApiStub.problem(401, "unauthorized", "")))) {
            var future = stub.client().checksAsync(CheckRequest.of("ETSY", Map.of()));

            Throwable seen = future.handle((v, e) -> e).join();
            assertThatThrownBy(future::join).isInstanceOf(CompletionException.class)
                    .cause().isExactlyInstanceOf(ListingGuardApiException.class);
            assertThatThrownBy(future::get).isInstanceOf(ExecutionException.class)
                    .cause().isExactlyInstanceOf(ListingGuardApiException.class);
            assertThat(seen).isExactlyInstanceOf(ListingGuardApiException.class);
        }
    }

    @Test
    void aLongDetailIsCutInTheMessageButKeptInDetail() {
        String body = "{\"type\":\"urn:listing-guard:problem:internal-error\",\"status\":500,\"detail\":\""
                + "x".repeat(2000) + "\"}";
        try (ApiStub stub = new ApiStub().replying(Reply.problem(500, body))) {
            ListingGuardApiException e = (ListingGuardApiException) checkFails(stub);

            assertThat(e.detail()).hasSize(2000);
            assertThat(e.getMessage().length()).isLessThan(700);
        }
    }
}
