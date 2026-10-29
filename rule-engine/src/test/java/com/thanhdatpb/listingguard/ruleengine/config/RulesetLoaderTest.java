package com.thanhdatpb.listingguard.ruleengine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RulesetLoaderTest {

    private static final String ETSY = "rulesets/etsy-2026-11.yaml";

    private static final String MINIMAL = """
            marketplace: etsy
            version: v1
            checked_at: 2026-10-27
            rules:
              r1:
                severity: warning
                limit: 5
                source:
                  status: unverified
            """;

    private static String etsyText() throws IOException {
        try (InputStream in = RulesetLoaderTest.class.getResourceAsStream("/" + ETSY)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void loadsTheEtsyRuleset() {
        RulesetConfig config = RulesetLoader.loadClasspath(ETSY);

        assertThat(config.packId()).isEqualTo("listing-rules");
        assertThat(config.marketplace()).isEqualTo("ETSY");
        assertThat(config.version()).isEqualTo("etsy-2026-11");
        assertThat(config.checkedAt()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(config.contentHash()).matches("[0-9a-f]{64}");
        assertThat(config.rules()).containsOnlyKeys("title_length", "title_characters", "title_repeated_special_chars",
                "tag_count", "tag_length", "tag_characters", "duplicate_tags", "required_fields",
                "price_quantity_positive", "banned_words");
    }

    @Test
    void etsySeveritiesAndSourceStatusesMatchTheAgreedDecisions() {
        RulesetConfig config = RulesetLoader.loadClasspath(ETSY);

        assertThat(config.rule("title_length").severity()).isEqualTo(Severity.ERROR);
        assertThat(config.rule("duplicate_tags").severity()).isEqualTo(Severity.WARNING);
        assertThat(config.rule("banned_words").severity()).isEqualTo(Severity.WARNING);

        assertThat(config.rule("title_characters").severity()).isEqualTo(Severity.ERROR);
        assertThat(config.rule("title_repeated_special_chars").severity()).isEqualTo(Severity.WARNING);
        assertThat(config.rule("price_quantity_positive").severity()).isEqualTo(Severity.ERROR);
        assertThat(config.rule("title_characters").source().status()).isEqualTo(SourceStatus.VERIFIED);
        assertThat(config.rule("title_repeated_special_chars").source().status()).isEqualTo(SourceStatus.SECONDARY);
        assertThat(config.rule("price_quantity_positive").source().status()).isEqualTo(SourceStatus.VERIFIED);
        assertThat(config.rule("title_repeated_special_chars").stringListParam("chars")).containsExactly("%", ":", "&");

        assertThat(config.rule("title_length").source().status()).isEqualTo(SourceStatus.VERIFIED);
        assertThat(config.rule("tag_count").source().status()).isEqualTo(SourceStatus.SECONDARY);
        assertThat(config.rule("tag_length").source().status()).isEqualTo(SourceStatus.SECONDARY);
        assertThat(config.rule("tag_characters").source().status()).isEqualTo(SourceStatus.VERIFIED);
        assertThat(config.rule("required_fields").source().status()).isEqualTo(SourceStatus.VERIFIED);
        assertThat(config.rule("duplicate_tags").source().status()).isEqualTo(SourceStatus.UNVERIFIED);
        assertThat(config.rule("required_fields").stringListParam("fields")).doesNotContain("images");
        assertThat(config.rule("banned_words").boolParam("sample")).isTrue();
    }

    @Test
    void everyVerifiedOrSecondarySourceHasAUrl() {
        RulesetLoader.loadClasspath(ETSY).rules().values().forEach(settings -> {
            if (settings.source().status() != SourceStatus.UNVERIFIED) {
                assertThat(settings.source().urls()).as(settings.ruleId()).isNotEmpty();
            }
        });
    }

    @Test
    void hashIsTheSameForLfAndCrlfFiles() throws IOException {
        String lf = etsyText();
        String crlf = lf.replace("\n", "\r\n");

        assertThat(RulesetLoader.parse(crlf).contentHash()).isEqualTo(RulesetLoader.parse(lf).contentHash());
    }

    @Test
    void hashIgnoresALeadingBom() {
        assertThat(RulesetLoader.parse("﻿" + MINIMAL).contentHash())
                .isEqualTo(RulesetLoader.parse(MINIMAL).contentHash());
    }

    @Test
    void hashChangesWhenContentChanges() {
        assertThat(RulesetLoader.parse(MINIMAL.replace("limit: 5", "limit: 6")).contentHash())
                .isNotEqualTo(RulesetLoader.parse(MINIMAL).contentHash());
    }

    @Test
    void normalizesCaseOfMarketplaceAndSeverity() {
        RulesetConfig config = RulesetLoader.parse(MINIMAL);

        assertThat(config.marketplace()).isEqualTo("ETSY");
        assertThat(config.rule("r1").severity()).isEqualTo(Severity.WARNING);
        assertThat(config.rule("r1").intParam("limit")).isEqualTo(5);
    }

    @Test
    void acceptsAnExplicitPackIdWithoutChangingLegacyRulesetHashing() {
        RulesetConfig legacy = RulesetLoader.parse(MINIMAL);
        RulesetConfig explicit = RulesetLoader.parse(MINIMAL.replace("marketplace: etsy\n",
                "marketplace: etsy\npack_id: visual-checks\n"));

        assertThat(legacy.packId()).isEqualTo("listing-rules");
        assertThat(explicit.packId()).isEqualTo("visual-checks");
        assertThat(legacy.contentHash()).isEqualTo(RulesetLoader.parse(MINIMAL).contentHash());
        assertThat(explicit.contentHash()).isNotEqualTo(legacy.contentHash());
    }

    @Test
    void missingParameterFailsWithTheRuleAndKeyName() {
        RuleSettings settings = RulesetLoader.parse(MINIMAL).rule("r1");

        assertThatThrownBy(() -> settings.intParam("nope"))
                .isInstanceOf(RulesetException.class)
                .hasMessageContaining("r1")
                .hasMessageContaining("nope");
    }

    @Test
    void rejectsMissingVersion() {
        assertThatThrownBy(() -> RulesetLoader.parse(MINIMAL.replace("version: v1\n", "")))
                .isInstanceOf(RulesetException.class).hasMessageContaining("version");
    }

    @Test
    void rejectsBadDate() {
        assertThatThrownBy(() -> RulesetLoader.parse(MINIMAL.replace("2026-10-27", "27/10/2026")))
                .isInstanceOf(RulesetException.class).hasMessageContaining("checked_at");
    }

    @Test
    void rejectsMissingOrInvalidSeverity() {
        assertThatThrownBy(() -> RulesetLoader.parse(MINIMAL.replace("    severity: warning\n", "")))
                .isInstanceOf(RulesetException.class).hasMessageContaining("severity");
        assertThatThrownBy(() -> RulesetLoader.parse(MINIMAL.replace("warning", "fatal")))
                .isInstanceOf(RulesetException.class).hasMessageContaining("fatal");
    }

    @Test
    void rejectsVerifiedSourceWithoutUrl() {
        assertThatThrownBy(() -> RulesetLoader.parse(MINIMAL.replace("unverified", "verified")))
                .isInstanceOf(RulesetException.class).hasMessageContaining("URL");
    }

    @Test
    void rejectsMissingSource() {
        String noSource = MINIMAL.substring(0, MINIMAL.indexOf("    source:"));

        assertThatThrownBy(() -> RulesetLoader.parse(noSource))
                .isInstanceOf(RulesetException.class).hasMessageContaining("source");
    }

    @Test
    void rejectsUnknownTopLevelKeyAndDuplicateKeys() {
        assertThatThrownBy(() -> RulesetLoader.parse(MINIMAL + "extra: 1\n"))
                .isInstanceOf(RulesetException.class).hasMessageContaining("extra");
        assertThatThrownBy(() -> RulesetLoader.parse(MINIMAL.replace("limit: 5", "limit: 5\n    limit: 6")))
                .isInstanceOf(RulesetException.class);
    }

    @Test
    void rejectsEmptyRules() {
        assertThatThrownBy(() -> RulesetLoader.parse("marketplace: ETSY\nversion: v\nchecked_at: 2026-10-27\nrules: {}\n"))
                .isInstanceOf(RulesetException.class);
    }

    @Test
    void missingClasspathResourceFails() {
        assertThatThrownBy(() -> RulesetLoader.loadClasspath("rulesets/does-not-exist.yaml"))
                .isInstanceOf(RulesetException.class);
    }
}
