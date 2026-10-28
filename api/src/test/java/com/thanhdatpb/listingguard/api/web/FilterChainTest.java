package com.thanhdatpb.listingguard.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.security.ApiKeyFilter;
import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.security.ClientAuthenticator;
import com.thanhdatpb.listingguard.api.security.RateLimitFilter;
import com.thanhdatpb.listingguard.api.security.RateLimitProperties;
import com.thanhdatpb.listingguard.api.security.RateLimiter;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The four request filters in their real order, in front of a stub controller and the real exception handler. */
class FilterChainTest {

    private static final String KEY_A = "a".repeat(40);
    private static final String KEY_B = "b".repeat(40);
    /** A second key of the same client as KEY_A. */
    private static final String KEY_A2 = "c".repeat(40);
    private static final AuthenticatedClient CLIENT_A = new AuthenticatedClient(UUID.randomUUID(), "client-a", "keya0001");
    private static final AuthenticatedClient CLIENT_B = new AuthenticatedClient(UUID.randomUUID(), "client-b", "keyb0001");
    private static final int MAX_BODY = 100;

    @RestController
    static class StubController {

        @GetMapping("/health")
        String health() {
            return "up";
        }

        @GetMapping("/api/v1/ping")
        String ping() {
            return "pong";
        }

        @GetMapping("/v3/api-docs")
        String docs() {
            return "docs";
        }

        @PostMapping(value = "/api/v1/echo", consumes = MediaType.TEXT_PLAIN_VALUE)
        String echo(@RequestBody String body) {
            return "got " + body.length();
        }
    }

    private final long[] nanos = {0};
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    private final ClientAuthenticator authenticator = key -> {
        if (key.equals(KEY_A)) {
            return Optional.of(CLIENT_A);
        }
        if (key.equals(KEY_A2)) {
            return Optional.of(new AuthenticatedClient(CLIENT_A.id(), CLIENT_A.name(), "keya0002"));
        }
        return key.equals(KEY_B) ? Optional.of(CLIENT_B) : Optional.empty();
    };

    private MockMvc mvc(boolean docsPublic, int burst) {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 60, burst), () -> nanos[0]);
        return MockMvcBuilders.standaloneSetup(new StubController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter(), new BodyLimitFilter(MAX_BODY, mapper), new ApiKeyFilter(authenticator, docsPublic, mapper),
                        new RateLimitFilter(limiter, mapper))
                .build();
    }

    private MockMvc mvc() {
        return mvc(false, 2);
    }

    // ---- request id ---------------------------------------------------------------------------------

    @Test
    void generatesARequestIdWhenTheClientSendsNone() throws Exception {
        String id = mvc().perform(get("/health")).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Request-Id");

        assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void keepsAClientRequestIdAndRepeatsItInProblemResponses() throws Exception {
        mvc().perform(get("/api/v1/ping").header("X-Request-Id", "client-123.abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "client-123.abc"))
                .andExpect(jsonPath("$.requestId").value("client-123.abc"));
    }

    @Test
    void replacesARequestIdThatIsNotSane() throws Exception {
        for (String bad : new String[] {"has space", "x".repeat(65), "semi;colon"}) {
            String id = mvc().perform(get("/health").header("X-Request-Id", bad)).andReturn().getResponse().getHeader("X-Request-Id");
            assertThat(id).as(bad).isNotEqualTo(bad).hasSize(36);
        }
    }

    // ---- API key ------------------------------------------------------------------------------------

    @Test
    void missingKeyIs401WithAStandardProblem() throws Exception {
        var result = mvc().perform(get("/api/v1/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", Matchers.startsWith("ApiKey")))
                .andExpect(jsonPath("$.type").value("urn:listing-guard:problem:unauthorized"))
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/v1/ping"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("missing")))
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
    }

    @Test
    void invalidKeyIs401AndTheKeyIsNeverEchoed() throws Exception {
        String wrong = "w".repeat(40);

        String body = mvc().perform(get("/api/v1/ping").header("X-API-Key", wrong))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(wrong);
    }

    @Test
    void anyKnownKeyIsAccepted() throws Exception {
        mvc().perform(get("/api/v1/ping").header("X-API-Key", KEY_A)).andExpect(status().isOk());
        mvc().perform(get("/api/v1/ping").header("X-API-Key", KEY_B)).andExpect(status().isOk());
    }

    @Test
    void healthNeedsNoKeyAndIsNeverRateLimited() throws Exception {
        MockMvc mvc = mvc();
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/health")).andExpect(status().isOk());
        }
    }

    @Test
    void theOpenApiDocumentNeedsAKeyUnlessTheDevelopmentFlagIsOn() throws Exception {
        mvc(false, 2).perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc(false, 2).perform(get("/v3/api-docs").header("X-API-Key", KEY_A)).andExpect(status().isOk());
        mvc(true, 2).perform(get("/v3/api-docs")).andExpect(status().isOk());
        mvc(true, 2).perform(get("/api/v1/ping")).andExpect(status().isUnauthorized());
    }

    // ---- rate limit ---------------------------------------------------------------------------------

    @Test
    void tooManyRequestsFromOneClientGet429WithRetryAfterAndOtherClientsAreUnaffected() throws Exception {
        MockMvc mvc = mvc(false, 2);
        mvc.perform(get("/api/v1/ping").header("X-API-Key", KEY_A)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ping").header("X-API-Key", KEY_A)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/ping").header("X-API-Key", KEY_A).header("X-Request-Id", "rl-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value("rate-limited"))
                .andExpect(jsonPath("$.type").value("urn:listing-guard:problem:rate-limited"))
                .andExpect(jsonPath("$.requestId").value("rl-1"));

        mvc.perform(get("/api/v1/ping").header("X-API-Key", KEY_B)).andExpect(status().isOk());

        nanos[0] += TimeUnit.SECONDS.toNanos(1) + 1;
        mvc.perform(get("/api/v1/ping").header("X-API-Key", KEY_A)).andExpect(status().isOk());
    }

    @Test
    void allKeysOfOneClientShareOneBucket() throws Exception {
        MockMvc mvc = mvc(false, 2);
        mvc.perform(get("/api/v1/ping").header("X-API-Key", KEY_A)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ping").header("X-API-Key", KEY_A2)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/ping").header("X-API-Key", KEY_A)).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/v1/ping").header("X-API-Key", KEY_A2)).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/v1/ping").header("X-API-Key", KEY_B)).andExpect(status().isOk());
    }

    @Test
    void rejectedKeysDoNotUseUpAnyBucket() throws Exception {
        MockMvc mvc = mvc(false, 1);
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/ping").header("X-API-Key", "w".repeat(40))).andExpect(status().isUnauthorized());
        }

        mvc.perform(get("/api/v1/ping").header("X-API-Key", KEY_A)).andExpect(status().isOk());
    }

    // ---- body size limit ----------------------------------------------------------------------------

    @Test
    void aBodyWithinTheLimitPasses() throws Exception {
        mvc().perform(post("/api/v1/echo").header("X-API-Key", KEY_A).contentType(MediaType.TEXT_PLAIN).content("x".repeat(MAX_BODY)))
                .andExpect(status().isOk());
    }

    @Test
    void aBodyOverTheLimitIs413BeforeItIsRead() throws Exception {
        mvc().perform(post("/api/v1/echo").header("X-API-Key", KEY_A).contentType(MediaType.TEXT_PLAIN).content("x".repeat(MAX_BODY + 1)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("payload-too-large"))
                .andExpect(jsonPath("$.type").value("urn:listing-guard:problem:payload-too-large"));
    }

    @Test
    void aBodyWithNoDeclaredLengthIsCutOffWhenItGrowsPastTheLimit() throws Exception {
        mvc().perform(context -> {
                    MockHttpServletRequest request = new MockHttpServletRequest(context, "POST", "/api/v1/echo") {
                        @Override
                        public long getContentLengthLong() {
                            return -1;
                        }

                        @Override
                        public int getContentLength() {
                            return -1;
                        }
                    };
                    request.addHeader("X-API-Key", KEY_A);
                    request.setContentType(MediaType.TEXT_PLAIN_VALUE);
                    request.setContent("x".repeat(MAX_BODY + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return request;
                })
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("payload-too-large"));
    }

    @Test
    void theLimitStreamFailsOnceMoreThanTheLimitHasBeenRead() throws Exception {
        var stream = new BodyLimitFilter.LimitedStream(new DelegatingServletInputStream(new ByteArrayInputStream(new byte[MAX_BODY + 1])), MAX_BODY);

        assertThat(stream.read(new byte[MAX_BODY], 0, MAX_BODY)).isEqualTo(MAX_BODY);
        assertThatThrownBy(stream::read).isInstanceOf(PayloadTooLargeException.class);
    }
}
