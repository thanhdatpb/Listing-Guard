package com.thanhdatpb.listingguard.api.image;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.MAPPER;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.main;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.product;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The endpoint switched on with the rulesets that ship (ADR-008): they hold no marketplace threshold until the maintainer has
 * reviewed the official pages, so every check against them is INCOMPLETE, names the checks that could not run, never gives a
 * clean summary, and is neither reused nor charged.
 */
@SpringBootTest(properties = "listing-guard.image.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ImageShippedRulesetsIntegrationTest {

    @Autowired
    MockMvc mvc;

    private JsonNode check(String marketplace) throws Exception {
        return MAPPER.readTree(mvc.perform(request(marketplace, List.of(main(product(320, 240, "PNG", 0)))).header(HEADER, KEY_1))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void aCheckAgainstTheShippedAmazonRulesetIsIncompleteAndSaysWhichChecksCouldNotRun() throws Exception {
        JsonNode result = check("AMAZON");

        assertThat(result.get("rulesetVersion").asText()).isEqualTo("amazon-image-2026-11");
        assertThat(result.get("completeness").asText()).isEqualTo("INCOMPLETE");
        assertThat(result.get("summary").asText()).isEqualTo("INCOMPLETE");
        assertThat(result.get("failedRules").toString()).contains("image_count", "images[0].image_dimensions",
                "images[0].aspect_ratio", "images[0].main_background", "images[0].occupancy", "images[0].blur");
        assertThat(result.get("findings")).isEmpty();
        assertThat(result.get("images").get(0).get("findings")).as("no verdict without a reviewed threshold").isEmpty();
        assertThat(result.get("images").get(0).get("metadata").get("format").asText()).isEqualTo("PNG");
        assertThat(check("AMAZON").get("cached").asBoolean()).as("an INCOMPLETE result is never reused").isFalse();
    }

    @Test
    void aCheckAgainstTheShippedEtsyRulesetIsIncompleteToo() throws Exception {
        JsonNode result = check("ETSY");

        assertThat(result.get("rulesetVersion").asText()).isEqualTo("etsy-image-2026-11");
        assertThat(result.get("completeness").asText()).isEqualTo("INCOMPLETE");
        assertThat(result.get("summary").asText()).isEqualTo("INCOMPLETE");
        assertThat(result.get("failedRules").toString()).contains("image_count");
    }
}
