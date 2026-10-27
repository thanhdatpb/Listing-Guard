package com.thanhdatpb.listingguard.rag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.rag.policy.PolicyParseException;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever.RetrievedChunk;
import org.junit.jupiter.api.Test;

class EvalSetTest {

    private static final String VALID = """
            marketplace: etsy
            queries:
              - id: counterfeit
                en: "Can I sell counterfeit items?"
                vi: "Tôi có được bán hàng giả không?"
                expected:
                  - file: ip.html
                  - file: seller.html
                    section: "1. Selling Basics > b. What Can't be Sold on Etsy"
            """;

    private static RetrievedChunk chunk(String file, String title, String sectionPath) {
        return new RetrievedChunk(1, 0.5, "ETSY", title, file, sectionPath, "https://example.com/", "v", "text");
    }

    @Test
    void parsesQuestionsInEveryLanguageAndExpectations() {
        EvalSet set = EvalSet.parse(VALID);

        assertThat(set.marketplace()).isEqualTo("ETSY");
        assertThat(set.queries()).hasSize(1);
        EvalSet.Query query = set.queries().get(0);
        assertThat(query.id()).isEqualTo("counterfeit");
        assertThat(query.questions()).containsEntry("en", "Can I sell counterfeit items?")
                .containsEntry("vi", "Tôi có được bán hàng giả không?");
        assertThat(query.expected()).extracting(EvalSet.Expected::file).containsExactly("ip.html", "seller.html");
        assertThat(query.expected().get(0).section()).isNull();
    }

    @Test
    void aDocumentLevelExpectationMatchesAnyChunkOfThatDocument() {
        EvalSet.Expected expected = new EvalSet.Expected("ip.html", null);

        assertThat(expected.matches(chunk("ip.html", "IP Policy", "IP Policy"))).isTrue();
        assertThat(expected.matches(chunk("ip.html", "IP Policy", "IP Policy > 3. Counter Notice"))).isTrue();
        assertThat(expected.matches(chunk("other.html", "Other", "Other > 3. Counter Notice"))).isFalse();
    }

    @Test
    void aSectionExpectationMatchesTheStartOfTheHeadingPathWithoutTheDocumentTitle() {
        EvalSet.Expected expected = new EvalSet.Expected("seller.html", "1. Selling Basics > b. What Can't be Sold");

        assertThat(expected.matches(chunk("seller.html", "Seller Policy",
                "Seller Policy > 1. Selling Basics > b. What Can't be Sold on Etsy"))).isTrue();
        assertThat(expected.matches(chunk("seller.html", "Seller Policy",
                "Seller Policy > 1. Selling Basics > a. What can be sold on Etsy"))).isFalse();
        assertThat(expected.matches(chunk("seller.html", "Seller Policy", "Seller Policy"))).isFalse();
    }

    @Test
    void anEmptySectionMeansTextBeforeTheFirstHeadingOrAnything() {
        EvalSet.Expected root = new EvalSet.Expected("mature.html", "");

        assertThat(root.matches(chunk("mature.html", "Mature", "Mature"))).isTrue();
        assertThat(root.matches(chunk("mature.html", "Mature", "Mature > 1. Nudity"))).isTrue();
    }

    @Test
    void relevanceIsAnyOfTheExpectedEntries() {
        EvalSet.Query query = EvalSet.parse(VALID).queries().get(0);

        assertThat(query.isRelevant(chunk("ip.html", "IP", "IP > x"))).isTrue();
        assertThat(query.isRelevant(chunk("seller.html", "Seller", "Seller > 1. Selling Basics > b. What Can't be Sold on Etsy"))).isTrue();
        assertThat(query.isRelevant(chunk("seller.html", "Seller", "Seller > 2. Community"))).isFalse();
    }

    @Test
    void everyLanguageIsRequired() {
        String withoutVi = VALID.replace("    vi: \"Tôi có được bán hàng giả không?\"\n", "");
        String withoutEn = VALID.replace("    en: \"Can I sell counterfeit items?\"\n", "");
        assertThat(withoutVi).isNotEqualTo(VALID);
        assertThat(withoutEn).isNotEqualTo(VALID);
        assertThatThrownBy(() -> EvalSet.parse(withoutVi)).isInstanceOf(PolicyParseException.class).hasMessageContaining("'vi'");
        assertThatThrownBy(() -> EvalSet.parse(withoutEn)).isInstanceOf(PolicyParseException.class).hasMessageContaining("'en'");
    }

    @Test
    void rejectsMissingExpectedUnknownKeysAndDuplicateIds() {
        assertThatThrownBy(() -> EvalSet.parse(VALID.substring(0, VALID.indexOf("    expected:"))))
                .isInstanceOf(PolicyParseException.class).hasMessageContaining("expected");
        assertThatThrownBy(() -> EvalSet.parse(VALID + "    note: x\n"))
                .isInstanceOf(PolicyParseException.class);
        String twice = VALID + VALID.substring(VALID.indexOf("  - id"));
        assertThatThrownBy(() -> EvalSet.parse(twice)).isInstanceOf(PolicyParseException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> EvalSet.parse("marketplace: ETSY\nqueries: []\n")).isInstanceOf(PolicyParseException.class);
    }
}
