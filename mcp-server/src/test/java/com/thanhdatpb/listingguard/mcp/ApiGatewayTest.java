package com.thanhdatpb.listingguard.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.client.Operation;
import com.thanhdatpb.listingguard.client.RawRequest;
import com.thanhdatpb.listingguard.client.RawResponse;
import com.thanhdatpb.listingguard.mcp.ApiStub.Reply;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The server's one way to the API, now a raw call of the Java client SDK (ADR-023). These are the scenarios of the former
 * {@code ListingGuardApiClientTest}, with the same assertions; the unit test of the percent-encoding moved to the SDK
 * ({@code TransportTest.percentEncodeKeepsOnlyUnreservedCharacters}), and the encoding is still checked here end to end.
 */
class ApiGatewayTest {

    @Test
    void aPostSendsTheKeyTheRequestIdAndTheBodyAsGiven() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, "{\"checkId\":\"c-1\"}"))) {
            ApiGateway gateway = new ApiGateway(stub.config());
            String body = "{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"  Đạt  \"}}";

            RawResponse response = gateway.call(RawRequest.post(Operation.CHECKS, body), "req-123");

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
            assertThat(response.requestId()).isEqualTo("req-123");
        }
    }

    @Test
    void aGetHasNoBodyAndNoContentType() throws Exception {
        try (ApiStub stub = new ApiStub()) {
            new ApiGateway(stub.config()).call(RawRequest.usage(), "r-1");

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
            ApiGateway gateway = new ApiGateway(stub.config());

            gateway.call(RawRequest.rulesetLimits("ET SY/../x?y#z", "a b&c=d/é"), "r-1");

            ApiStub.Recorded recorded = stub.only();
            assertThat(recorded.path()).isEqualTo("/api/v1/rulesets/ET%20SY%2F%2E%2E%2Fx%3Fy%23z/limits");
            assertThat(recorded.target()).endsWith("?productType=a%20b%26c%3Dd%2F%C3%A9");
        }
    }

    @Test
    void aPlainMarketplaceIsNotAltered() throws Exception {
        try (ApiStub stub = new ApiStub()) {
            new ApiGateway(stub.config()).call(RawRequest.rulesetLimits("ETSY", null), "r-1");

            assertThat(stub.only().target()).isEqualTo("/api/v1/rulesets/ETSY/limits");
        }
    }

    @Test
    void aBaseUrlPathPrefixIsKept() throws Exception {
        try (ApiStub stub = new ApiStub()) {
            McpServerConfig config = new McpServerConfig(ApiStub.KEY, URI.create(stub.baseUri() + "/lg"),
                    Duration.ofSeconds(5), TransportKind.STDIO, InetAddress.getLoopbackAddress(), 0, false);

            new ApiGateway(config).call(RawRequest.usage(), "r-1");

            assertThat(stub.only().path()).isEqualTo("/lg/api/v1/usage");
        }
    }

    @Test
    void aRedirectIsNotFollowedSoTheKeyIsNeverSentElsewhere() throws Exception {
        try (ApiStub stub = new ApiStub().replying(r -> Reply.json(302, "").with("Location", "http://127.0.0.1:1/steal"))) {
            RawResponse response = new ApiGateway(stub.config()).call(RawRequest.usage(), "r-1");

            assertThat(response.status()).isEqualTo(302);
            assertThat(stub.requests()).hasSize(1);
        }
    }

    @Test
    void anAnswerLargerThanTheLimitIsRefused() throws Exception {
        String big = "{\"x\":\"" + "a".repeat(ApiGateway.MAX_RESPONSE_BYTES + 10) + "\"}";
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, big))) {
            ApiGateway gateway = new ApiGateway(stub.config());

            assertThatThrownBy(() -> gateway.call(RawRequest.usage(), "r-1"))
                    .isInstanceOfSatisfying(ApiCallException.class,
                            e -> assertThat(e.code()).isEqualTo(ApiCallException.INVALID_RESPONSE));
        }
    }

    @Test
    void anAnswerJustUnderTheLimitIsRead() throws Exception {
        String body = "{\"x\":\"" + "a".repeat(ApiGateway.MAX_RESPONSE_BYTES - 100) + "\"}";
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, body))) {
            RawResponse response = new ApiGateway(stub.config()).call(RawRequest.usage(), "r-1");

            assertThat(response.body()).hasSize(body.length());
        }
    }

    @Test
    void aSlowAnswerIsATimeout() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, "{}").after(Duration.ofSeconds(3)))) {
            ApiGateway gateway = new ApiGateway(stub.config(Duration.ofMillis(300)));
            long started = System.nanoTime();

            assertThatThrownBy(() -> gateway.call(RawRequest.usage(), "r-1"))
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

        assertThatThrownBy(() -> new ApiGateway(config).call(RawRequest.usage(), "r-1"))
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
            RawResponse response = new ApiGateway(stub.config()).call(RawRequest.usage(), "r-1");

            assertThat(response.body()).doesNotContain(ApiStub.KEY).contains("<redacted>");
            assertThat(response.requestId()).doesNotContain(ApiStub.KEY);
        }
    }

    @Test
    void theRetryPolicyIsNoneSoA503IsAnsweredOnce() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.problem(503, "{\"code\":\"service-unavailable\"}"))) {
            RawResponse response = new ApiGateway(stub.config()).call(RawRequest.usage(), "r-1");

            assertThat(response.status()).isEqualTo(503);
            assertThat(stub.requests()).hasSize(1);
        }
    }
}
