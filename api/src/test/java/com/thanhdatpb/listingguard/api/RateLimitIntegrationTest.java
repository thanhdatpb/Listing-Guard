package com.thanhdatpb.listingguard.api;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_2;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** The rate limit wired into the real application, per client, with a burst of 3 and the default 60 requests per minute. */
@SpringBootTest(properties = {"test.custom-rate-limit=true", "listing-guard.rate-limit.burst=3",
        "listing-guard.rate-limit.requests-per-minute=60"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class RateLimitIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    private void call(String key, int expectedStatus) throws Exception {
        mvc.perform(get("/api/v1/checks/" + UUID.randomUUID()).header(HEADER, key)).andExpect(status().is(expectedStatus));
    }

    @Test
    void theFourthRequestWithinTheBurstWindowIs429AndOtherClientsAreNotAffected() throws Exception {
        call(KEY_1, 404);
        call(KEY_1, 404);
        call(KEY_1, 404);

        mvc.perform(get("/api/v1/checks/" + UUID.randomUUID()).header(HEADER, KEY_1).header("X-Request-Id", "rate-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate-limited"))
                .andExpect(jsonPath("$.type").value("urn:listing-guard:problem:rate-limited"))
                .andExpect(jsonPath("$.requestId").value("rate-1"));

        call(KEY_2, 404);
        mvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void trademarkChecksUseTheSameBucketAsChecks() throws Exception {
        String client = "rl" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        provisioning.createClient(client);
        String key = provisioning.createKey(client, "only").secret();

        call(key, 404);
        mvc.perform(get("/api/v1/trademark-checks/" + UUID.randomUUID()).header(HEADER, key)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/trademark-checks/" + UUID.randomUUID()).header(HEADER, key)).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/trademark-checks/" + UUID.randomUUID()).header(HEADER, key))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate-limited"));
        call(key, 429);
    }

    @Test
    void theKeysOfOneClientShareOneLimit() throws Exception {
        String client = "rl" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        provisioning.createClient(client);
        String first = provisioning.createKey(client, "first").secret();
        String second = provisioning.createKey(client, "second").secret();

        call(first, 404);
        call(second, 404);
        call(first, 404);

        call(second, 429);
        call(first, 429);
    }
}
