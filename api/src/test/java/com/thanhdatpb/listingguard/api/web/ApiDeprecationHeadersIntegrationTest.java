package com.thanhdatpb.listingguard.api.web;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * A deployment that deprecates {@code GET /api/v1/checks/{id}} (ADR-022, section 6): every response of that operation, the
 * stored result, the problem for an unknown id and the {@code 401} alike, carries {@code Deprecation}, {@code Sunset} and
 * {@code Link}; any other operation carries only the version. The start-up check accepted the setting against the real mappings.
 */
@SpringBootTest(properties = {
        "listing-guard.api.deprecation.endpoints[0].method=GET",
        "listing-guard.api.deprecation.endpoints[0].path=/api/v1/checks/{id}",
        "listing-guard.api.deprecation.endpoints[0].deprecated-at=2027-01-01",
        "listing-guard.api.deprecation.endpoints[0].sunset-at=2027-07-01T00:00:00Z",
        "listing-guard.api.deprecation.endpoints[0].link=https://example.com/listing-guard/migration"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ApiDeprecationHeadersIntegrationTest {

    private static final String DEPRECATION = "@1798761600";
    private static final String SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT";
    private static final String LINK = "<https://example.com/listing-guard/migration>; rel=\"deprecation\"; type=\"text/html\"";

    @Autowired
    MockMvc mvc;

    @Autowired
    ServerVersion serverVersion;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyResponseOfTheDeprecatedOperationCarriesTheHeaders() throws Exception {
        mvc.perform(get("/api/v1/checks/" + UUID.randomUUID()).header(HEADER, KEY_1))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Deprecation", DEPRECATION))
                .andExpect(header().string("Sunset", SUNSET))
                .andExpect(header().string("Link", LINK))
                .andExpect(header().string("X-Listing-Guard-Version", serverVersion.value()));
        mvc.perform(get("/api/v1/checks/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Deprecation", DEPRECATION))
                .andExpect(header().string("Sunset", SUNSET));
    }

    @Test
    void anotherOperationCarriesOnlyTheVersion() throws Exception {
        mvc.perform(post("/api/v1/checks").header(HEADER, KEY_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"Handmade Ceramic Mug\"}}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Listing-Guard-Version", serverVersion.value()))
                .andExpect(header().doesNotExist("Deprecation"))
                .andExpect(header().doesNotExist("Sunset"))
                .andExpect(header().doesNotExist("Link"));
        mvc.perform(get("/health")).andExpect(header().doesNotExist("Deprecation")).andExpect(header().doesNotExist("X-Listing-Guard-Version"));
    }

    @Test
    void theStartUpCheckReadsTheRealOperations() {
        assertThat(ApiDeprecationStartupCheck.operations(mappings))
                .contains("GET /api/v1/checks/{id}", "POST /api/v1/checks", "GET /api/v1/capabilities");
    }
}
