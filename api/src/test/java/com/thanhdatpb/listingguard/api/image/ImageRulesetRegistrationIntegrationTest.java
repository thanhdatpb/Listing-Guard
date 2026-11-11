package com.thanhdatpb.listingguard.api.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.image.ImageMarketplace;
import com.thanhdatpb.listingguard.image.ImageRuleSetLoader;
import com.thanhdatpb.listingguard.image.ImageRuleset;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The shipped image rulesets (no thresholds yet) are registered in {@code ruleset_version} for each marketplace under the pack
 * {@code image-compliance} (ADR-010, ADR-008), are immutable (ADR-002), and do not appear to the text-check path. Registration
 * happens whether or not the endpoint is enabled, so a damaged ruleset fails the start-up in every deployment.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ImageRulesetRegistrationIntegrationTest {

    @Autowired
    ImageRulesets rulesets;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void eachMarketplaceRulesetIsRegisteredUnderTheImageCompliancePackWithItsHash() {
        assertThat(rulesets.marketplaces()).containsExactly("AMAZON", "ETSY");
        for (String marketplace : rulesets.marketplaces()) {
            ImageRuleset ruleset = rulesets.loaded(marketplace).ruleset();

            List<Map<String, Object>> rows = jdbc.queryForList("SELECT pack_id, content_hash FROM ruleset_version "
                    + "WHERE version = ? AND marketplace_code = ?", ruleset.version(), marketplace);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("pack_id")).isEqualTo("image-compliance");
            assertThat(((String) rows.get(0).get("content_hash")).trim()).isEqualTo(ruleset.contentHash());
            assertThat(rulesets.rulesetVersionId(marketplace.toLowerCase())).isEqualTo(rulesets.rulesetVersionId(marketplace))
                    .isPositive();
        }
    }

    @Test
    void registeringTheSameRulesetAgainChangesNothing() {
        long before = rulesets.rulesetVersionId("ETSY");

        assertThat(rulesets.register("ETSY", rulesets.loaded("ETSY").ruleset())).isEqualTo(before);
    }

    @Test
    void aPublishedVersionWithAnotherHashIsRefused() throws IOException {
        String text = new ClassPathResource("rulesets/etsy-image-2026-11.yaml").getContentAsString(StandardCharsets.UTF_8);
        ImageRuleset edited = ImageRuleSetLoader.parse(text.replace("max_decoded_pixels: 40000000", "max_decoded_pixels: 40000001"),
                ImageMarketplace.ETSY);

        assertThat(edited.version()).isEqualTo(rulesets.loaded("ETSY").ruleset().version());
        assertThatThrownBy(() -> rulesets.register("ETSY", edited)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered").hasMessageContaining("bump its version");
    }

    @Test
    void theTextCheckPathDoesNotSeeThePack() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ruleset_version WHERE pack_id = 'listing-rules' "
                + "AND version LIKE '%-image-%'", Integer.class)).isZero();
    }

    @Test
    void everyShippedThresholdIsStillNullAndEverySourceUnverified() {
        for (String marketplace : rulesets.marketplaces()) {
            ImageRulesets.Loaded loaded = rulesets.loaded(marketplace);

            assertThat(loaded.sourceStatusByRule().values()).as("sources of " + marketplace).containsOnly("unverified");
            // a ruleset with no number is INCOMPLETE for every check: ADR-008 forbids a remembered or inferred threshold
            assertThat(loaded.ruleset().document().path("profiles").path("default").toString()).doesNotMatch(".*[0-9].*");
        }
    }
}
