package com.thanhdatpb.listingguard.api.trademark.tsdr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.trademark.tsdr.FakeTsdrServer.Behaviour;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.CallResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The TSDR client against a stand-in on the loopback address (ADR-009, "TSDR status check"): a live answer, "not found", a limit
 * that passes and one that lasts, a server that stalls or fails, a refused key, and what it never does (follow a redirect, send a
 * key it was not given, print one). Nothing here talks to the real TSDR and nothing sleeps for real.
 */
class TsdrClientTest {

    private static final String KEY = "tsdr-test-key-0123456789";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T11:00:00Z"), ZoneOffset.UTC);

    private FakeTsdrServer server;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = new FakeTsdrServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private TsdrClient client(Map<String, String> extra) {
        Map<String, String> values = new java.util.HashMap<>(Map.of("download.base-url", server.baseUrl(),
                "download.api-key", KEY));
        values.putAll(extra);
        return new TsdrClient(FakeTsdrServer.properties(values).download(), CLOCK, sleeps::add);
    }

    @Test
    void aLiveAnswerCarriesTheKeyInItsHeaderAndTheSerialNumberInThePath() {
        server.serve("90000001", Behaviour.OK, FakeTsdrServer.xml("700", "REGISTERED", "2020-01-02"));

        var reply = client(Map.of()).fetch("90000001");

        assertThat(reply).isInstanceOf(TsdrClient.Body.class);
        assertThat(new String(((TsdrClient.Body) reply).bytes())).contains("<MarkCurrentStatusCode>700<");
        assertThat(server.requests()).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/ts/cd/casestatus/sn90000001/info.xml");
            assertThat(request.key()).isEqualTo(KEY);
        });
    }

    @Test
    void withoutAKeyNoKeyHeaderIsSent() {
        server.serve("90000001", Behaviour.OK);
        client(Map.of("download.api-key", "")).fetch("90000001");
        assertThat(server.requests()).singleElement().satisfies(request -> assertThat(request.key()).isNull());
    }

    @Test
    void anUnknownSerialNumberIsNotFoundAndNotTriedAgain() {
        server.serve("90000002", Behaviour.NOT_FOUND);

        var reply = (TsdrClient.Failure) client(Map.of()).fetch("90000002");

        assertThat(reply.result()).isEqualTo(CallResult.NOT_FOUND);
        assertThat(reply.stopsRun()).isFalse();
        assertThat(server.calls("90000002")).isEqualTo(1);
    }

    @Test
    void aLimitThatPassesIsWaitedOutAndTheMarkIsAnswered() {
        server.serve("90000003", Behaviour.RATE_LIMITED_ONCE);

        var reply = client(Map.of()).fetch("90000003");

        assertThat(reply).isInstanceOf(TsdrClient.Body.class);
        assertThat(((TsdrClient.Body) reply).attempts()).isEqualTo(2);
        assertThat(server.calls("90000003")).isEqualTo(2);
    }

    @Test
    void aLimitThatLastsEndsTheRunAfterTheConfiguredAttemptsAndNoMore() {
        server.serve("90000004", Behaviour.RATE_LIMITED_ALWAYS);

        var reply = (TsdrClient.Failure) client(Map.of("download.max-attempts", "3")).fetch("90000004");

        assertThat(reply.result()).isEqualTo(CallResult.RATE_LIMITED);
        assertThat(reply.stopsRun()).isTrue();
        assertThat(server.calls("90000004")).isEqualTo(3);
    }

    @Test
    void aRetryAfterLongerThanTheBackoffCeilingEndsTheRunAtOnce() {
        server.serve("90000005", Behaviour.RATE_LIMITED_LONG);

        var reply = (TsdrClient.Failure) client(Map.of("download.max-backoff", "30s")).fetch("90000005");

        assertThat(reply.result()).isEqualTo(CallResult.RATE_LIMITED);
        assertThat(reply.stopsRun()).isTrue();
        assertThat(reply.message()).contains("longer than max-backoff");
        assertThat(server.calls("90000005")).isEqualTo(1);
    }

    @Test
    void aFailingServerIsTriedWithGrowingWaitsThenGivenUpOnWithoutStoppingTheRun() {
        server.serve("90000006", Behaviour.SERVER_ERROR);

        var reply = (TsdrClient.Failure) client(Map.of("download.max-attempts", "4", "download.initial-backoff", "1s",
                "download.max-backoff", "3s")).fetch("90000006");

        assertThat(reply.result()).isEqualTo(CallResult.ERROR);
        assertThat(reply.httpStatus()).isEqualTo(500);
        assertThat(reply.stopsRun()).isFalse();
        assertThat(server.calls("90000006")).isEqualTo(4);
        // doubled from the initial backoff and held at the ceiling
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    @Test
    void aServerThatStallsIsATimeout() {
        server.serve("90000007", Behaviour.STALL);

        var reply = (TsdrClient.Failure) client(Map.of("download.read-timeout", "200ms", "download.max-attempts", "2"))
                .fetch("90000007");

        assertThat(reply.result()).isEqualTo(CallResult.TIMEOUT);
        assertThat(reply.stopsRun()).isFalse();
        assertThat(server.calls("90000007")).isEqualTo(2);
    }

    @Test
    void aRefusedKeyEndsTheRunAndIsNotTriedAgain() {
        server.serve("90000008", Behaviour.REFUSED);
        var forbidden = (TsdrClient.Failure) client(Map.of()).fetch("90000008");
        assertThat(forbidden.result()).isEqualTo(CallResult.REFUSED);
        assertThat(forbidden.stopsRun()).isTrue();
        assertThat(server.calls("90000008")).isEqualTo(1);

        // a wrong key: the server wants another one and answers 401
        server.serve("90000009", Behaviour.OK).requireKey("another-key-0123456789");
        var wrong = (TsdrClient.Failure) client(Map.of()).fetch("90000009");
        assertThat(wrong.result()).isEqualTo(CallResult.REFUSED);
        assertThat(wrong.httpStatus()).isEqualTo(401);
        assertThat(wrong.stopsRun()).isTrue();
        assertThat(wrong.message()).doesNotContain(KEY);
    }

    @Test
    void aRedirectIsNotFollowedAndTheKeyGoesNowhereElse() {
        server.serve("90000010", Behaviour.REDIRECT);

        var reply = (TsdrClient.Failure) client(Map.of()).fetch("90000010");

        assertThat(reply.result()).isEqualTo(CallResult.ERROR);
        assertThat(reply.httpStatus()).isEqualTo(302);
        assertThat(server.calls("90000010")).isEqualTo(1);
    }

    @Test
    void anAnswerLargerThanTheLimitIsRefused() {
        server.serve("90000011", Behaviour.TOO_BIG);

        var reply = (TsdrClient.Failure) client(Map.of("download.max-response-size", "1KB")).fetch("90000011");

        assertThat(reply.result()).isEqualTo(CallResult.ERROR);
        assertThat(reply.message()).contains("max-response-size");
    }

    @Test
    void requestsAreSpacedByTheMinimumInterval() {
        server.serve("90000012", Behaviour.OK).serve("90000013", Behaviour.OK);
        var client = client(Map.of("download.min-interval", "2s"));

        client.fetch("90000012");
        client.fetch("90000013");

        // the clock is fixed, so no time has passed since the first request: the second waits the whole interval
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void aServerThatIsNotThereIsAFailureThatNeverPrintsTheKey() {
        String base = server.baseUrl();
        server.close();

        var reply = (TsdrClient.Failure) new TsdrClient(FakeTsdrServer.properties(Map.of("download.base-url", base,
                "download.api-key", KEY, "download.max-attempts", "2")).download(), CLOCK, sleeps::add).fetch("90000014");

        assertThat(reply.result()).isEqualTo(CallResult.ERROR);
        assertThat(reply.message()).doesNotContain(KEY);
    }

    @Test
    void onlyEightDigitsGoIntoTheAddress() {
        var client = client(Map.of());
        for (String bad : new String[] {null, "", "1234567", "123456789", "9000000a", "../etc/pw", "9000 001"}) {
            assertThatThrownBy(() -> client.fetch(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void theSettingsRefuseWhatWouldSendTheKeyAwayAndNeverPrintIt() {
        assertThatThrownBy(() -> FakeTsdrServer.properties(Map.of("download.base-url", "http://tsdrapi.uspto.gov",
                "download.allowed-hosts", "tsdrapi.uspto.gov"))).hasStackTraceContaining("https");
        assertThatThrownBy(() -> FakeTsdrServer.properties(Map.of("download.base-url", "https://tsdrapi.uspto.gov",
                "download.allowed-hosts", "somewhere.example"))).hasStackTraceContaining("allowed-hosts");
        assertThatThrownBy(() -> FakeTsdrServer.properties(Map.of("download.status-path", "/x?sn={serial}")))
                .hasStackTraceContaining("status-path");
        assertThat(FakeTsdrServer.properties(Map.of("download.api-key", KEY, "download.base-url", "https://tsdrapi.uspto.gov",
                "download.allowed-hosts", "tsdrapi.uspto.gov")).download().toString()).doesNotContain(KEY).contains("(set)");
    }
}
