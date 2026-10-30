package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.ApiTestKeys;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** A catalog that cannot be served turns trademark checks off; the application starts and /checks keeps working. */
@SpringBootTest(properties = "listing-guard.trademark.catalog-version=configured-but-never-imported-0123456789ab")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class TrademarkRefusedStartupTest {

    @Autowired
    TrademarkCatalogRuntime runtime;

    @Autowired
    MockMvc mvc;

    @Test
    void theApplicationStartsAndTheConfiguredCatalogIsRefused() {
        assertThat(runtime.status().state()).isEqualTo(TrademarkCatalogRuntime.State.REFUSED);
        assertThat(runtime.status().detail()).contains("configured-but-never-imported-0123456789ab");
    }

    @Test
    void trademarkChecksAnswer503WhileTheCatalogIsRefused() throws Exception {
        mvc.perform(post("/api/v1/trademark-checks").header(ApiTestKeys.HEADER, ApiTestKeys.KEY_1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"Plain mug\"}}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("trademark-not-configured"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("configured-but-never-imported-0123456789ab")));
    }

    @Test
    void checksStillWork() throws Exception {
        String body = "{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"Plain mug " + UUID.randomUUID() + "\"}}";

        mvc.perform(post("/api/v1/checks").header(ApiTestKeys.HEADER, ApiTestKeys.KEY_1)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
