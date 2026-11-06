package com.thanhdatpb.listingguard.variation;

import static com.thanhdatpb.listingguard.variation.VariationFixtures.RULESET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.variation.config.RuleIds;
import com.thanhdatpb.listingguard.variation.config.Source;
import com.thanhdatpb.listingguard.variation.config.SourceStatus;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import com.thanhdatpb.listingguard.variation.config.VariationRulesetException;
import com.thanhdatpb.listingguard.variation.config.VariationRulesetLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class VariationRulesetLoaderTest {

    /**
     * The content hash of {@code variation-checks-2026-11.yaml}. A published version is immutable (ADR-002): a change to
     * the file is a new version and a new file, never a new value here.
     */
    private static final String FROZEN_HASH = "aa3e2a396705272dbeeab492af8b4c36cdf7b56f1a45ff1779815927ccb77438";

    private static String text() throws IOException {
        try (var in = VariationRulesetLoaderTest.class.getResourceAsStream("/" + VariationFixtures.RULESET_PATH)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String mutate(String from, String to) throws IOException {
        String text = text();
        assertThat(text).as("the anchor must be in the shipped file").contains(from);
        return text.replace(from, to);
    }

    private static void assertRefused(String yaml, String message) {
        assertThatThrownBy(() -> VariationRulesetLoader.parse(yaml)).isInstanceOf(VariationRulesetException.class)
                .hasMessageContaining(message);
    }

    // ---- the shipped file ----------------------------------------------------------------------------------------

    @Test
    void theShippedRulesetIsPinnedByItsContentHash() throws Exception {
        assertThat(RULESET.contentHash()).isEqualTo(FROZEN_HASH);
        // computed here from the bytes, with the line endings of ADR-002, not by the loader
        String lf = text().replace("\r\n", "\n").replace('\r', '\n');
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(lf.getBytes(StandardCharsets.UTF_8));
        assertThat(HexFormat.of().formatHex(digest)).isEqualTo(FROZEN_HASH);
    }

    @Test
    void theHashDoesNotDependOnLineEndingsOrABom() throws Exception {
        assertThat(VariationRulesetLoader.parse(text().replace("\n", "\r\n")).contentHash()).isEqualTo(FROZEN_HASH);
        assertThat(VariationRulesetLoader.parse("﻿" + text()).contentHash()).isEqualTo(FROZEN_HASH);
    }

    @Test
    void theIdentityIsTheOneOfTheAdr() {
        assertThat(RULESET.packId()).isEqualTo("variation-checks");
        assertThat(RULESET.version()).isEqualTo("variation-checks-2026-11");
        assertThat(RULESET.checkedAt()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(RULESET.marketplaces()).containsExactlyInAnyOrder("AMAZON", "ETSY");
    }

    @Test
    void everyRuleAndEveryKindIsDeclaredOnceInTheOrderOfTheFile() {
        assertThat(RULESET.rules()).extracting(VariationRuleset.RuleSpec::id).containsExactlyElementsOf(RuleIds.ALL);
        assertThat(RULESET.rule(RuleIds.PARENT_CHILD_STRUCTURE).kinds().keySet()).containsExactlyInAnyOrder(
                RuleIds.PARENT_MISSING, RuleIds.PARENT_THEME_MISSING, RuleIds.NO_CHILDREN,
                RuleIds.THEME_ATTRIBUTES_UNKNOWN, RuleIds.CHILD_PARENT_SKU_MISSING, RuleIds.CHILD_PARENT_SKU_MISMATCH,
                RuleIds.CHILD_ATTRIBUTE_MISSING);
        assertThat(RULESET.rule(RuleIds.ETSY_VARIATION_LIMITS).marketplaces()).containsExactly("ETSY");
        assertThat(RULESET.rule(RuleIds.PARENT_CHILD_STRUCTURE).marketplaces()).containsExactly("AMAZON");
    }

    private static List<Source> everySource() {
        List<Source> sources = new ArrayList<>();
        RULESET.themes().values().forEach(theme -> sources.add(theme.source()));
        RULESET.rules().forEach(rule -> sources.add(rule.source()));
        RULESET.themeAllowed().productTypes().values().forEach(entry -> sources.add(entry.source()));
        sources.add(RULESET.apparel().productTypesSource());
        sources.add(RULESET.apparel().alphaScale().source());
        sources.add(RULESET.childrenDiffer().ignoreWordsSource());
        sources.add(RULESET.etsyLimits().maxVariationTypesSource());
        sources.add(RULESET.etsyLimits().unsupportedPropertiesSource());
        sources.add(RULESET.etsyLimits().photoVariationLimitSource());
        return sources;
    }

    @Test
    void nothingInTheShippedFileIsVerifiedAndEverySecondarySourceHasAUrl() {
        assertThat(everySource()).isNotEmpty();
        for (Source source : everySource()) {
            assertThat(source.status()).isNotEqualTo(SourceStatus.VERIFIED);
            if (source.status() == SourceStatus.SECONDARY) {
                assertThat(source.urls()).isNotEmpty();
            }
        }
    }

    @Test
    void nothingIsAnErrorWhileNothingIsVerified() {
        for (VariationRuleset.RuleSpec rule : RULESET.rules()) {
            assertThat(rule.kinds().values()).extracting(VariationRuleset.Kind::severity).doesNotContain(Severity.ERROR);
        }
    }

    @Test
    void theRulesTheBriefCallsSecondaryOrUnverifiedAreExactlyThat() {
        assertThat(RULESET.rule(RuleIds.PARENT_CHILD_STRUCTURE).source().status()).isEqualTo(SourceStatus.SECONDARY);
        assertThat(RULESET.rule(RuleIds.VARIATION_THEME_ALLOWED).source().status()).isEqualTo(SourceStatus.SECONDARY);
        assertThat(RULESET.rule(RuleIds.APPAREL_SIZE_ATTRIBUTES).source().status()).isEqualTo(SourceStatus.SECONDARY);
        assertThat(RULESET.rule(RuleIds.ETSY_VARIATION_LIMITS).source().status()).isEqualTo(SourceStatus.SECONDARY);
        assertThat(RULESET.rule(RuleIds.DUPLICATE_COMBINATION).source().status()).isEqualTo(SourceStatus.UNVERIFIED);
        assertThat(RULESET.rule(RuleIds.DUPLICATE_SKU).source().status()).isEqualTo(SourceStatus.UNVERIFIED);
        assertThat(RULESET.rule(RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME).source().status()).isEqualTo(SourceStatus.UNVERIFIED);
    }

    @Test
    void noMessageClaimsThatAListingIsAcceptedOrApproved() {
        List<String> forbidden = List.of("an toàn", "được phép", "đã xác nhận", "đã được xác nhận", "được chấp nhận",
                "đã duyệt", "approved", "compliant");
        List<String> texts = new ArrayList<>(List.of(RULESET.disclaimer()));
        RULESET.rules().forEach(rule -> rule.kinds().values().forEach(kind -> {
            texts.add(kind.message());
            texts.add(kind.suggestion());
        }));
        for (String text : texts) {
            assertThat(text.toLowerCase()).as(text).doesNotContain(forbidden);
        }
    }

    // ---- the loader is strict --------------------------------------------------------------------------------------

    @Test
    void aKeyNobodyReadsIsRefused() throws Exception {
        assertRefused(text() + "\nstray: 1\n", "Unknown key 'stray'");
        assertRefused(mutate("    max_variation_types:\n", "    max_variation_types_typo: 1\n    max_variation_types:\n"),
                "Unknown key 'max_variation_types_typo'");
    }

    @Test
    void aMissingKeyIsRefused() throws Exception {
        assertRefused(text().replaceAll("(?m)^disclaimer:.*\\n", ""), "missing required key 'disclaimer'");
    }

    @Test
    void aDuplicateKeyIsRefused() throws Exception {
        assertRefused(mutate("version: variation-checks-2026-11\n", "version: variation-checks-2026-11\nversion: other\n"),
                "Invalid ruleset YAML");
    }

    @Test
    void theIdentityIsChecked() throws Exception {
        assertRefused(mutate("pack_id: variation-checks", "pack_id: other-pack"), "pack_id must be");
        assertRefused(mutate("version: variation-checks-2026-11", "version: Bad Version"), "version must match");
        assertRefused(mutate("checked_at: 2026-11-06", "checked_at: yesterday"), "checked_at must be an ISO date");
    }

    @Test
    void aSourceAboveUnverifiedNeedsAUrl() throws Exception {
        String anchor = "      status: unverified\n      note: Named in the maintainer's brief";
        assertRefused(mutate(anchor, anchor.replace("unverified", "verified")), "lists no URL");
        assertRefused(mutate(anchor, anchor.replace("unverified", "secondary")), "lists no URL");
        assertRefused(mutate(anchor, anchor.replace("unverified", "official")), "must be 'verified', 'secondary' or 'unverified'");
    }

    @Test
    void aUrlMustBeHttps() throws Exception {
        assertRefused(mutate("        - https://www.alura.io", "        - http://www.alura.io"), "must be https URLs");
    }

    @Test
    void anErrorNeedsAVerifiedSourceAndAnUnknownSeverityIsRefused() throws Exception {
        assertRefused(mutate("      parent_missing:\n        severity: WARNING", "      parent_missing:\n        severity: ERROR"),
                "ERROR needs a verified source");
        assertRefused(mutate("      no_children:\n        severity: INFO", "      no_children:\n        severity: LOUD"),
                "must be ERROR, WARNING or INFO");
    }

    @Test
    void theHeuristicRuleIsNeverAnErrorEvenWhenItsSourceIsVerified() throws Exception {
        String verified = mutate("      status: unverified\n      urls:\n        - https://riverbendconsulting.com",
                "      status: verified\n      urls:\n        - https://riverbendconsulting.com")
                .replace("      children_differ_outside_theme:\n        severity: WARNING",
                        "      children_differ_outside_theme:\n        severity: ERROR");
        assertRefused(verified, "must not be ERROR");
    }

    @Test
    void aMessageMayOnlyUseThePlaceholdersOfItsKind() throws Exception {
        assertRefused(mutate("message: Chưa có child nào được gửi kèm, nên không có gì để kiểm tra.",
                "message: Chưa có child {position} nào được gửi kèm."), "unknown placeholder '{position}'");
        assertRefused(mutate("message: Parent chưa khai báo variation_theme.", "message: Parent chưa khai báo variation_theme {"),
                "stray");
    }

    @Test
    void everyKindAndEveryRuleMustBeDeclaredAndNoOtherIsAccepted() throws Exception {
        assertRefused(mutate("      product_type_missing:", "      product_type_gone:"), "unknown kind 'product_type_gone'");
        assertRefused(text().replaceAll("(?s)      no_children:.*?(?=      theme_attributes_unknown:)", ""),
                "missing required kind 'no_children'");
        assertRefused(mutate("\n  duplicate_sku:\n", "\n  duplicate_skus:\n"), "unknown rule 'duplicate_skus'");
        String text = text();
        assertRefused(text.substring(0, text.indexOf("  etsy_variation_limits:")), "missing required rule 'etsy_variation_limits'");
    }

    @Test
    void aRuleCanOnlyRunForTheMarketplacesItsCodeHandles() throws Exception {
        assertRefused(mutate("  etsy_variation_limits:\n    marketplaces:\n      - ETSY",
                "  etsy_variation_limits:\n    marketplaces:\n      - AMAZON"), "can only run for");
        assertRefused(mutate("  duplicate_sku:\n    marketplaces:\n      - AMAZON\n      - ETSY",
                "  duplicate_sku:\n    marketplaces:\n      - AMAZON\n      - amazon"), "twice");
    }

    @Test
    void aThemeThatIsUsedMustBeDefined() throws Exception {
        assertRefused(mutate("          - SizeName-ColorName\n", "          - Nonexistent-Theme\n"),
                "'Nonexistent-Theme' which is not defined in themes");
    }

    @Test
    void numbersAreRangeChecked() throws Exception {
        assertRefused(mutate("    max_variation_types:\n      value: 2", "    max_variation_types:\n      value: 0"),
                "must be an integer from 1 to 100");
        assertRefused(mutate("max_value_chars: 200", "max_value_chars: 5"), "must be an integer from 20 to 500");
    }

    @Test
    void theApparelTablesMustBeConsistent() throws Exception {
        assertRefused(mutate("      body_type: [big, slim, regular, plus]", "      shoe_size: [a, b]"),
                "'shoe_size' which is not in required_attributes");
        assertRefused(mutate("        age_range: none", "        huge_range: none"), "which is not a value of size_class");
        assertRefused(mutate("        age_range: none", "        age_range: sideways"), "must be numeric, alpha or none");
        assertRefused(mutate("        - [xs]\n", "        - [xxs]\n"), "twice");
        assertRefused(mutate("    required_attributes:\n      - size_system", "    required_attributes:\n      - Size System"),
                "must be lower case letters, digits and '_'");
    }

    @Test
    void theClasspathLoaderReportsAMissingFile() {
        assertThatThrownBy(() -> VariationRulesetLoader.loadClasspath("rulesets/no-such-file.yaml"))
                .isInstanceOf(VariationRulesetException.class).hasMessageContaining("not found on classpath");
    }
}
