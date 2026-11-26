package com.thanhdatpb.listingguard.trademark;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.trademark.PhraseScreening.Input;
import com.thanhdatpb.listingguard.trademark.PhraseScreening.PhraseFinding;
import com.thanhdatpb.listingguard.trademark.PhraseScreening.PhraseRow;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.InMemoryTrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.watchlist.Watchlist;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PhraseScreeningTest {
    private final com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset ruleset =
            TrademarkRulesetLoader.loadClasspath("rulesets/trademark-risk-2026-12.yaml");

    @Test
    void matchesTheSequentialPipelineAndMergesIdenticalForms() {
        var catalog = new CountingCatalog(new InMemoryTrademarkCatalog("test", List.of(
                new CatalogMark("zorvex", "Zorvex"), new CatalogMark("crimson falcons", "Crimson Falcons"))));
        var scanner = new TrademarkScanner(ruleset, catalog);
        var inputs = List.of(new Input("Zorvex", null), new Input("ZORVEX!", 20L), new Input("Crimson Falcons", null),
                new Input("Northvale Baseball", null), new Input("Zorvexx", 14L), new Input(" ", null),
                new Input("a".repeat(201), 42L));
        var batch = PhraseScreening.screen(scanner, inputs, null, Watchlist.EMPTY, 200);

        assertThat(catalog.exactCalls).isEqualTo(1);
        assertThat(catalog.fuzzyCalls).isEqualTo(1);
        assertThat(batch.summary().duplicates()).isEqualTo(1);
        assertThat(batch.summary().dropped()).isEqualTo(2);
        assertThat(batch.phrases()).extracting(PhraseRow::text).containsExactly(
                "Zorvex", "Crimson Falcons", "Northvale Baseball", "Zorvexx");
        assertThat(batch.phrases().getFirst().searchVolume()).isEqualTo(20);
        for (PhraseRow row : batch.phrases()) {
            var sequential = scanner.scan(new TrademarkCheckInput(null, List.of(row.text()), null, null, null, null));
            assertThat(row.completeness()).isEqualTo(sequential.completeness());
            assertThat(row.findings()).hasSize(sequential.findings().size());
            for (int i = 0; i < row.findings().size(); i++) {
                var match = row.findings().get(i);
                var expected = sequential.findings().get(i);
                assertThat(match.risk()).isEqualTo(expected.risk());
                assertThat(match.ruleId()).isEqualTo(expected.ruleId());
                assertThat(match.mark()).isEqualTo(expected.evidence().matchedMark());
                assertThat(match.riskAdjustments()).isEqualTo(expected.evidence().riskAdjustments());
                assertThat(match.matchType()).isEqualTo(expected.evidence().matchType());
                assertThat(match.field()).isEqualTo("phrase");
            }
        }
        assertThat(batch.phrases().getLast().findings()).extracting(PhraseFinding::matchType).contains(MatchType.FUZZY);
    }

    @Test
    void choosesFuzzyPhrasesByVolumeThenInputOrder() {
        var scanner = new TrademarkScanner(ruleset, new InMemoryTrademarkCatalog("test", List.<CatalogMark>of()));
        var result = PhraseScreening.screen(scanner, List.of(new Input("fabric", null), new Input("cotton", 50L),
                new Input("linen", 50L), new Input("thread", null)), null, Watchlist.EMPTY, 1);
        assertThat(result.phrases()).extracting(PhraseRow::fuzzySkipped).containsExactly(true, false, true, true);
        assertThat(result.summary().fuzzySkipped()).isEqualTo(3);
        assertThat(result.phrases().getFirst().completeness()).isEqualTo(Completeness.INCOMPLETE);
        var noVolume = PhraseScreening.screen(scanner, List.of(new Input("fabric", null), new Input("cotton", null)),
                null, Watchlist.EMPTY, 1);
        assertThat(noVolume.phrases()).extracting(PhraseRow::fuzzySkipped).containsExactly(false, true);
    }

    @Test
    void batchesOneThousandPhrasesAgainstTwoHundredThousandMarks() {
        // The 199 selected short numeric phrases are below the ruleset's fuzzy length limit. One form exercises the
        // real in-memory fuzzy scan. Exact forms and non-selected rows still run against the full synthetic catalog.
        var marks = IntStream.range(0, 200_000).mapToObj(i -> new CatalogMark("fictitious" + i, "fictitious" + i)).toList();
        var catalog = new CountingCatalog(new InMemoryTrademarkCatalog("large-synthetic", marks));
        var scanner = new TrademarkScanner(ruleset, catalog);
        var inputs = IntStream.range(0, 1_000).mapToObj(i -> new Input(
                i == 0 ? "tangerine" : Integer.toString(i + 1000), null)).toList();

        long started = System.nanoTime();
        var result = PhraseScreening.screen(scanner, inputs, null, Watchlist.EMPTY, 200);
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("phrase-synthetic marks=%d phrases=%d exactCalls=%d fuzzyCalls=%d fuzzyForms=%d elapsedMillis=%d%n",
                marks.size(), inputs.size(), catalog.exactCalls, catalog.fuzzyCalls, catalog.fuzzyForms, millis);
        assertThat(result.phrases()).hasSize(1_000);
        assertThat(result.summary().fuzzySkipped()).isEqualTo(800);
        assertThat(catalog.exactCalls).isEqualTo(1);
        assertThat(catalog.fuzzyCalls).isEqualTo(1);
    }

    private static final class CountingCatalog implements TrademarkCatalog {
        private final TrademarkCatalog delegate;
        private int exactCalls;
        private int fuzzyCalls;
        private int fuzzyForms;

        CountingCatalog(TrademarkCatalog delegate) {
            this.delegate = delegate;
        }

        @Override public String version() { return delegate.version(); }
        @Override public boolean mayHaveDistinctiveness() { return delegate.mayHaveDistinctiveness(); }

        @Override
        public Map<String, CatalogMark> findExact(Collection<String> forms) {
            exactCalls++;
            return delegate.findExact(forms);
        }

        @Override
        public Map<String, List<CatalogMark>> fuzzyCandidates(Collection<String> forms, double threshold) {
            fuzzyCalls++;
            fuzzyForms += forms.size();
            return delegate.fuzzyCandidates(forms, threshold);
        }
    }
}
