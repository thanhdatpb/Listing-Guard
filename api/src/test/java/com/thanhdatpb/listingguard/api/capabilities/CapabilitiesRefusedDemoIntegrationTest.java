package com.thanhdatpb.listingguard.api.capabilities;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A configured catalog that cannot be served is {@code REFUSED}; its name starts with {@code demo-}, so it is also a demo. The
 * state is shown, the name never is (the refusal's own text names it, the capabilities never read that text).
 */
@SpringBootTest(properties = "listing-guard.trademark.catalog-version=demo-never-imported-0123456789ab")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CapabilitiesRefusedDemoIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    TrademarkCatalogRuntime runtime;

    @Test
    void aRefusedDemoCatalogShowsItsStateAndThatItIsADemoButNotItsName() throws Exception {
        assertThat(runtime.status().state()).isEqualTo(TrademarkCatalogRuntime.State.REFUSED);
        assertThat(runtime.status().detail()).contains("demo-never-imported-0123456789ab");

        String raw = mvc.perform(get("/api/v1/capabilities").header(HEADER, KEY_1)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode body = mapper.readTree(raw);

        assertThat(body.at("/trademarkCatalog/state").asText()).isEqualTo("REFUSED");
        assertThat(body.at("/trademarkCatalog/demo").asBoolean()).isTrue();
        body.get("packs").forEach(p -> {
            if (p.get("id").asText().equals("trademark-risk")) {
                assertThat(p.get("available").asBoolean()).isFalse();
            }
        });
        assertThat(raw).doesNotContain("demo-never-imported").doesNotContain("0123456789ab");
    }
}
