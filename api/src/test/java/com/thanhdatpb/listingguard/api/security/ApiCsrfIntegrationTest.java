package com.thanhdatpb.listingguard.api.security;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CSRF on the API chain: a request authenticated by a valid {@code X-API-Key} is exempt (it has no cookie to forge), and
 * a state-changing request that is not authenticated that way, on a path the key filter leaves open, is still refused by
 * Spring Security's CSRF protection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ApiCsrfIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void aRequestWithAValidKeyNeedsNoCsrfToken() throws Exception {
        // an unknown route: the answer is the application's 404 problem, not a CSRF refusal
        mvc.perform(post("/no-such-route").header(HEADER, KEY_1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void aPostOnAnOpenPathWithoutAKeyIsRefusedByCsrfProtection() throws Exception {
        mvc.perform(post("/health")).andExpect(status().isForbidden());
    }

    @Test
    void aRequestWithoutAKeyIsStillAnswered401NotByCsrf() throws Exception {
        mvc.perform(post("/api/v1/checks")).andExpect(status().isUnauthorized());
    }

    @Test
    void aRequestWithAnInvalidKeyIsStillAnswered401NotByCsrf() throws Exception {
        mvc.perform(post("/no-such-route").header(HEADER, "not-a-valid-key")).andExpect(status().isUnauthorized());
    }
}
