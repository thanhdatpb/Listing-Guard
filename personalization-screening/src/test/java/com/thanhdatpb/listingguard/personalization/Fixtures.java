package com.thanhdatpb.listingguard.personalization;

import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRulesetLoader;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.catalog.InMemoryTrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Shared helpers: the shipped rulesets, scanners over the fictional marks of the DEMO catalog, and small inputs. */
final class Fixtures {

    static final String RULESET_PATH = "rulesets/personalization-screening-2026-11.yaml";
    static final String TRADEMARK_RULESET_PATH = "rulesets/trademark-risk-2026-11.yaml";

    /** Two of the invented marks of catalogs/demo: nobody owns them. */
    static final String MARK = "Zibblefrax";
    static final String OTHER_MARK = "Quonkleberry";

    private Fixtures() {
    }

    static PersonalizationRuleset ruleset() {
        return PersonalizationRulesetLoader.loadClasspath(RULESET_PATH);
    }

    static String rulesetText() {
        try (InputStream in = Fixtures.class.getResourceAsStream("/" + RULESET_PATH)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The shipped ruleset text with the first occurrence of {@code from} replaced, parsed. */
    static PersonalizationRuleset variant(String from, String to) {
        return PersonalizationRulesetLoader.parse(variantText(from, to));
    }

    static String variantText(String from, String to) {
        String text = rulesetText();
        int at = text.indexOf(from);
        if (at < 0) {
            throw new IllegalArgumentException("The ruleset has no text '" + from + "'");
        }
        return text.substring(0, at) + to + text.substring(at + from.length());
    }

    static TrademarkRuleset trademarkRuleset() {
        return TrademarkRulesetLoader.loadClasspath(TRADEMARK_RULESET_PATH);
    }

    static TrademarkCatalog catalog(TrademarkRuleset ruleset, String... marks) {
        return new InMemoryTrademarkCatalog("catalog-test-1", new TextNormalizer(ruleset.normalization()),
                Arrays.asList(marks));
    }

    /** A scanner on the shipped trademark ruleset and a catalog of these marks (the two fictional ones by default). */
    static TrademarkScanner scanner(String... marks) {
        TrademarkRuleset ruleset = trademarkRuleset();
        return new TrademarkScanner(ruleset, catalog(ruleset, marks.length == 0 ? new String[] {MARK, OTHER_MARK} : marks));
    }

    static PersonalizationInput input(String... texts) {
        return new PersonalizationInput(null, null, Arrays.stream(texts).map(text -> new PersonalizationInput.Value(null, text)).toList());
    }

    static PersonalizationInput profiled(String printProfile, String label, String text) {
        return new PersonalizationInput(printProfile, null, List.of(new PersonalizationInput.Value(label, text)));
    }

    /** {@code count} copies of a letter the default profile prints. */
    static String letters(int count) {
        return "A".repeat(count);
    }

    static String codePoint(int codePoint) {
        return new String(Character.toChars(codePoint));
    }
}
