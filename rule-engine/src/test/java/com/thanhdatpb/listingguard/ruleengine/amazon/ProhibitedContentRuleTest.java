package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.listing;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProhibitedContentRuleTest {

    private final ProhibitedContentRule rule = new ProhibitedContentRule();

    private static Listing with(String title, List<String> bullets, String description) {
        return listing(title, "Roadster", bullets, description, "carry on", "LUGGAGE");
    }

    private List<Finding> run(Listing listing) {
        return rule.evaluate(listing, CONFIG);
    }

    @Test
    void aCleanListingHasNoFindings() {
        assertThat(run(valid())).isEmpty();
    }

    @Test
    void aWebAddressInTheTitleIsReported() {
        List<Finding> findings = run(with("Luggage www.example.com best", List.of(), "ok"));

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("prohibited_content");
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("title");
        assertThat(finding.value()).isEqualTo("www.example.com");
        assertThat(finding.message()).isEqualTo("Phát hiện url trong title: 'www.example.com'.");
        assertThat(finding.suggestion()).contains("url");
    }

    @Test
    void httpAndHttpsLinksAreWebAddressesIgnoringCase() {
        assertThat(run(with("Luggage", List.of("See HTTPS://shop.example.com/x?a=1"), "ok")))
                .extracting(Finding::value).containsExactly("HTTPS://shop.example.com/x?a=1");
        assertThat(run(with("Luggage", List.of("See http://example.com"), "ok"))).hasSize(1);
    }

    @Test
    void anEmailAddressInTheDescriptionIsReported() {
        List<Finding> findings = run(with("Luggage", List.of(), "Contact me at jane.doe+shop@example.co.uk now"));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).field()).isEqualTo("description");
        assertThat(findings.get(0).value()).isEqualTo("jane.doe+shop@example.co.uk");
        assertThat(findings.get(0).message()).contains("email");
    }

    @Test
    void eachBulletIsCheckedWithItsOwnPosition() {
        List<Finding> findings = run(with("Luggage", List.of("Fine", "Visit www.a.com", "Mail a@b.com"), "ok"));

        assertThat(findings).extracting(Finding::field).containsExactly("bullet_points[1]", "bullet_points[2]");
    }

    @Test
    void urlAndEmailInTheSameFieldAreTwoFindingsInConfiguredOrder() {
        List<Finding> findings = run(with("Luggage", List.of(), "Visit www.example.com or write to a@example.com"));

        assertThat(findings).extracting(Finding::value).containsExactly("www.example.com", "a@example.com");
    }

    @Test
    void onlyTheFirstMatchOfAKindInAFieldIsReported() {
        assertThat(run(with("Luggage", List.of(), "www.a.com and www.b.com"))).extracting(Finding::value).containsExactly("www.a.com");
    }

    @Test
    void wordsThatMerelyContainAnAtSignOrDotAreNotMatches() {
        assertThat(run(with("Luggage 20 in. wide", List.of("Size: 20 x 10 in."), "Call @ noon, e.g. today"))).isEmpty();
    }

    @Test
    void fieldsNotListedInTheConfigAreNotScanned() {
        RulesetConfig config = config("[title]", "url: '(?i)\\bwww\\.\\S+'");

        assertThat(rule.evaluate(with("Luggage", List.of("www.a.com"), "www.b.com"), config)).isEmpty();
        assertThat(rule.evaluate(with("www.c.com", List.of(), null), config)).hasSize(1);
    }

    @Test
    void theSearchTermsAreNotScanned() {
        Listing listing = listing("Luggage", "Roadster", List.of(), "ok", "www.example.com", "LUGGAGE");

        assertThat(run(listing)).isEmpty();
    }

    @Test
    void anInvalidRegexFailsValidationAndNamesThePattern() {
        RulesetConfig config = config("[title]", "broken: '(unclosed'");

        assertThatThrownBy(() -> rule.validate(config)).isInstanceOf(RulesetException.class).hasMessageContaining("broken");
    }

    @Test
    void anUnknownFieldFailsValidation() {
        assertThatThrownBy(() -> rule.validate(config("[title, tags]", "url: 'x'")))
                .isInstanceOf(RulesetException.class).hasMessageContaining("tags");
    }

    @Test
    void missingOrEmptyPatternsFailValidation() {
        RulesetConfig missing = RulesetLoader.parse("""
                marketplace: AMAZON
                version: t
                checked_at: 2026-10-28
                rules:
                  prohibited_content:
                    severity: WARNING
                    fields: [title]
                    source:
                      status: unverified
                """);

        assertThatThrownBy(() -> rule.validate(missing)).isInstanceOf(RulesetException.class).hasMessageContaining("patterns");
        assertThatThrownBy(() -> rule.validate(config("[title]", "{}"))).isInstanceOf(RulesetException.class);
    }

    private static RulesetConfig config(String fields, String patterns) {
        String indented = patterns.lines().map(line -> "      " + line).reduce((a, b) -> a + "\n" + b).orElse("");
        return RulesetLoader.parse("marketplace: AMAZON\nversion: t\nchecked_at: 2026-10-28\nrules:\n"
                + "  prohibited_content:\n    severity: WARNING\n    fields: " + fields + "\n    patterns:\n" + indented
                + "\n    source:\n      status: unverified\n");
    }
}
