package com.thanhdatpb.listingguard.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.mcp.ApiStub.Reply;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ListingGuardApiClientTest {

    @Test
    void aPostSendsTheKeyTheRequestIdAndTheBodyAsGiven() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, "{\"checkId\":\"c-1\"}"))) {
            ListingGuardApiClient client = new ListingGuardApiClient(stub.config());
            String body = "{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"  Đạt  \"}}";

            ApiResponse response = client.call(ApiRequest.post(Endpoint.CHECKS, body), "req-123");

            ApiStub.Recorded recorded = stub.only();
            assertThat(recorded.method()).isEqualTo("POST");
            assertThat(recorded.path()).isEqualTo("/api/v1/checks");
            assertThat(recorded.header("X-API-Key")).isEqualTo(ApiStub.KEY);
            assertThat(recorded.header("X-Request-Id")).isEqualTo("req-123");
            assertThat(recorded.header("Accept")).isEqualTo("application/json");
            assertThat(recorded.header("Content-Type")).isEqualTo("application/json");
            assertThat(recorded.body()).isEqualTo(body);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{\"checkId\":\"c-1\"}");
            assertThat(response.requestIdHeader()).isEqualTo("req-123");
        }
    }

    @Test
    void aGetHasNoBodyAndNoContentType() throws Exception {
        try (ApiStub stub = new ApiStub()) {
            new ListingGuardApiClient(stub.config()).call(ApiRequest.usage(), "r-1");

            ApiStub.Recorded recorded = stub.only();
            assertThat(recorded.method()).isEqualTo("GET");
            assertThat(recorded.path()).isEqualTo("/api/v1/usage");
            assertThat(recorded.body()).isEmpty();
            assertThat(recorded.header("Content-Type")).isNull();
            assertThat(recorded.header("X-API-Key")).isEqualTo(ApiStub.KEY);
        }
    }

    @Test
    void theMarketplaceAndTheProductTypeAreEncodedSoNothingCanChangeThePath() throws Exception {
        try (ApiStub stub = new ApiStub()) {
            ListingGuardApiClient client = new ListingGuardApiClient(stub.config());

            client.call(ApiRequest.limits("ET SY/../x?y#z", "a b&c=d/é"), "r-1");

            ApiStub.Recorded recorded = stub.only();
            assertThat(recorded.path()).isEqualTo("/api/v1/rulesets/ET%20SY%2F%2E%2E%2Fx%3Fy%23z/limits");
            assertThat(recorded.target()).endsWith("?productType=a%20b%26c%3Dd%2F%C3%A9");
        }
    }

    @Test
    void aPlainMarketplaceIsNotAltered() throws Exception {
        try (ApiStub stub = new ApiStub()) {
            new ListingGuardApiClient(stub.config()).call(ApiRequest.limits("ETSY", null), "r-1");

            assertThat(stub.only().target()).isEqualTo("/api/v1/rulesets/ETSY/limits");
        }
    }

    @Test
    void aBaseUrlPathPrefixIsKept() throws Exception {
        try (ApiStub stub = new ApiStub()) {
            McpServerConfig config = new McpServerConfig(ApiStub.KEY, URI.create(stub.baseUri() + "/lg"),
                    Duration.ofSeconds(5), TransportKind.STDIO, InetAddress.getLoopbackAddress(), 0, false);

            new ListingGuardApiClient(config).call(ApiRequest.usage(), "r-1");

            assertThat(stub.only().path()).isEqualTo("/lg/api/v1/usage");
        }
    }

    @Test
    void aRedirectIsNotFollowedSoTheKeyIsNeverSentElsewhere() throws Exception {
        try (ApiStub stub = new ApiStub().replying(r -> Reply.json(302, "").with("Location", "http://127.0.0.1:1/steal"))) {
            ApiResponse response = new ListingGuardApiClient(stub.config()).call(ApiRequest.usage(), "r-1");

            assertThat(response.status()).isEqualTo(302);
            assertThat(stub.requests()).hasSize(1);
        }
    }

    @Test
    void anAnswerLargerThanTheLimitIsRefused() throws Exception {
        String big = "{\"x\":\"" + "a".repeat(ListingGuardApiClient.MAX_RESPONSE_BYTES + 10) + "\"}";
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, big))) {
            ListingGuardApiClient client = new ListingGuardApiClient(stub.config());

            assertThatThrownBy(() -> client.call(ApiRequest.usage(), "r-1"))
                    .isInstanceOfSatisfying(ApiCallException.class,
                            e -> assertThat(e.code()).isEqualTo(ApiCallException.INVALID_RESPONSE));
        }
    }

    @Test
    void anAnswerJustUnderTheLimitIsRead() throws Exception {
        String body = "{\"x\":\"" + "a".repeat(ListingGuardApiClient.MAX_RESPONSE_BYTES - 100) + "\"}";
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, body))) {
            ApiResponse response = new ListingGuardApiClient(stub.config()).call(ApiRequest.usage(), "r-1");

            assertThat(response.body()).hasSize(body.length());
        }
    }

    @Test
    void aSlowAnswerIsATimeout() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, "{}").after(Duration.ofSeconds(3)))) {
            ListingGuardApiClient client = new ListingGuardApiClient(stub.config(Duration.ofMillis(300)));
            long started = System.nanoTime();

            assertThatThrownBy(() -> client.call(ApiRequest.usage(), "r-1"))
                    .isInstanceOfSatisfying(ApiCallException.class,
                            e -> assertThat(e.code()).isEqualTo(ApiCallException.TIMEOUT));

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        }
    }

    @Test
    void aClosedPortIsUnreachable() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        McpServerConfig config = new McpServerConfig(ApiStub.KEY, URI.create("http://127.0.0.1:" + port),
                Duration.ofSeconds(2), TransportKind.STDIO, InetAddress.getLoopbackAddress(), 0, false);

        assertThatThrownBy(() -> new ListingGuardApiClient(config).call(ApiRequest.usage(), "r-1"))
                .isInstanceOfSatisfying(ApiCallException.class, e -> {
                    assertThat(e.code()).isEqualTo(ApiCallException.UNREACHABLE);
                    assertThat(e.getMessage()).doesNotContain(ApiStub.KEY);
                    assertThat(e.getStackTrace()).isEmpty();
                });
    }

    @Test
    void anEchoedKeyIsReplacedBeforeAnythingElseSeesTheAnswer() throws Exception {
        String body = "{\"detail\":\"bad key " + ApiStub.KEY + "\"}";
        try (ApiStub stub = new ApiStub().replying(Reply.problem(401, body).with("X-Request-Id", "id-" + ApiStub.KEY))) {
            ApiResponse response = new ListingGuardApiClient(stub.config()).call(ApiRequest.usage(), "r-1");

            assertThat(response.body()).doesNotContain(ApiStub.KEY).contains("<redacted>");
            assertThat(response.requestIdHeader()).doesNotContain(ApiStub.KEY);
        }
    }

    @Test
    void percentEncodeKeepsOnlyUnreservedCharacters() {
        assertThat(ListingGuardApiClient.percentEncode("Az09-_", false)).isEqualTo("Az09-_");
        assertThat(ListingGuardApiClient.percentEncode("a.b~c", false)).isEqualTo("a%2Eb%7Ec");
        assertThat(ListingGuardApiClient.percentEncode("a.b~c", true)).isEqualTo("a.b~c");
        assertThat(ListingGuardApiClient.percentEncode("Đ", false)).isEqualTo("%C4%90");
        assertThat(ListingGuardApiClient.percentEncode("", false)).isEmpty();
    }
}
