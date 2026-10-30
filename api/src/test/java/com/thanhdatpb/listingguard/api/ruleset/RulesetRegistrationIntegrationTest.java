package com.thanhdatpb.listingguard.api.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;

@SpringBootTest
@Import(TestcontainersConfig.class)
class RulesetRegistrationIntegrationTest {

    private static final String LOCATION = "rulesets/etsy-2026-11.yaml";

    @Autowired
    ActiveRulesets rulesets;

    @Autowired
    RulesetVersionRepository versions;

    private static String yaml() throws IOException {
        return new ClassPathResource(LOCATION).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void startupRegistersTheEtsyRulesetWithItsMetadata() {
        RulesetConfig config = rulesets.get("ETSY").config();

        RulesetVersion row = versions.findByPackIdAndMarketplaceCodeAndVersion("listing-rules", "ETSY", "etsy-2026-11").orElseThrow();

        assertThat(row.getPackId()).isEqualTo("listing-rules");
        assertThat(rulesets.get("listing-rules", "ETSY")).isEqualTo(rulesets.get("ETSY"));
        assertThat(rulesets.get("ETSY").rulesetVersionId()).isEqualTo(row.getId());
        assertThat(row.getContentHash()).isEqualTo(config.contentHash()).matches("[0-9a-f]{64}");
        assertThat(row.getCheckedAt()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(row.getSource()).contains("https://www.etsy.com/openapi/generated/oas/3.0.0.json");
    }

    @Test
    void registeringTheSameContentAgainReturnsTheSameRow() throws IOException {
        RulesetConfig config = RulesetLoader.parse(yaml());

        assertThat(rulesets.register(config).rulesetVersionId()).isEqualTo(rulesets.get("ETSY").rulesetVersionId());
    }

    @Test
    void windowsLineEndingsDoNotCountAsAChange() throws IOException {
        RulesetConfig crlf = RulesetLoader.parse(yaml().replace("\n", "\r\n"));

        assertThat(rulesets.register(crlf).rulesetVersionId()).isEqualTo(rulesets.get("ETSY").rulesetVersionId());
    }

    @Test
    void sameVersionWithDifferentContentIsRejected() throws IOException {
        RulesetConfig edited = RulesetLoader.parse(yaml() + "\n# edited without bumping the version\n");

        assertThatThrownBy(() -> rulesets.register(edited))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("etsy-2026-11")
                .hasMessageContaining("bump its version");
    }

    @Test
    void aNewVersionIsRegisteredAsANewRow() throws IOException {
        RulesetConfig next = RulesetLoader.parse(yaml().replace("version: etsy-2026-11", "version: etsy-2099-01"));

        ActiveRulesets.Active active = rulesets.register(next);

        assertThat(active.rulesetVersionId()).isNotEqualTo(rulesets.get("ETSY").rulesetVersionId());
        assertThat(versions.findByPackIdAndMarketplaceCodeAndVersion("listing-rules", "ETSY", "etsy-2099-01")).isPresent();
    }
}
