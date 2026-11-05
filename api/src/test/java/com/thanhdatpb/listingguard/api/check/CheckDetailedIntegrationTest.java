package com.thanhdatpb.listingguard.api.check;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.ApiTestKeys;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.core.finding.FixAction;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR-014 B3: the console editor calls {@link CheckService#checkDetailed}, which verifies fixes and scores readiness
 * whatever {@code listing-guard.fixes.enabled} says. With the flag at its default (off), the API and the batch job
 * (which call {@link CheckService#check}) must keep their old shape, even for a result the editor stored.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CheckDetailedIntegrationTest {

    private static final String CHECKS = "/api/v1/checks";

    @Autowired
    CheckService service;

    @Autowired
    FixesProperties fixes;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void checkDetailedGivesFixesAndReadinessWhileTheFlagIsOff() throws Exception {
        assertThat(fixes.enabled()).isFalse();

        CheckResult result = service.checkDetailed(clientId(), request(uniqueDescription()));

        assertThat(result.fixesComputed()).isTrue();
        assertThat(result.readiness()).isNotNull();
        assertThat(result.readiness().status()).isEqualTo("SCORED");
        CheckResult.FindingView longTitle = finding(result, "title_length");
        assertThat(longTitle.fix()).isNotNull();
        assertThat(longTitle.fix().action()).isEqualTo(FixAction.REPLACE);
        assertThat(longTitle.fix().proposedValue()).isNotBlank();
    }

    @Test
    void theApiAndPlainCheckKeepTheOldShapeForAResultTheEditorStored() throws Exception {
        String description = uniqueDescription();
        CheckResult stored = service.checkDetailed(clientId(), request(description));
        assertThat(stored.fixesComputed()).isTrue();

        // same listing, same cache row: the flag-off callers must not see what the editor computed
        CheckResult plain = service.check(clientId(), request(description));
        assertThat(plain.checkId()).isEqualTo(stored.checkId());
        assertThat(plain.cached()).isTrue();
        assertThat(plain.fixesComputed()).isNull();
        assertThat(plain.readiness()).isNull();
        assertThat(plain.findings()).allSatisfy(finding -> assertThat(finding.fix()).isNull());

        JsonNode posted = mapper.readTree(mvc.perform(post(CHECKS).header(HEADER, KEY_1)
                        .contentType(MediaType.APPLICATION_JSON).content(body(description)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertOldShape(posted);

        JsonNode fetched = mapper.readTree(mvc.perform(get(CHECKS + "/" + stored.checkId()).header(HEADER, KEY_1))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertOldShape(fetched);
    }

    @Test
    void checkDetailedEnrichesAResultThatWasStoredWithoutFixes() throws Exception {
        String description = uniqueDescription();
        CheckResult plain = service.check(clientId(), request(description));
        assertThat(plain.fixesComputed()).isNull();
        assertThat(jdbc.queryForObject("SELECT fixes_computed FROM listing_check WHERE id = ?", Boolean.class,
                plain.checkId())).isFalse();

        CheckResult detailed = service.checkDetailed(clientId(), request(description));

        assertThat(detailed.checkId()).isEqualTo(plain.checkId());
        assertThat(detailed.cached()).isTrue();
        assertThat(detailed.fixesComputed()).isTrue();
        assertThat(detailed.readiness()).isNotNull();
        assertThat(finding(detailed, "title_length").fix()).isNotNull();
        assertThat(detailed.contentHash()).isEqualTo(plain.contentHash());
        assertThat(detailed.analysisVersion()).isEqualTo(plain.analysisVersion());
    }

    private static void assertOldShape(JsonNode response) {
        assertThat(response.has("fixesComputed")).isFalse();
        assertThat(response.has("readiness")).isFalse();
        for (JsonNode finding : response.get("findings")) {
            assertThat(finding.has("fix")).isFalse();
        }
    }

    private static CheckResult.FindingView finding(CheckResult result, String ruleId) {
        return result.findings().stream().filter(finding -> finding.ruleId().equals(ruleId)).findFirst()
                .orElseThrow(() -> new AssertionError("no finding for rule " + ruleId));
    }

    private UUID clientId() {
        return jdbc.queryForObject("SELECT id FROM client WHERE name = ?", UUID.class, ApiTestKeys.CLIENT_1);
    }

    private static String uniqueDescription() {
        return "A synthetic ceramic mug " + UUID.randomUUID();
    }

    private CheckRequest request(String description) throws Exception {
        return mapper.readValue(body(description), CheckRequest.class);
    }

    /** A title far over any Etsy limit, made of short words so that a cut at a word boundary exists. */
    private static String body(String description) {
        String title = "Handmade ceramic coffee mug ".repeat(8).trim();
        return """
                {
                  "marketplace": "ETSY",
                  "listing": {
                    "title": "%s",
                    "description": "%s",
                    "tags": ["mug", "ceramic"],
                    "price": {"amount": 1999, "divisor": 100, "currency_code": "USD"},
                    "taxonomy_path": ["Home & Living", "Mugs"],
                    "quantity": 5,
                    "who_made": "i_did",
                    "when_made": "made_to_order"
                  }
                }
                """.formatted(title, description);
    }
}
