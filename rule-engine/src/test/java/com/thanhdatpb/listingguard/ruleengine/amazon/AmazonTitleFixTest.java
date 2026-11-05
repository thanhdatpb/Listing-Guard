package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.listing;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.FixAction;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The candidate fix of the Amazon title length rule (ADR-014, A3), including the limit of a product-type override. */
class AmazonTitleFixTest {

    private static String words(int minLength) {
        StringBuilder text = new StringBuilder("alpha1");
        for (int i = 2; text.length() <= minLength; i++) {
            text.append(" alpha").append(i);
        }
        return text.toString();
    }

    private static Finding run(RulesetConfig config, String title, String productType) {
        List<Finding> findings = new TitleLengthRule().evaluate(
                listing(title, "Roadster", List.of("bullet"), "ok", "terms", productType), config);
        assertThat(findings).hasSize(1);
        return findings.get(0);
    }

    @Test
    void aLongTitleGetsAReplaceFixWithinTheLimit() {
        int max = limit("title_length", "max_length");
        String title = words(max);

        Finding finding = run(CONFIG, title, "LUGGAGE");

        assertThat(finding.fix().action()).isEqualTo(FixAction.REPLACE);
        String proposed = finding.fix().proposedValue();
        assertThat(title).startsWith(proposed);
        assertThat(proposed.codePointCount(0, proposed.length())).isLessThanOrEqualTo(max);
    }

    @Test
    void aTitleThatIsOneLongWordGetsNoFix() {
        assertThat(run(CONFIG, "x".repeat(limit("title_length", "max_length") + 1), "LUGGAGE").fix()).isNull();
    }

    @Test
    void theCutUsesTheLimitOfTheProductTypeOverride() {
        RulesetConfig config = RulesetLoader.loadClasspath("rulesets/amazon-test-categories.yaml");
        int defaultMax = config.rule("title_length").intParam("max_length");
        int bagMax = config.rule("title_length", "TESTBAG").intParam("max_length");
        assertThat(bagMax).isLessThan(defaultMax);
        String title = words(bagMax);

        Finding bag = run(config, title, "TESTBAG");

        String proposed = bag.fix().proposedValue();
        assertThat(proposed.codePointCount(0, proposed.length())).isLessThanOrEqualTo(bagMax);
        assertThat(bag.fix().description()).contains("tối đa " + bagMax);
        assertThat(new TitleLengthRule().evaluate(
                listing(title, "Roadster", List.of("bullet"), "ok", "terms", "OTHER"), config)).isEmpty();
    }
}
