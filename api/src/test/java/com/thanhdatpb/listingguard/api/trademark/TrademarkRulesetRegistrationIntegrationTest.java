package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.ApiTestKeys;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersion;
import com.thanhdatpb.listingguard.api.ruleset.RulesetVersionRepository;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
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

/** ADR-010: the trademark ruleset is registered per (pack, marketplace, version); /checks still selects listing-rules. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class TrademarkRulesetRegistrationIntegrationTest {

    @Autowired
    TrademarkRulesets trademark;

    @Autowired
    ActiveRulesets textRulesets;

    @Autowired
    RulesetVersionRepository versions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    private static String yaml() throws Exception {
        return new ClassPathResource("rulesets/trademark-risk-2026-11.yaml").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void theRulesetIsRegisteredForEtsyAndAmazonUnderItsOwnPack() {
        TrademarkRuleset ruleset = trademark.ruleset();

        for (String marketplace : new String[]{"ETSY", "AMAZON"}) {
            RulesetVersion row = versions.findByPackIdAndMarketplaceCodeAndVersion("trademark-risk", marketplace,
                    "trademark-risk-2026-11").orElseThrow();
            assertThat(row.getContentHash()).isEqualTo(ruleset.contentHash());
            assertThat(row.getCheckedAt()).isEqualTo(ruleset.checkedAt());
            assertThat(trademark.rulesetVersionId(marketplace)).isEqualTo(row.getId());
        }
        assertThat(trademark.rulesetVersionId("etsy")).isEqualTo(trademark.rulesetVersionId("ETSY"));
        assertThat(trademark.rulesetVersionId("ETSY")).isNotEqualTo(trademark.rulesetVersionId("AMAZON"));
        assertThatThrownBy(() -> trademark.rulesetVersionId("WALMART")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theTextCheckPathStillSelectsOnlyTheListingRules() {
        assertThat(textRulesets.get("ETSY").config().packId()).isEqualTo("listing-rules");
        assertThat(textRulesets.get("AMAZON").config().packId()).isEqualTo("listing-rules");
        assertThatThrownBy(() -> textRulesets.get("trademark-risk", "ETSY")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ruleset_version WHERE pack_id = 'listing-rules' "
                + "AND marketplace_code = 'ETSY' AND version = 'etsy-2026-11'", Integer.class)).isEqualTo(1);
    }

    @Test
    void registeringTheSameContentAgainReturnsTheSameRow() throws Exception {
        TrademarkRuleset same = TrademarkRulesetLoader.parse(yaml().replace("\n", "\r\n"));

        assertThat(trademark.register("ETSY", same)).isEqualTo(trademark.rulesetVersionId("ETSY"));
    }

    @Test
    void theSameVersionWithDifferentContentIsRejected() throws Exception {
        TrademarkRuleset edited = TrademarkRulesetLoader.parse(yaml() + "\n# edited without bumping the version\n");

        assertThatThrownBy(() -> trademark.register("AMAZON", edited)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trademark-risk-2026-11").hasMessageContaining("bump its version");
    }

    @Test
    void aNewVersionIsANewRowAndLeavesTheOldOneAlone() throws Exception {
        String version = "trademark-risk-2099-" + UUID.randomUUID().toString().substring(0, 4);
        TrademarkRuleset next = TrademarkRulesetLoader.parse(yaml().replace("version: trademark-risk-2026-11",
                "version: " + version));

        long id = trademark.register("ETSY", next);

        assertThat(id).isNotEqualTo(trademark.rulesetVersionId("ETSY"));
        assertThat(versions.findByPackIdAndMarketplaceCodeAndVersion("trademark-risk", "ETSY", version)).isPresent();
        assertThat(versions.findByPackIdAndMarketplaceCodeAndVersion("trademark-risk", "ETSY", "trademark-risk-2026-11"))
                .isPresent();
    }

    @Test
    void checksStillWorkNextToTheTrademarkRegistration() throws Exception {
        String body = "{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"Plain mug " + UUID.randomUUID() + "\"}}";

        mvc.perform(post("/api/v1/checks").header(ApiTestKeys.HEADER, ApiTestKeys.KEY_1)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
