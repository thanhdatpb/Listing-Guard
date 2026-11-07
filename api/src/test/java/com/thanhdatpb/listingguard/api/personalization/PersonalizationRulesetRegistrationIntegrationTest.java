package com.thanhdatpb.listingguard.api.personalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRulesetLoader;
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
 * The personalization ruleset is registered in {@code ruleset_version} for each configured marketplace under its own pack
 * (ADR-010, ADR-016), is immutable (ADR-002), and does not appear to the text-check path.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class PersonalizationRulesetRegistrationIntegrationTest {

    @Autowired
    PersonalizationRulesets rulesets;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theRulesetIsRegisteredForEachMarketplaceUnderItsOwnPackWithItsHash() {
        PersonalizationRuleset ruleset = rulesets.ruleset();

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT marketplace_code, pack_id, content_hash FROM ruleset_version "
                + "WHERE version = ? ORDER BY marketplace_code", ruleset.version());

        assertThat(rows).extracting(row -> row.get("marketplace_code")).containsExactly("AMAZON", "ETSY");
        assertThat(rows).extracting(row -> row.get("pack_id")).containsOnly("personalization-screening");
        assertThat(rows).extracting(row -> ((String) row.get("content_hash")).trim()).containsOnly(ruleset.contentHash());
        assertThat(rulesets.rulesetVersionId("etsy")).isEqualTo(rulesets.rulesetVersionId("ETSY")).isPositive();
    }

    @Test
    void registeringTheSameRulesetAgainChangesNothing() {
        long before = rulesets.rulesetVersionId("ETSY");

        assertThat(rulesets.register("ETSY", rulesets.ruleset())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ruleset_version WHERE version = ?", Integer.class,
                rulesets.ruleset().version())).isEqualTo(2);
    }

    @Test
    void aPublishedVersionWithAnotherHashIsRefused() throws IOException {
        String text = new ClassPathResource("rulesets/personalization-screening-2026-11.yaml")
                .getContentAsString(StandardCharsets.UTF_8);
        PersonalizationRuleset edited = PersonalizationRulesetLoader.parse(text.replace("max_value_chars: 16",
                "max_value_chars: 17"));

        assertThat(edited.version()).isEqualTo(rulesets.ruleset().version());
        assertThatThrownBy(() -> rulesets.register("ETSY", edited)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered").hasMessageContaining("bump its version");
    }

    @Test
    void theTextCheckPathDoesNotSeeThePack() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ruleset_version WHERE pack_id = 'listing-rules' AND version = ?",
                Integer.class, rulesets.ruleset().version())).isZero();
    }
}
