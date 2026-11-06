package com.thanhdatpb.listingguard.api.variation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.ApiTestKeys;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersion;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersionRepository;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import com.thanhdatpb.listingguard.variation.config.VariationRulesetLoader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** ADR-010: the variation ruleset is registered per (pack, marketplace, version); /checks still selects listing-rules. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class VariationRulesetRegistrationIntegrationTest {

    @Autowired
    VariationRulesets variation;

    @Autowired
    ActiveRulesets textRulesets;

    @Autowired
    RulesetVersionRepository versions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    private static String yaml() throws Exception {
        return new ClassPathResource("rulesets/variation-checks-2026-11.yaml").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void theRulesetIsRegisteredForEtsyAndAmazonUnderItsOwnPack() {
        VariationRuleset ruleset = variation.ruleset();

        for (String marketplace : new String[]{"ETSY", "AMAZON"}) {
            RulesetVersion row = versions.findByPackIdAndMarketplaceCodeAndVersion("variation-checks", marketplace,
                    "variation-checks-2026-11").orElseThrow();
            assertThat(row.getContentHash()).isEqualTo(ruleset.contentHash());
            assertThat(row.getCheckedAt()).isEqualTo(ruleset.checkedAt());
            assertThat(variation.rulesetVersionId(marketplace)).isEqualTo(row.getId());
        }
        assertThat(variation.rulesetVersionId("etsy")).isEqualTo(variation.rulesetVersionId("ETSY"));
        assertThat(variation.rulesetVersionId("ETSY")).isNotEqualTo(variation.rulesetVersionId("AMAZON"));
        assertThatThrownBy(() -> variation.rulesetVersionId("WALMART")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void thePackIdIsTheOneOfThePlanPackAndTheOneTheChecksNeverSelect() {
        assertThat(variation.ruleset().packId()).isEqualTo(Pack.VARIATION_CHECKS.id());
        assertThat(textRulesets.get("ETSY").config().packId()).isEqualTo("listing-rules");
        assertThat(textRulesets.get("AMAZON").config().packId()).isEqualTo("listing-rules");
        assertThatThrownBy(() -> textRulesets.get("variation-checks", "ETSY")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ruleset_version WHERE pack_id = 'listing-rules' "
                + "AND marketplace_code = 'ETSY' AND version = ?", Integer.class,
                textRulesets.get("ETSY").config().version())).isEqualTo(1);
    }

    @Test
    void registeringTheSameContentAgainReturnsTheSameRow() throws Exception {
        VariationRuleset same = VariationRulesetLoader.parse(yaml().replace("\n", "\r\n"));

        assertThat(variation.register("ETSY", same)).isEqualTo(variation.rulesetVersionId("ETSY"));
    }

    @Test
    void theSameVersionWithDifferentContentIsRejected() throws Exception {
        VariationRuleset edited = VariationRulesetLoader.parse(yaml() + "\n# edited without bumping the version\n");

        assertThatThrownBy(() -> variation.register("AMAZON", edited)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("variation-checks-2026-11").hasMessageContaining("bump its version");
    }

    @Test
    void aNewVersionIsANewRowAndLeavesTheOldOneAlone() throws Exception {
        String version = "variation-checks-2099-" + UUID.randomUUID().toString().substring(0, 4);
        VariationRuleset next = VariationRulesetLoader.parse(yaml().replace("version: variation-checks-2026-11",
                "version: " + version));

        long id = variation.register("ETSY", next);

        assertThat(id).isNotEqualTo(variation.rulesetVersionId("ETSY"));
        assertThat(versions.findByPackIdAndMarketplaceCodeAndVersion("variation-checks", "ETSY", version)).isPresent();
        assertThat(versions.findByPackIdAndMarketplaceCodeAndVersion("variation-checks", "ETSY", "variation-checks-2026-11"))
                .isPresent();
    }

    @Test
    void checksStillWorkNextToTheVariationRegistration() throws Exception {
        String body = "{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"Plain mug " + UUID.randomUUID() + "\"}}";

        mvc.perform(post("/api/v1/checks").header(ApiTestKeys.HEADER, ApiTestKeys.KEY_1)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
