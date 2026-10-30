package com.thanhdatpb.listingguard.trademark;

import com.thanhdatpb.listingguard.trademark.catalog.InMemoryTrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.util.Arrays;
import java.util.List;

/** Shared helpers: scanners over synthetic marks, and values read from the test ruleset. */
final class ScannerFixtures {

    static final String CATALOG_VERSION = "catalog-test-1";

    private ScannerFixtures() {
    }

    static TrademarkCatalog catalog(TrademarkRuleset ruleset, String... marks) {
        return new InMemoryTrademarkCatalog(CATALOG_VERSION, new TextNormalizer(ruleset.normalization()),
                Arrays.asList(marks));
    }

    static TrademarkScanner scanner(TrademarkRuleset ruleset, String... marks) {
        return new TrademarkScanner(ruleset, catalog(ruleset, marks));
    }

    static TrademarkScanner scanner(String... marks) {
        return scanner(TestRulesets.test(), marks);
    }

    static TrademarkResult scanTitle(TrademarkScanner scanner, String title) {
        return scanner.scan(TrademarkCheckInput.ofTitle(title));
    }

    static List<String> ruleIds(TrademarkResult result) {
        return result.findings().stream().map(TrademarkFinding::ruleId).toList();
    }

    /** The i-th place of the ruleset as text. */
    static String place(TrademarkRuleset ruleset, int index) {
        return String.join(" ", ruleset.lexicon().places().get(index));
    }

    /** The i-th sport term of the ruleset as text. */
    static String sport(TrademarkRuleset ruleset, int index) {
        return String.join(" ", ruleset.lexicon().sportTerms().get(index));
    }

    static String color(TrademarkRuleset ruleset, int index) {
        return ruleset.lexicon().colorWords().stream().sorted().toList().get(index);
    }

    static String pattern(TrademarkRuleset ruleset, int index) {
        return ruleset.lexicon().patternWords().stream().sorted().toList().get(index);
    }

    /** Words that are in no list of the ruleset, to put distance between two terms. */
    static String filler(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append(" qzx").append((char) ('a' + i));
        }
        return out.toString();
    }
}
