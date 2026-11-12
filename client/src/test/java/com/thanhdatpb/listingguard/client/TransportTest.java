package com.thanhdatpb.listingguard.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.thanhdatpb.listingguard.client.ApiStub.Reply;
import com.thanhdatpb.listingguard.client.ListingGuardTransportException.Kind;
import com.thanhdatpb.listingguard.client.model.Capabilities;
import com.thanhdatpb.listingguard.client.model.CheckRequest;
import com.thanhdatpb.listingguard.client.model.RulesetLimits;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What happens on the wire: timeouts, refused answers, redirects, size, encoding, redaction and conditional requests. */
class TransportTest {

    private static Kind kindOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ListingGuardTransportException.class);
        return ((ListingGuardTransportException) thrown).kind();
    }

    @Test
    void aSlowAnswerIsATimeoutWithinTheConfiguredTime() {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, "{}").after(Duration.ofSeconds(3)))) {
            ListingGuardClient client = stub.builder().timeout(Duration.ofMillis(300)).build();
            long started = System.nanoTime();

            Throwable thrown = catchThrowable(client::usage);

            assertThat(kindOf(thrown)).isEqualTo(Kind.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        }
    }

    @Test
    void aClosedPortIsUnreachableAndTheMessageHoldsNoKey() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        ListingGuardClient client = ListingGuardClient.builder().baseUrl("http://127.0.0.1:" + port).apiKey(ApiStub.KEY)
                .timeout(Duration.ofSeconds(2)).build();

        Throwable thrown = catchThrowable(client::usage);

        assertThat(kindOf(thrown)).isEqualTo(Kind.UNREACHABLE);
        assertThat(thrown.getMessage()).doesNotContain(ApiStub.KEY);
    }

    @Test
    void anAnswerLargerThanTheLimitIsRefused() {
        String big = "{\"x\":\"" + "a".repeat(ListingGuardClient.DEFAULT_MAX_RESPONSE_BYTES + 10) + "\"}";
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, big))) {
            assertThat(kindOf(catchThrowable(() -> stub.client().send(RawRequest.usage())))).isEqualTo(Kind.RESPONSE_TOO_LARGE);
        }
    }

    @Test
    void anAnswerJustUnderTheLimitIsReadAndTheLimitCanBeLowered() {
        String body = "{\"x\":\"" + "a".repeat(ListingGuardClient.DEFAULT_MAX_RESPONSE_BYTES - 100) + "\"}";
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, body))) {
            assertThat(stub.client().send(RawRequest.usage()).body()).hasSize(body.length());

            ListingGuardClient small = stub.builder().maxResponseBytes(4096).build();
            assertThat(kindOf(catchThrowable(() -> small.send(RawRequest.usage())))).isEqualTo(Kind.RESPONSE_TOO_LARGE);
        }
    }

    @Test
    void aRedirectIsNotFollowedSoTheKeyIsNeverSentElsewhere() {
        try (ApiStub stub = new ApiStub().replying(Reply.json(302, "").with("Location", "http://127.0.0.1:1/steal"))) {
            Throwable thrown = catchThrowable(() -> stub.client().usage());

            assertThat(kindOf(thrown)).isEqualTo(Kind.REDIRECT);
            assertThat(stub.requests()).hasSize(1);

            RawResponse raw = stub.client().send(RawRequest.usage());
            assertThat(raw.status()).as("a raw call returns the redirect as it is").isEqualTo(302);
            assertThat(stub.requests()).hasSize(2);
        }
    }

    @Test
    void aSuccessThatIsNotAJsonObjectIsAnInvalidResponse() {
        for (String body : new String[] {"not json", "[1,2,3]", "\"text\"", "", "null"}) {
            try (ApiStub stub = new ApiStub().replying(Reply.json(200, body))) {
                assertThat(kindOf(catchThrowable(() -> stub.client().usage()))).as(body).isEqualTo(Kind.INVALID_RESPONSE);
            }
        }
    }

    @Test
    void a304ToAPlainRequestIsAnInvalidResponse() {
        try (ApiStub stub = new ApiStub().replying(Reply.json(304, ""))) {
            assertThat(kindOf(catchThrowable(() -> stub.client().capabilities()))).isEqualTo(Kind.INVALID_RESPONSE);
        }
    }

    @Test
    void theMarketplaceAndTheProductTypeAreEncodedSoNothingCanChangeThePath() {
        try (ApiStub stub = new ApiStub()) {
            stub.client().send(RawRequest.rulesetLimits("ET SY/../x?y#z", "a b&c=d/é"));

            ApiStub.Recorded recorded = stub.only();
            assertThat(recorded.path()).isEqualTo("/api/v1/rulesets/ET%20SY%2F%2E%2E%2Fx%3Fy%23z/limits");
            assertThat(recorded.target()).endsWith("?productType=a%20b%26c%3Dd%2F%C3%A9");
        }
    }

    @Test
    void percentEncodeKeepsOnlyUnreservedCharacters() {
        assertThat(HttpTransport.percentEncode("Az09-_", false)).isEqualTo("Az09-_");
        assertThat(HttpTransport.percentEncode("a.b~c", false)).isEqualTo("a%2Eb%7Ec");
        assertThat(HttpTransport.percentEncode("a.b~c", true)).isEqualTo("a.b~c");
        assertThat(HttpTransport.percentEncode("Đ", false)).isEqualTo("%C4%90");
        assertThat(HttpTransport.percentEncode("", false)).isEmpty();
    }

    @Test
    void aBaseUrlPathPrefixIsKept() {
        try (ApiStub stub = new ApiStub()) {
            ListingGuardClient client = ListingGuardClient.builder().baseUrl(URI.create(stub.baseUri() + "/lg/"))
                    .apiKey(ApiStub.KEY).build();

            client.send(RawRequest.usage());

            assertThat(stub.only().path()).isEqualTo("/lg/api/v1/usage");
        }
    }

    @Test
    void anEchoedKeyOrAccessSecretIsReplacedEverywhereACallerCouldSeeIt() {
        String secret = "access-secret-XYZ-123";
        String body = "{\"type\":\"urn:listing-guard:problem:unauthorized\",\"status\":401,\"detail\":\"bad key "
                + ApiStub.KEY + " and " + secret + "\",\"code\":\"unauthorized\",\"echo\":\"" + ApiStub.KEY + "\"}";
        try (ApiStub stub = new ApiStub().replying(Reply.problem(401, body).with("X-Request-Id", "id-" + ApiStub.KEY)
                .with("X-Debug", secret))) {
            ListingGuardClient client = stub.builder().cloudflareAccess("access-id", secret).build();

            RawResponse raw = client.send(RawRequest.usage());
            assertThat(raw.body()).doesNotContain(ApiStub.KEY).doesNotContain(secret).contains("<redacted>");
            assertThat(raw.requestId()).doesNotContain(ApiStub.KEY);
            assertThat(raw.header("X-Debug")).isEqualTo("<redacted>");

            Throwable thrown = catchThrowable(client::usage);
            ListingGuardApiException e = (ListingGuardApiException) thrown;
            assertThat(e.getMessage()).doesNotContain(ApiStub.KEY).doesNotContain(secret);
            assertThat(e.detail()).doesNotContain(ApiStub.KEY).doesNotContain(secret);
            assertThat(e.properties().toString()).doesNotContain(ApiStub.KEY);
        }
    }

    @Test
    void capabilitiesSendsIfNoneMatchAndReadsA304AsUnchanged() {
        String etag = "\"0f3c9a\"";
        try (ApiStub stub = new ApiStub().replying(r -> etag.equals(r.header("If-None-Match"))
                ? Reply.json(304, "").with("ETag", etag)
                : Reply.json(200, ClientEndpointsTest.CAPABILITIES).with("ETag", etag))) {
            ListingGuardClient client = stub.client();

            Conditional<Capabilities> first = client.capabilities(null);
            Conditional<Capabilities> second = client.capabilities(first.etag());

            assertThat(first.notModified()).isFalse();
            assertThat(first.etag()).isEqualTo(etag);
            assertThat(first.value().trademarkCatalog()).isNotNull();
            assertThat(first.ifModified()).isPresent();
            assertThat(stub.requests().get(0).header("If-None-Match")).isNull();
            assertThat(second.notModified()).isTrue();
            assertThat(second.value()).isNull();
            assertThat(second.ifModified()).isEmpty();
            assertThat(second.etag()).isEqualTo(etag);
            assertThat(stub.requests().get(1).header("If-None-Match")).isEqualTo(etag);
        }
    }

    @Test
    void rulesetLimitsIsConditionalTooAndA304WithoutETagKeepsTheCallersOne() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(304, ""))) {
            Conditional<RulesetLimits> result = stub.client().rulesetLimitsAsync("ETSY", null, "\"abc\"").get();

            assertThat(result.notModified()).isTrue();
            assertThat(result.etag()).isEqualTo("\"abc\"");
            assertThat(stub.only().header("If-None-Match")).isEqualTo("\"abc\"");
            assertThat(stub.only().target()).isEqualTo("/api/v1/rulesets/ETSY/limits");
        }
    }

    @Test
    void aListingThatCannotBeWrittenAsJsonFailsBeforeAnyCall() {
        try (ApiStub stub = new ApiStub()) {
            Throwable thrown = catchThrowable(() -> stub.client().checks(CheckRequest.of("ETSY",
                    Map.of("x", new Object() {
                        @SuppressWarnings("unused")
                        public Object getSelf() {
                            return this;
                        }
                    }))));

            assertThat(kindOf(thrown)).isEqualTo(Kind.FAILED);
            assertThat(stub.requests()).isEmpty();
        }
    }

    @Test
    void anInterruptedCallerGetsInterruptedAndKeepsItsFlag() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, "{}").after(Duration.ofSeconds(3)))) {
            ListingGuardClient client = stub.client();
            Throwable[] seen = new Throwable[1];
            boolean[] flag = new boolean[1];
            Thread caller = new Thread(() -> {
                try {
                    client.usage();
                } catch (Throwable t) {
                    seen[0] = t;
                    flag[0] = Thread.currentThread().isInterrupted();
                }
            });
            caller.start();
            Thread.sleep(200);
            caller.interrupt();
            caller.join(5000);

            assertThat(kindOf(seen[0])).isEqualTo(Kind.INTERRUPTED);
            assertThat(flag[0]).isTrue();
        }
    }
}
