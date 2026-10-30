package com.thanhdatpb.listingguard.trademark.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.trademark.TestRulesets;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextNormalizerTest {

    private final TrademarkRuleset ruleset = TestRulesets.test();
    private final TextNormalizer normalizer = new TextNormalizer(ruleset.normalization());

    private List<String> texts(String input) {
        return normalizer.tokenTexts(input);
    }

    /** The first mapping of a table, so the tests follow the ruleset instead of assuming a character. */
    private static Map.Entry<Integer, String> first(Map<Integer, String> table) {
        return table.entrySet().stream().min(Map.Entry.comparingByKey()).orElseThrow();
    }

    private static String chr(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    @Test
    void lowercasesAndSplitsOnWhitespaceAndPunctuation() {
        assertThat(texts("Zorvex, Quill-Mark & Co.")).containsExactly("zorvex", "quill", "mark", "co");
    }

    @Test
    void removesDiacriticsOfComposedAndDecomposedText() {
        String composed = Normalizer.normalize("Café Zörvex", Normalizer.Form.NFC);
        String decomposed = Normalizer.normalize("Café Zörvex", Normalizer.Form.NFD);

        assertThat(texts(composed)).containsExactly("cafe", "zorvex");
        assertThat(texts(decomposed)).containsExactly("cafe", "zorvex");
    }

    @Test
    void foldsVietnameseLettersIncludingTheOnesUnicodeDoesNotDecompose() {
        assertThat(texts("Đà Nẵng Áo Thể Thao")).containsExactly("da", "nang", "ao", "the", "thao");
    }

    @Test
    void appliesTheFoldMapOfTheRuleset() {
        Map.Entry<Integer, String> fold = first(ruleset.normalization().foldMap());

        assertThat(texts("x" + chr(fold.getKey()) + "x")).containsExactly("x" + fold.getValue() + "x");
    }

    @Test
    void mapsHomoglyphsOfTheRuleset() {
        ruleset.normalization().homoglyphs().forEach((lookAlike, letter) ->
                assertThat(texts("zorv" + chr(lookAlike) + "x")).as("U+%04X", lookAlike)
                        .containsExactly("zorv" + letter + "x"));
    }

    @Test
    void foldsFullWidthLettersAndLigatures() {
        assertThat(texts("ＺＯＲＶＥＸ")).containsExactly("zorvex");
        assertThat(texts("oﬃce")).containsExactly("office");
    }

    @Test
    void mapsLeetCharactersInsideAWord() {
        ruleset.normalization().leetMap().forEach((symbol, letter) ->
                assertThat(texts("zor" + chr(symbol) + "vex")).as("U+%04X", symbol)
                        .containsExactly("zor" + letter + "vex"));
    }

    @Test
    void leavesLeetCharactersAtTheEdgeOfAWordAlone() {
        assertThat(texts("3D printed 2XL 100% cotton 5pcs")).containsExactly("3d", "printed", "2xl", "100", "cotton", "5pcs");
        assertThat(texts("zorvex3")).containsExactly("zorvex3");
    }

    @Test
    void aLeetSymbolThatIsNotMappedSeparatesTokens() {
        assertThat(texts("wow! zorvex$ @zorvex")).containsExactly("wow", "zorvex", "zorvex");
    }

    @Test
    void leetNeedsAtLeastTheConfiguredLettersOnBothSides() {
        TrademarkRuleset strict = TestRulesets.variant("min_letters_around: 1", "min_letters_around: 2");
        TextNormalizer strictNormalizer = new TextNormalizer(strict.normalization());

        assertThat(strictNormalizer.tokenTexts("z0rvex")).containsExactly("z0rvex");
        assertThat(strictNormalizer.tokenTexts("zo0rvex")).containsExactly("zoorvex");
    }

    @Test
    void deletedCharactersJoinTheWordInsteadOfSplittingIt() {
        assertThat(texts("Zorvex's")).containsExactly("zorvexs");
        assertThat(texts("zor​vex")).containsExactly("zorvex");
        assertThat(texts("zor­vex")).containsExactly("zorvex");
    }

    @Test
    void tokenBoundariesAreLettersAndDigits() {
        assertThat(texts("abc123 def_456/ghi")).containsExactly("abc123", "def", "456", "ghi");
        assertThat(texts("   ")).isEmpty();
        assertThat(texts("")).isEmpty();
        assertThat(texts("!!! ... ---")).isEmpty();
    }

    @Test
    void tokensKeepTheirSpanInTheCanonicalText() {
        String text = "Big Zorvex Jersey";
        List<Token> tokens = normalizer.tokenize(text);

        assertThat(tokens).hasSize(3);
        assertThat(text.substring(tokens.get(1).start(), tokens.get(1).end())).isEqualTo("Zorvex");
        assertThat(tokens.get(1).index()).isEqualTo(1);
    }

    @Test
    void spansCoverTheRawCharactersOfAMappedToken() {
        String text = "buy Z0rvex now";
        Token token = normalizer.tokenize(text).get(1);

        assertThat(token.text()).isEqualTo("zorvex");
        assertThat(text.substring(token.start(), token.end())).isEqualTo("Z0rvex");
    }

    @Test
    void spansCoverLettersThatBecomeSeveralCharactersAndCombiningMarks() {
        String text = "Straße été";
        List<Token> tokens = normalizer.tokenize(text);

        assertThat(tokens.get(0).text()).isEqualTo("strasse");
        assertThat(text.substring(tokens.get(0).start(), tokens.get(0).end())).isEqualTo("Straße");
        String decomposed = "été x";
        Token mark = normalizer.tokenize(decomposed).get(0);
        assertThat(mark.text()).isEqualTo("ete");
        assertThat(decomposed.substring(mark.start(), mark.end())).isEqualTo("été");
    }

    @Test
    void spansUseUtf16OffsetsAcrossSurrogatePairs() {
        String text = "😀 zorvex";
        Token token = normalizer.tokenize(text).get(0);

        assertThat(token.start()).isEqualTo(3);
        assertThat(text.substring(token.start(), token.end())).isEqualTo("zorvex");
    }

    @Test
    void normalizeMarkJoinsTokensWithOneSpace() {
        assertThat(normalizer.normalizeMark("  Quill-Mark   Tools ")).isEqualTo("quill mark tools");
    }

    @Test
    void doesNotDedupeTruncateOrCorrect() {
        assertThat(texts("zorvex zorvex zorvx")).containsExactly("zorvex", "zorvex", "zorvx");
    }

    @Test
    void isDeterministic() {
        String text = "Z0rvеx Đà Nẵng ZORVEX's";

        assertThat(normalizer.tokenize(text)).isEqualTo(normalizer.tokenize(text));
    }

    // --- the canonical hash of the settings: what a catalog's normalization identity is made of ---

    private static String hashOf(String yaml) {
        return com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader.parse(yaml).normalization()
                .canonicalHash();
    }

    @Test
    void theCanonicalHashIsStableAndHex() {
        String hash = ruleset.normalization().canonicalHash();

        assertThat(hash).matches("[0-9a-f]{64}").isEqualTo(TestRulesets.test().normalization().canonicalHash());
    }

    @Test
    void aCommentTheOrderOfKeysAndTheQuotingDoNotChangeTheHash() {
        String base = hashOf(TestRulesets.text(TestRulesets.TEST));

        assertThat(hashOf(TestRulesets.variantText("normalization:\n", "normalization:\n  # a comment\n"))).isEqualTo(base);
        assertThat(hashOf(TestRulesets.variantText("    \"\u0111\": \"d\"\n    \"\u00F0\": \"d\"\n",
                "    \"\u00F0\": \"d\"\n    \"\u0111\": \"d\"\n"))).isEqualTo(base);
        assertThat(hashOf(TestRulesets.variantText("\"\u00F8\": \"o\"", "'\u00F8': 'o'"))).isEqualTo(base);
        assertThat(hashOf(TestRulesets.variantText("      \"0\": \"o\"\n      \"1\": \"i\"\n",
                "      \"1\": \"i\"\n      \"0\": \"o\"\n"))).isEqualTo(base);
    }

    @Test
    void theRestOfTheRulesetDoesNotChangeTheHash() {
        String base = ruleset.normalization().canonicalHash();

        assertThat(TestRulesets.variant("final_threshold: 0.80", "final_threshold: 0.9").normalization().canonicalHash())
                .isEqualTo(base);
        assertThat(TestRulesets.variant("    - Aldwick\n", "    - Aldwyck\n").normalization().canonicalHash())
                .isEqualTo(base);
        assertThat(TestRulesets.variant("version: trademark-test-1", "version: trademark-test-9").normalization()
                .canonicalHash()).isEqualTo(base);
        assertThat(TestRulesets.shipped().normalization().canonicalHash()).isEqualTo(base);
    }

    @Test
    void changingAnyNormalizationSettingChangesTheHash() {
        String base = ruleset.normalization().canonicalHash();

        for (TrademarkRuleset changed : List.of(
                TestRulesets.variant("\"\u00F8\": \"o\"", "\"\u00F8\": \"e\""),
                TestRulesets.variant("    \"\u00F8\": \"o\"\n", ""),
                TestRulesets.variant("\"0\": \"o\"", "\"0\": \"q\""),
                TestRulesets.variant("min_letters_around: 1", "min_letters_around: 2"),
                TestRulesets.variant("    delete_chars:\n", "    delete_chars:\n      - \"-\"\n"),
                TestRulesets.variant("\"а\": \"a\"", "\"а\": \"b\""))) {
            assertThat(changed.normalization().canonicalHash()).isNotEqualTo(base);
        }
    }
}
