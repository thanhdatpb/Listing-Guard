package com.thanhdatpb.listingguard.ruleengine.hygiene;

import static com.thanhdatpb.listingguard.ruleengine.hygiene.ContentFixtures.AMAZON;
import static com.thanhdatpb.listingguard.ruleengine.hygiene.ContentFixtures.ENGINE;
import static com.thanhdatpb.listingguard.ruleengine.hygiene.ContentFixtures.ETSY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.amazon.AmazonRules;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import com.thanhdatpb.listingguard.ruleengine.etsy.EtsyRules;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The ruleset versions published before ADR-039 keep their results (ADR-002): the ten content rules are optional, so a ruleset that does not
 * configure them does not run them. The reference is an engine whose registry never had them.
 */
class OptionalRulesTest {

    private static final RulesetConfig ETSY_OLD = RulesetLoader.loadClasspath("rulesets/etsy-2026-12.yaml");
    private static final RulesetConfig AMAZON_OLD = RulesetLoader.loadClasspath("rulesets/amazon-2026-12c.yaml");
    private static final Set<String> CONTENT_IDS = ContentRules.all().stream().map(Rule::id).collect(Collectors.toSet());

    /** An engine as it was before the content rules existed. */
    private static RuleEngine before() {
        RuleRegistry registry = new RuleRegistry();
        EtsyRules.all().stream().filter(rule -> !CONTENT_IDS.contains(rule.id())).forEach(rule -> registry.register(EtsyRules.MARKETPLACE, rule));
        AmazonRules.all().stream().filter(rule -> !CONTENT_IDS.contains(rule.id())).forEach(rule -> registry.register(AmazonRules.MARKETPLACE, rule));
        return new RuleEngine(registry);
    }

    /** A listing that every content rule would report if its ruleset ran it, and some older rules too. */
    private static Listing dirtyEtsy() {
        return ContentFixtures.etsy("[NAME]  Mug 11oz for Mom (Set of 2 SALE SALE", "Perfect for dad. This 15 oz mug, pack of 4. CafÃ© TBD", List.of("mug", "mug"),
                "Home > Mugs");
    }

    private static Listing dirtyAmazon() {
        return ContentFixtures.amazon("Funny Mug 11oz Pack of 2 for Mom Mug Mug [NAME]  (", List.of("Holds 15 oz, perfect for dad", "CafÃ© VINTAGE RETRO FUNNY MUG"),
                "Pack of 4 mugs XXXXX", "SHIRT");
    }

    @Test
    void theNewListingsReallyBreakTheContentRules() {
        assertThat(ENGINE.run(dirtyEtsy(), ETSY)).extracting(Finding::ruleId).contains("mojibake", "placeholder_leftover", "whitespace",
                "unbalanced_brackets", "size_unit_conflict", "quantity_conflict", "recipient_conflict");
        assertThat(ENGINE.run(dirtyAmazon(), AMAZON)).extracting(Finding::ruleId).contains("mojibake", "placeholder_leftover", "whitespace",
                "unbalanced_brackets", "all_caps_ratio", "size_unit_conflict", "quantity_conflict", "recipient_conflict", "product_noun_conflict");
    }

    @Test
    void theOldVersionsStillStartAndGiveExactlyTheResultsOfAnEngineWithoutTheContentRules() {
        RuleEngine before = before();
        ENGINE.validate(ETSY_OLD);
        ENGINE.validate(AMAZON_OLD);
        assertThat(ENGINE.run(dirtyEtsy(), ETSY_OLD)).isEqualTo(before.run(dirtyEtsy(), ETSY_OLD)).isNotEmpty();
        assertThat(ENGINE.run(dirtyAmazon(), AMAZON_OLD)).isEqualTo(before.run(dirtyAmazon(), AMAZON_OLD)).isNotEmpty();
        assertThat(ENGINE.runDetailed(dirtyEtsy(), ETSY_OLD)).isEqualTo(before.runDetailed(dirtyEtsy(), ETSY_OLD));
        assertThat(ENGINE.runDetailed(dirtyAmazon(), AMAZON_OLD)).isEqualTo(before.runDetailed(dirtyAmazon(), AMAZON_OLD));
        assertThat(ENGINE.limits(ETSY_OLD, null)).isEqualTo(before.limits(ETSY_OLD, null));
        assertThat(ENGINE.limits(AMAZON_OLD, "LUGGAGE")).isEqualTo(before.limits(AMAZON_OLD, "LUGGAGE"));
        assertThat(ENGINE.skippedRuleIds(dirtyEtsy(), ETSY_OLD, Set.of("title"))).isEqualTo(before.skippedRuleIds(dirtyEtsy(), ETSY_OLD, Set.of("title")));
    }

    @Test
    void theNewVersionsGiveTheOldFindingsUnchangedPlusTheNewOnes() {
        assertThat(withoutContentRules(ENGINE.run(dirtyEtsy(), ETSY))).isEqualTo(sameFindingsOf(ENGINE.run(dirtyEtsy(), ETSY_OLD)));
        assertThat(withoutContentRules(ENGINE.run(dirtyAmazon(), AMAZON))).isEqualTo(sameFindingsOf(ENGINE.run(dirtyAmazon(), AMAZON_OLD)));
        assertThat(ENGINE.limits(ETSY, null)).extracting(l -> l.ruleId() + l.field() + l.value()).isEqualTo(
                ENGINE.limits(ETSY_OLD, null).stream().map(l -> l.ruleId() + l.field() + l.value()).toList());
        assertThat(ENGINE.limits(AMAZON, "LUGGAGE")).hasSameSizeAs(ENGINE.limits(AMAZON_OLD, "LUGGAGE"));
    }

    private static List<String> withoutContentRules(List<Finding> findings) {
        return findings.stream().filter(f -> !CONTENT_IDS.contains(f.ruleId())).map(OptionalRulesTest::line).toList();
    }

    private static List<String> sameFindingsOf(List<Finding> findings) {
        return findings.stream().map(OptionalRulesTest::line).toList();
    }

    /** A finding without the version of the ruleset that made it: the only thing a new version may change in an old rule's finding. */
    private static String line(Finding f) {
        return f.ruleId() + "|" + f.severity() + "|" + f.field() + "|" + f.value() + "|" + f.message() + "|" + f.suggestion() + "|" + f.matchStart() + "|" + f.matchEnd();
    }

    @Test
    void onlyTheWhitespaceRuleOfTheNewRulesProposesAFix() {
        List<Finding> found = ENGINE.runDetailed(ContentFixtures.etsy("Funny  Mug (", "A  nice mug [NAME] CafÃ© 11oz SET OF TWO", List.of("coffee  mug"), "Home > Mugs"), ETSY)
                .findings();
        Set<String> withFix = found.stream().filter(f -> f.fix() != null && CONTENT_IDS.contains(f.ruleId())).map(Finding::ruleId).collect(Collectors.toSet());
        assertThat(withFix).containsExactly("whitespace");
        assertThat(found).filteredOn(f -> f.ruleId().equals("whitespace")).extracting(Finding::field).contains("title", "tags[0]", "description");
        assertThat(found).filteredOn(f -> f.ruleId().equals("whitespace") && f.field().equals("description")).allMatch(f -> f.fix() == null);
        assertThat(found).filteredOn(f -> f.ruleId().equals("whitespace") && f.field().equals("tags[0]")).allMatch(f -> f.fix().proposedValue().equals("coffee mug"));
    }

    @Test
    void aBrokenContentRuleKeyFailsAtStartup() {
        String yaml = new String(readBytes("rulesets/etsy-2026-12b.yaml"), java.nio.charset.StandardCharsets.UTF_8);
        RulesetConfig badField = RulesetLoader.parse(yaml.replace("fields: [title, description, tags, materials]", "fields: [title, subtitle]"));
        assertThatThrownBy(() -> ENGINE.validate(badField)).isInstanceOf(RulesetException.class).hasMessageContaining("subtitle");
        RulesetConfig badRegex = RulesetLoader.parse(yaml.replace("'lorem\\s+ipsum'", "'lorem(ipsum'"));
        assertThatThrownBy(() -> ENGINE.validate(badRegex)).isInstanceOf(RulesetException.class).hasMessageContaining("regular expression");
        RulesetConfig noPairs = RulesetLoader.parse(yaml.replace("pairs: [\"()\", \"[]\", \"{}\"]", "pairs: [\"(\"]"));
        assertThatThrownBy(() -> ENGINE.validate(noPairs)).isInstanceOf(RulesetException.class).hasMessageContaining("pairs");
        RulesetConfig unknownRule = RulesetLoader.parse(yaml + "\n  made_up_rule:\n    severity: WARNING\n    source:\n      status: unverified\n      note: x\n");
        assertThatThrownBy(() -> ENGINE.validate(unknownRule)).isInstanceOf(RulesetException.class).hasMessageContaining("unknown rule");
    }

    private static byte[] readBytes(String resource) {
        try (var in = OptionalRulesTest.class.getClassLoader().getResourceAsStream(resource)) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
