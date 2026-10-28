package com.thanhdatpb.listingguard.ruleengine.amazon;

import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.amazon.AmazonTestData.withSearchTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchTermsLengthRuleTest {

    private final SearchTermsLengthRule rule = new SearchTermsLengthRule();
    private final int max = limit("search_terms_length", "max_bytes");

    private List<Finding> run(String searchTerms) {
        return rule.evaluate(withSearchTerms(searchTerms), CONFIG);
    }

    @Test
    void termsOfExactlyTheMaximumBytesAreValid() {
        assertThat(run("a".repeat(max))).isEmpty();
    }

    @Test
    void termsOneByteOverAreReported() {
        List<Finding> findings = run("a".repeat(max + 1));

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("search_terms_length");
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("search_terms");
        assertThat(finding.value()).isEqualTo("a".repeat(max + 1));
        assertThat(finding.message()).isEqualTo("Search terms dài " + (max + 1) + " byte (UTF-8), tối đa " + max + " byte.");
        assertThat(finding.suggestion()).startsWith("Rút ngắn search terms ít nhất 1 byte.");
    }

    @Test
    void suggestionSaysHowManyBytesToCut() {
        assertThat(run("a".repeat(max + 20)).get(0).suggestion()).contains("ít nhất 20 byte");
    }

    @Test
    void bytesAreCountedNotCharacters() {
        String accented = "é".repeat(max / 2 + 1);

        assertThat(Texts.length(accented)).isLessThan(max);
        List<Finding> findings = run(accented);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).message()).contains("dài " + (2 * (max / 2 + 1)) + " byte");
    }

    @Test
    void multiByteCharactersAtTheBoundary() {
        // 2 bytes each: an even maximum is filled exactly, an odd one is filled by one ASCII byte more
        String filled = "é".repeat(max / 2) + "a".repeat(max % 2);

        assertThat(Texts.utf8Bytes(filled)).isEqualTo(max);
        assertThat(run(filled)).isEmpty();
        assertThat(run(filled + "a")).hasSize(1);
    }

    @Test
    void threeAndFourByteCharactersCountFully() {
        assertThat(run("ạ".repeat(max / 3 + 1))).hasSize(1);
        assertThat(run("😀".repeat(max / 4 + 1))).hasSize(1);
        assertThat(run("😀".repeat(max / 4))).isEmpty();
    }

    @Test
    void missingTermsHaveNoFindings() {
        assertThat(run(null)).isEmpty();
    }
}
