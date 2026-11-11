package com.thanhdatpb.listingguard.trademark;

import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.mark;
import static com.thanhdatpb.listingguard.trademark.ScannerFixtures.scannerOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-009, "Product classes, source links and generic words": a public look-up link is built from a template of the
 * ruleset, only for a source key the ruleset names and only from an id that matches that entry's pattern.
 */
class SourceLinksTest {

    private final TrademarkRuleset ruleset = TestRulesets.test();
    private final TrademarkRuleset.SourceLinks links = ruleset.sourceLinks();

    private TrademarkRuleset.SourceLinks.Template template(String key) {
        return links.templates().stream().filter(t -> t.sourceKey().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void buildsALinkFromTheTemplateOfTheSourceKey() {
        List<SourceLink> result = links.resolve(List.of("tsreg:123456", "other.src:AB-1234"));

        assertThat(result).containsExactly(
                new SourceLink("tsreg:123456", template("tsreg").label(),
                        template("tsreg").urlTemplate().replace("{id}", "123456")),
                new SourceLink("other.src:AB-1234", template("other.src").label(),
                        template("other.src").urlTemplate().replace("{id}", "AB-1234")));
    }

    @Test
    void skipsAnIdThatHasNoKeyAnUnknownKeyOrAnIdThatDoesNotMatchThePattern() {
        List<String> skipped = List.of("plain-id", "123456", ":123456", "uspto:12345678", "internal:case-1",
                "TSREG:123456", "tsreg:12345", "tsreg:1234567", "tsreg:abcdef", "other.src:ab-1234", "tsreg:");

        assertThat(links.resolve(skipped)).isEmpty();
    }

    @Test
    void keepsTheOrderOfTheIdsAndSkipsOnlyTheOnesWithoutALink() {
        List<SourceLink> result = links.resolve(List.of("internal:1", "other.src:ZZ-0001", "tsreg:000001", "x"));

        assertThat(result).extracting(SourceLink::sourceId).containsExactly("other.src:ZZ-0001", "tsreg:000001");
    }

    @Test
    void anIdCanNeverAddAPathAQueryAFragmentOrASpaceWhateverThePatternSays() {
        TrademarkRuleset anything = TestRulesets.variant("id_pattern: \"[0-9]{6}\"", "id_pattern: \".*\"");
        List<String> unsafe = List.of("tsreg:12/34", "tsreg:12#34", "tsreg:12?34", "tsreg:12 34", "tsreg:12%34",
                "tsreg:12&34", "tsreg:12\n34", "tsreg:..%2f..", "tsreg:" + "9".repeat(65));

        assertThat(anything.sourceLinks().resolve(unsafe)).isEmpty();
        assertThat(anything.sourceLinks().resolve(List.of("tsreg:12.3_4-5"))).singleElement()
                .satisfies(link -> assertThat(link.url()).endsWith("12.3_4-5?view=status"));
    }

    @Test
    void theEvidenceOfAFindingCarriesTheLinksOfTheMatchedMark() {
        TrademarkScanner scanner = scannerOf(ruleset, mark(ruleset, "Zorvex", List.of(25), "internal:7", "tsreg:123456"));

        TrademarkFinding finding = scanner.scan(TrademarkCheckInput.ofTitle("Big Zorvex Shirt")).findings().get(0);

        assertThat(finding.evidence().sourceIds()).containsExactly("internal:7", "tsreg:123456");
        assertThat(finding.evidence().sourceLinks()).singleElement().satisfies(link -> {
            assertThat(link.sourceId()).isEqualTo("tsreg:123456");
            assertThat(link.url()).isEqualTo(template("tsreg").urlTemplate().replace("{id}", "123456"));
        });
    }

    @Test
    void aFindingWithoutAMarkOrWithoutLinkableSourcesHasNoLink() {
        TrademarkScanner scanner = scannerOf(ruleset, mark(ruleset, "Zorvex", List.of(25), "internal:7"));

        TrademarkFinding catalog = scanner.scan(TrademarkCheckInput.ofTitle("Zorvex")).findings().get(0);
        TrademarkResult context = scanner.scan(TrademarkCheckInput.ofTitle(
                ScannerFixtures.place(ruleset, 0) + " " + ScannerFixtures.sport(ruleset, 0)));

        assertThat(catalog.evidence().sourceLinks()).isEmpty();
        assertThat(context.findings()).isNotEmpty().allSatisfy(f -> assertThat(f.evidence().sourceLinks()).isEmpty());
    }

    @Test
    void aRulesetPublishedBeforeSourceLinksBuildsNone() {
        TrademarkRuleset previous = TestRulesets.previous();

        assertThat(previous.sourceLinks().templates()).isEmpty();
        assertThat(previous.sourceLinks().resolve(List.of("uspto:12345678", "tsreg:123456"))).isEmpty();
    }

    @Test
    void theShippedRulesetLinksUsptoSerialNumbersToTsdr() {
        TrademarkRuleset.SourceLinks shipped = TestRulesets.shipped().sourceLinks();

        assertThat(shipped.resolve(List.of("uspto:12345678"))).singleElement().satisfies(link -> {
            assertThat(link.sourceId()).isEqualTo("uspto:12345678");
            assertThat(link.label()).isEqualTo("USPTO TSDR");
            assertThat(link.url()).startsWith("https://tsdr.uspto.gov/#caseNumber=12345678&");
        });
        // only a source named uspto, and only an eight-digit serial number
        assertThat(shipped.resolve(List.of("USPTO:12345678", "internal:12345678", "12345678", "uspto:1234567",
                "uspto:123456789", "uspto:1234567a", "uspto:"))).isEmpty();
    }
}
