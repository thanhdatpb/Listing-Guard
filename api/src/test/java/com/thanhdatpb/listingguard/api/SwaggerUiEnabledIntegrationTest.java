package com.thanhdatpb.listingguard.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** The development flag: Swagger UI and the OpenAPI document open without a key, the API itself still needs one. */
@SpringBootTest(properties = "listing-guard.api.swagger-ui-enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SwaggerUiEnabledIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void theUiAndTheSpecAreOpenWithoutAKey() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void theApiStillNeedsAKey() throws Exception {
        mvc.perform(get("/api/v1/checks/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
}
