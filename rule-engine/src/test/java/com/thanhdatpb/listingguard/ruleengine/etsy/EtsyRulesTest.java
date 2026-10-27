package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The built-in Etsy rules wired together with the real ruleset. */
class EtsyRulesTest {

    private final RuleEngine engine = new RuleEngine(RuleRegistry.withDefaults());

    @Test
    void realRulesetPassesStartupValidation() {
        assertThatCode(() -> engine.validate(CONFIG)).doesNotThrowAnyException();
    }

    @Test
    void validListingHasNoFindings() {
        assertThat(engine.run(valid(), CONFIG)).isEmpty();
    }

    @Test
    void aBadListingTriggersEveryRule() {
        List<String> tags = new ArrayList<>(IntStream.range(0, limit("tag_count", "max_count")).mapToObj(i -> "tag" + i).toList());
        tags.add("x".repeat(limit("tag_length", "max_length") + 1));
        tags.add("bad_chars");
        tags.add("TAG0");
        tags.add(CONFIG.rule("banned_words").stringListParam("words").get(0));
        Listing base = valid();
        Listing bad = new Listing("x".repeat(limit("title_length", "max_length") + 1), null, tags, base.price(),
                base.category(), base.materials(), base.images(), base.quantity(), base.whoMade(), base.whenMade());

        List<Finding> findings = engine.run(bad, CONFIG);

        assertThat(findings).extracting(Finding::ruleId).containsExactlyInAnyOrder(
                "required_fields", "title_length", "tag_count", "tag_length", "tag_characters", "duplicate_tags",
                "banned_words");
    }

    @Test
    void severityCanBeChangedInYamlWithoutTouchingCode() throws IOException {
        String yaml = new String(getClass().getResourceAsStream("/rulesets/etsy-2026-10.yaml").readAllBytes(),
                StandardCharsets.UTF_8);
        String relaxed = yaml.replace("  tag_count:\n    severity: ERROR", "  tag_count:\n    severity: INFO");
        assertThat(relaxed).isNotEqualTo(yaml);
        RulesetConfig config = RulesetLoader.parse(relaxed);
        List<String> tooMany = IntStream.range(0, limit("tag_count", "max_count") + 1).mapToObj(i -> "tag" + i).toList();
        Listing base = valid();
        Listing listing = new Listing(base.title(), base.description(), tooMany, base.price(), base.category(),
                base.materials(), base.images(), base.quantity(), base.whoMade(), base.whenMade());

        List<Finding> findings = engine.run(listing, config);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.INFO);
    }
}
