package com.thanhdatpb.listingguard.trademark;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.trademark.config.RuleIds;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.watchlist.Watchlist;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistEntry;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistMatch;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The client's own watchlist in the trademark scan (ADR-031). Every name is invented. */
class WatchlistMatchingTest {

    private static final WatchlistSettings SETTINGS = WatchlistSettings.loadClasspath(WatchlistSettings.SHIPPED);

    private static final WatchlistEntry OTTERS = new WatchlistEntry("a-otters", "Brimwick Otters", List.of("Otterz", "BWO"),
            List.of(25), "12345678", "từng bị claim");
    private static final WatchlistEntry SLOGAN = new WatchlistEntry("b-slogan", "Paddle Hard Brimwick Never Quits", List.of(),
            List.of(), null, null);

    private final TrademarkRuleset ruleset = TestRulesets.shipped();
    private final TrademarkScanner scanner = ScannerFixtures.scanner(ruleset, "Zorvex");
    private final Watchlist watchlist = new Watchlist(SETTINGS, List.of(OTTERS, SLOGAN));

    private TrademarkResult scan(String title) {
        return scanner.scan(TrademarkCheckInput.ofTitle(title), watchlist);
    }

    private static List<TrademarkFinding> watchlistFindings(TrademarkResult result) {
        return result.findings().stream().filter(f -> f.source() == FindingSource.WATCHLIST).toList();
    }

    @Test
    void theMarkIsFoundWithItsSourceLevelAndEntry() {
        TrademarkResult result = scan("Brimwick Otters fan tee");

        assertThat(result.findings()).hasSize(1);
        TrademarkFinding finding = result.findings().get(0);
        assertThat(finding.ruleId()).isEqualTo(RuleIds.WATCHLIST_MATCH);
        assertThat(finding.source()).isEqualTo(FindingSource.WATCHLIST);
        assertThat(finding.risk()).isEqualTo(SETTINGS.minimumRisk());
        assertThat(result.riskLevel()).isEqualTo(SETTINGS.minimumRisk());
        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
        Evidence evidence = finding.evidence();
        assertThat(evidence.field()).isEqualTo("title");
        assertThat("Brimwick Otters fan tee".substring(evidence.start(), evidence.end())).isEqualTo("Brimwick Otters");
        assertThat(evidence.matchType()).isEqualTo(MatchType.PHRASE);
        assertThat(evidence.matchedMark()).isEqualTo("Brimwick Otters");
        assertThat(evidence.watchlist()).isEqualTo(new WatchlistMatch("a-otters", "Brimwick Otters", null, List.of(25),
                "12345678", "từng bị claim"));
        assertThat(evidence.sourceIds()).containsExactly("uspto:12345678");
        assertThat(evidence.sourceLinks()).singleElement().satisfies(link -> assertThat(link.url()).contains("12345678"));
        assertThat(finding.message()).contains("Brimwick Otters").contains("title").contains("từng bị claim");
    }

    @Test
    void textWithoutAListedFormHasNoWatchlistFinding() {
        TrademarkResult result = scan("Brimwick river otters plush");

        assertThat(watchlistFindings(result)).isEmpty();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void aWordInsideAnotherWordIsNotAMatch() {
        assertThat(watchlistFindings(scan("BWOsome Brimwickotters"))).isEmpty();
    }

    @Test
    void anAliasMatchesAndTheEvidenceNamesIt() {
        TrademarkResult result = scan("Retro otterz hoodie");

        TrademarkFinding finding = watchlistFindings(result).get(0);
        assertThat(finding.evidence().matchType()).isEqualTo(MatchType.EXACT);
        assertThat(finding.evidence().watchlist().matchedAlias()).isEqualTo("Otterz");
        assertThat(finding.evidence().watchlist().mark()).isEqualTo("Brimwick Otters");
        assertThat(finding.message()).contains("Otterz");
    }

    @Test
    void caseAccentsAndPunctuationDoNotHideAMark() {
        for (String title : List.of("BRIMWICK OTTERS tee", "brimwick-otters!", "Brímwick, Otters", "brim'wick otters")) {
            assertThat(watchlistFindings(scan(title))).as(title).hasSize(1);
        }
    }

    @Test
    void aSloganLongerThanTheCatalogNgramLimitIsFound() {
        assertThat(SLOGAN.mark().split(" ").length).isGreaterThan(ruleset.matching().maxNgramTokens());

        TrademarkResult result = scan("Mug: paddle hard, Brimwick never quits!");

        assertThat(watchlistFindings(result)).singleElement()
                .satisfies(f -> assertThat(f.evidence().watchlist().entryId()).isEqualTo("b-slogan"));
    }

    @Test
    void everyScannedFieldIsRead() {
        TrademarkCheckInput input = new TrademarkCheckInput("Plain tee", List.of("gift", "bwo"), List.of(), null, null, null,
                null, null, null);

        TrademarkResult result = scanner.scan(input, watchlist);

        assertThat(watchlistFindings(result)).singleElement()
                .satisfies(f -> assertThat(f.evidence().field()).isEqualTo("tags[1]"));
    }

    @Test
    void theWatchlistWinsASpanItSharesWithACatalogMark() {
        TrademarkScanner withMark = ScannerFixtures.scanner(ruleset, "Otterz");

        TrademarkResult result = withMark.scan(TrademarkCheckInput.ofTitle("Otterz tee"), watchlist);

        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo(RuleIds.WATCHLIST_MATCH);
            assertThat(finding.evidence().otherRuleIds()).containsExactly(RuleIds.CATALOG_EXACT);
        });
    }

    @Test
    void aLowerMinimumIsRaisedToTheLevelOfAnOverlappingCatalogMatch() {
        WatchlistSettings medium = WatchlistSettings.parse(text().replace("minimum_risk: HIGH", "minimum_risk: MEDIUM"));
        TrademarkScanner withMark = ScannerFixtures.scanner(ruleset, "Otterz");

        TrademarkResult alone = scanner.scan(TrademarkCheckInput.ofTitle("Otterz tee"), new Watchlist(medium, List.of(OTTERS)));
        TrademarkResult overlapping = withMark.scan(TrademarkCheckInput.ofTitle("Otterz tee"),
                new Watchlist(medium, List.of(OTTERS)));

        assertThat(alone.findings().get(0).risk()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(overlapping.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo(RuleIds.WATCHLIST_MATCH);
            assertThat(finding.risk()).isEqualTo(ruleset.catalogExact().risk());
        });
    }

    @Test
    void anEmptyWatchlistScansExactlyAsBefore() {
        TrademarkCheckInput input = TrademarkCheckInput.ofTitle("Zorvex Brimwick Otters tee");

        assertThat(scanner.scan(input, Watchlist.EMPTY)).isEqualTo(scanner.scan(input));
        assertThat(Watchlist.EMPTY.fingerprintComponents()).isEmpty();
    }

    @Test
    void withoutACatalogTheWatchlistStillRunsAndTheResultSaysItIsIncomplete() {
        TrademarkScanner noCatalog = TrademarkScanner.withoutCatalog(ruleset);

        TrademarkResult found = noCatalog.scan(TrademarkCheckInput.ofTitle("Zorvex Brimwick Otters tee"), watchlist);
        TrademarkResult nothing = noCatalog.scan(TrademarkCheckInput.ofTitle("Zorvex plain tee"), watchlist);

        assertThat(found.catalogVersion()).isNull();
        assertThat(found.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(found.failedRuleIds()).isEmpty();
        assertThat(found.skippedRuleIds()).containsAll(RuleIds.CATALOG)
                .contains(RuleIds.COLOR_NAME_CONTAINS_MARK, RuleIds.PURE_COLOR_DESCRIPTION)
                .doesNotContain(RuleIds.GEO_SPORT_COMBINATION);
        assertThat(found.findings()).extracting(TrademarkFinding::ruleId).containsExactly(RuleIds.WATCHLIST_MATCH);
        assertThat(found.riskLevel()).isEqualTo(SETTINGS.minimumRisk());
        assertThat(nothing.findings()).isEmpty();
        assertThat(nothing.riskLevel()).isEqualTo(RiskLevel.UNKNOWN);
    }

    @Test
    void theHashIgnoresOrderButNotContent() {
        Watchlist reversed = new Watchlist(SETTINGS, List.of(SLOGAN, OTTERS));
        Watchlist otherNote = new Watchlist(SETTINGS, List.of(SLOGAN, new WatchlistEntry(OTTERS.id(), OTTERS.mark(),
                OTTERS.aliases(), OTTERS.niceClasses(), OTTERS.serialNumber(), "khác")));

        assertThat(reversed.contentHash()).isEqualTo(watchlist.contentHash());
        assertThat(otherNote.contentHash()).isNotEqualTo(watchlist.contentHash());
        assertThat(watchlist.fingerprintComponents()).containsOnlyKeys("watchlist_sha256", "watchlist_settings",
                "watchlist_matcher");
        assertThat(scanner.fingerprint(watchlist.fingerprintComponents()))
                .isNotEqualTo(scanner.fingerprint(otherNote.fingerprintComponents()));
    }

    private static String text() {
        return TestRulesets.text(WatchlistSettings.SHIPPED);
    }
}
