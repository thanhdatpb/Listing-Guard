package com.thanhdatpb.listingguard.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thanhdatpb.listingguard.rag.llm.LlmException;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import com.thanhdatpb.listingguard.rag.llm.LlmResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The client on stub providers: no network, no database. */
class LlmClientTest {

    private static final String SECRET_PROMPT = "Dodger blue sweatshirt for my buyer Nguyen Van A";

    /** A provider that does what the test queued, then answers {@code {"ok":true}}. */
    private static final class Stub implements LlmProvider {
        final String model;
        final ArrayDeque<RuntimeException> failures = new ArrayDeque<>();
        int calls;

        Stub(String model) {
            this.model = model;
        }

        @Override
        public String modelId() {
            return model;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            calls++;
            if (!failures.isEmpty()) {
                throw failures.poll();
            }
            return new LlmResponse("{\"ok\":true}", 100, 20, "stop");
        }
    }

    private static final class MemoryStore implements LlmUsageStore {
        final Map<String, Long> requests = new HashMap<>();
        final Map<String, Long> tokens = new HashMap<>();

        @Override
        public boolean reserve(String providerId, LocalDate day, int limit) {
            long used = requests.getOrDefault(providerId + day, 0L);
            if (used >= limit) {
                return false;
            }
            requests.put(providerId + day, used + 1);
            return true;
        }

        @Override
        public void addTokens(String providerId, LocalDate day, long inputTokens, long outputTokens) {
            tokens.merge(providerId, inputTokens + outputTokens, Long::sum);
        }

        @Override
        public Map<String, Long> requestsOn(LocalDate day) {
            Map<String, Long> result = new HashMap<>();
            requests.forEach((key, value) -> {
                if (key.endsWith(day.toString())) {
                    result.put(key.substring(0, key.length() - day.toString().length()), value);
                }
            });
            return result;
        }
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-16T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final MemoryStore store = new MemoryStore();
    private final ListAppender<ILoggingEvent> auditLines = new ListAppender<>();
    private final Logger auditLogger = (Logger) LoggerFactory.getLogger(LlmClient.AUDIT_LOGGER);

    @BeforeEach
    void captureAudit() {
        auditLines.start();
        auditLogger.addAppender(auditLines);
    }

    @AfterEach
    void releaseAudit() {
        auditLogger.detachAppender(auditLines);
    }

    private LlmClient.Endpoint endpoint(String id, Stub stub, int perMinute, int perDay, boolean listingText, boolean personal) {
        return new LlmClient.Endpoint(id, stub, perMinute, perDay, listingText, personal);
    }

    private LlmClient client(LlmClient.Endpoint... endpoints) {
        return new LlmClient(List.of(endpoints), store, new LlmCache(100, Duration.ofHours(1), clock), clock);
    }

    private static LlmClient.Call call(String user, LlmDataClass... data) {
        Set<LlmDataClass> kinds = data.length == 0 ? EnumSet.of(LlmDataClass.LISTING_TEXT) : EnumSet.of(data[0], data);
        return new LlmClient.Call("test", new LlmRequest("system", user, "{}", 100, Duration.ofSeconds(5)), kinds);
    }

    @Test
    void disabledClientSendsNothing() {
        LlmClient disabled = LlmClient.disabled(clock);

        assertThat(disabled.enabled()).isFalse();
        assertThatThrownBy(() -> disabled.complete(call("x"))).isInstanceOfSatisfying(LlmUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(LlmUnavailableException.Reason.DISABLED));
        assertThat(disabled.usage().enabled()).isFalse();
    }

    @Test
    void failsOverOnTimeoutRateLimitAndServerError() {
        for (LlmException.Kind kind : List.of(LlmException.Kind.TIMEOUT, LlmException.Kind.RATE_LIMIT,
                LlmException.Kind.BAD_STATUS, LlmException.Kind.TRUNCATED)) {
            Stub first = new Stub("first:m");
            Stub second = new Stub("second:m");
            first.failures.add(new LlmException(kind, "boom"));
            LlmClient client = client(endpoint("first", first, 10, 10, true, false), endpoint("second", second, 10, 10, true, false));

            LlmClient.Result result = client.complete(call("prompt " + kind));

            assertThat(result.providerId()).as(kind.name()).isEqualTo("second");
            assertThat(first.calls).isEqualTo(1);
            assertThat(second.calls).isEqualTo(1);
        }
    }

    @Test
    void allProvidersFailingIsReportedAsFailedNotAsQuota() {
        Stub first = new Stub("first:m");
        Stub second = new Stub("second:m");
        first.failures.add(new LlmException(LlmException.Kind.TIMEOUT, "t"));
        second.failures.add(new IllegalStateException("unexpected"));
        LlmClient client = client(endpoint("first", first, 10, 10, true, false), endpoint("second", second, 10, 10, true, false));

        assertThatThrownBy(() -> client.complete(call("x"))).isInstanceOfSatisfying(LlmUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(LlmUnavailableException.Reason.PROVIDERS_FAILED));
    }

    @Test
    void aRateLimitedProviderIsLeftAloneForAWhile() {
        Stub first = new Stub("first:m");
        Stub second = new Stub("second:m");
        first.failures.add(new LlmException(LlmException.Kind.RATE_LIMIT, "429"));
        LlmClient client = client(endpoint("first", first, 10, 10, true, false), endpoint("second", second, 10, 10, true, false));

        client.complete(call("one"));
        client.complete(call("two"));
        assertThat(first.calls).as("cooling down: not asked again").isEqualTo(1);

        clock.now = clock.now.plusSeconds(61);
        client.complete(call("three"));
        assertThat(first.calls).isEqualTo(2);
    }

    @Test
    void theDailyLimitIsReservedBeforeTheCallAndSkipsTheStepWhenUsedUp() {
        Stub only = new Stub("only:m");
        LlmClient client = client(endpoint("only", only, 100, 2, true, false));

        client.complete(call("a"));
        client.complete(call("b"));
        assertThatThrownBy(() -> client.complete(call("c"))).isInstanceOfSatisfying(LlmUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(LlmUnavailableException.Reason.QUOTA_EXHAUSTED));

        assertThat(only.calls).as("no request is sent once the day is used up").isEqualTo(2);
        LlmClient.Usage usage = client.usage();
        assertThat(usage.used()).isEqualTo(2);
        assertThat(usage.limit()).isEqualTo(2);
        assertThat(store.tokens.get("only")).isEqualTo(240L);

        clock.now = clock.now.plus(Duration.ofDays(1));
        assertThat(client.complete(call("d")).text()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void thePerMinuteLimitMovesOnToTheNextProviderThenFreesUp() {
        Stub first = new Stub("first:m");
        Stub second = new Stub("second:m");
        LlmClient client = client(endpoint("first", first, 1, 100, true, false), endpoint("second", second, 100, 100, true, false));

        assertThat(client.complete(call("a")).providerId()).isEqualTo("first");
        assertThat(client.complete(call("b")).providerId()).isEqualTo("second");
        clock.now = clock.now.plusSeconds(61);
        assertThat(client.complete(call("c")).providerId()).isEqualTo("first");
    }

    @Test
    void aRepeatedPromptIsAnsweredFromTheCacheWithoutARequestOrQuota() {
        Stub only = new Stub("only:m");
        LlmClient client = client(endpoint("only", only, 10, 10, true, false));

        LlmClient.Result first = client.complete(call("same prompt"));
        LlmClient.Result second = client.complete(call("same prompt"));
        LlmClient.Result other = client.complete(call("another prompt"));

        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.text()).isEqualTo(first.text());
        assertThat(other.cached()).isFalse();
        assertThat(only.calls).isEqualTo(2);
        assertThat(client.usage().used()).isEqualTo(2);
    }

    @Test
    void theCacheIsPerModel() {
        Stub first = new Stub("first:m1");
        LlmClient before = client(endpoint("first", first, 10, 10, true, false));
        before.complete(call("p"));

        Stub changed = new Stub("first:m2");
        LlmCache shared = new LlmCache(100, Duration.ofHours(1), clock);
        assertThat(LlmCache.key("first:m1", call("p").request())).isNotEqualTo(LlmCache.key("first:m2", call("p").request()));
        assertThat(shared.get(LlmCache.key(changed.modelId(), call("p").request()))).isEmpty();
    }

    @Test
    void personalizationNeverGoesToAProviderThatIsNotAllowedToSeeIt() {
        Stub listingOnly = new Stub("listing:m");
        Stub personal = new Stub("personal:m");
        LlmClient client = client(endpoint("listing", listingOnly, 10, 10, true, false),
                endpoint("personal", personal, 10, 10, true, true));

        LlmClient.Result result = client.complete(call(SECRET_PROMPT, LlmDataClass.LISTING_TEXT, LlmDataClass.PERSONALIZATION));

        assertThat(result.providerId()).isEqualTo("personal");
        assertThat(listingOnly.calls).isZero();

        LlmClient onlyListing = client(endpoint("listing", listingOnly, 10, 10, true, false));
        assertThatThrownBy(() -> onlyListing.complete(call(SECRET_PROMPT + " 2", LlmDataClass.PERSONALIZATION)))
                .isInstanceOfSatisfying(LlmUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(LlmUnavailableException.Reason.NO_ELIGIBLE_PROVIDER));
        assertThat(listingOnly.calls).as("nothing reached the provider").isZero();
    }

    @Test
    void aProviderWithNoFlagReceivesNothing() {
        Stub stub = new Stub("closed:m");
        LlmClient client = client(endpoint("closed", stub, 10, 10, false, false));

        assertThat(client.canServe(EnumSet.of(LlmDataClass.LISTING_TEXT))).isFalse();
        assertThatThrownBy(() -> client.complete(call("x"))).isInstanceOf(LlmUnavailableException.class);
        assertThat(stub.calls).isZero();
        assertThat(client.modelIds(EnumSet.of(LlmDataClass.LISTING_TEXT))).isEmpty();
    }

    @Test
    void theAuditLinesCarryNoPromptAndNoAnswer() {
        Stub only = new Stub("only:m");
        LlmClient client = client(endpoint("only", only, 10, 10, true, false));

        client.complete(call(SECRET_PROMPT));
        client.complete(call(SECRET_PROMPT));

        assertThat(auditLines.list).hasSize(2);
        List<String> lines = auditLines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines.get(0)).contains("provider=only", "model=only:m", "outcome=OK", "inputTokens=100", "outputTokens=20");
        assertThat(lines.get(1)).contains("outcome=CACHE_HIT");
        assertThat(String.join("\n", lines)).doesNotContain("Dodger", "Nguyen", "true");
    }
}
