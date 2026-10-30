package com.thanhdatpb.listingguard.rag.analysis;

import static com.thanhdatpb.listingguard.rag.analysis.AnalysisFixtures.LISTING;
import static com.thanhdatpb.listingguard.rag.analysis.AnalysisFixtures.MIN_QUOTE;
import static com.thanhdatpb.listingguard.rag.analysis.AnalysisFixtures.MOCKUP;
import static com.thanhdatpb.listingguard.rag.analysis.AnalysisFixtures.PARTNER;
import static com.thanhdatpb.listingguard.rag.analysis.AnalysisFixtures.RETRIEVED;
import static com.thanhdatpb.listingguard.rag.analysis.AnalysisFixtures.parsed;
import static com.thanhdatpb.listingguard.rag.analysis.AnalysisFixtures.proposed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CitationVerifierTest {

    private final CitationVerifier verifier = new CitationVerifier(MIN_QUOTE);

    /** name, chunkId, quote, field, value, the reason it is dropped (null = kept) */
    static Stream<Arguments> cases() {
        String partnerLine = "If a production partner helps make";
        return Stream.of(
                Arguments.of("verbatim quote and value", 1L, partnerLine, "description", "print partner", null),
                Arguments.of("value may be null (an omission)", 1L, partnerLine, "description", null, null),

                // normalization of the quote, both sides
                Arguments.of("NBSP in the chunk, plain space in the quote", 1L,
                        "make your items, you must say so", "description", null, null),
                Arguments.of("newline in the chunk, space in the quote", 1L,
                        "sourced by you. If a production partner", "description", null, null),
                Arguments.of("runs of spaces and a newline in the quote", 1L,
                        "  sourced by you.\n\n  If   a production partner ", "description", null, null),
                Arguments.of("curly quotes and dashes in the chunk, straight in the quote", 1L,
                        "you must say so - \"clearly\" - in the listing", "description", null, null),
                Arguments.of("curly quotes and dashes in the quote, straight in the chunk", 3L,
                        "Don’t use “AI” labels – they are misleading", "description", null, null),
                Arguments.of("em dash in the quote, hyphen in the chunk", 3L,
                        "\"AI\" labels — they are misleading", "description", null, null),
                Arguments.of("NFKC: ligature in the chunk, letters in the quote", 2L,
                        "Mockups are not allowed for finished goods", "description", null, null),
                Arguments.of("NFKC: full-width letters in the quote", 2L,
                        "Ｍockups are not allowed for finished goods", "description", null, null),

                // unknown_chunk
                Arguments.of("chunk id that does not exist", 999L, partnerLine, "description", null,
                        DropReason.UNKNOWN_CHUNK),
                Arguments.of("chunk id that exists elsewhere but was not retrieved", 4L, partnerLine,
                        "description", null, DropReason.UNKNOWN_CHUNK),

                // quote_length: too short, or an ellipsis
                Arguments.of("quote shorter than the minimum", 2L, "Mockups are not", "description", null,
                        DropReason.QUOTE_LENGTH),
                Arguments.of("quote short once whitespace is collapsed", 2L, "Mockups     are   not   a ",
                        "description", null, DropReason.QUOTE_LENGTH),
                Arguments.of("quote with an ellipsis character", 2L, "Mockups are not allowed…",
                        "description", null, DropReason.QUOTE_LENGTH),
                Arguments.of("quote with three dots", 2L, "Listing photos must show...buyer will receive.",
                        "description", null, DropReason.QUOTE_LENGTH),

                // quote_not_found
                Arguments.of("quote taken from another chunk", 1L, "Mockups are not allowed for finished goods",
                        "description", null, DropReason.QUOTE_NOT_FOUND),
                Arguments.of("case differs", 2L, "mockups are not allowed for finished goods", "description", null,
                        DropReason.QUOTE_NOT_FOUND),
                Arguments.of("one word changed", 2L, "Mockups are never allowed for finished goods", "description",
                        null, DropReason.QUOTE_NOT_FOUND),
                Arguments.of("paraphrase", 2L, "you cannot use mockup photos for finished items", "description", null,
                        DropReason.QUOTE_NOT_FOUND),

                // value_not_found
                Arguments.of("value not in the field", 1L, partnerLine, "description", "AI generated",
                        DropReason.VALUE_NOT_FOUND),
                Arguments.of("value only in another field", 1L, partnerLine, "title", "print partner",
                        DropReason.VALUE_NOT_FOUND),
                Arguments.of("field the listing does not have", 1L, partnerLine, "who_made", "i_did",
                        DropReason.VALUE_NOT_FOUND),
                Arguments.of("value differs by case", 1L, partnerLine, "description", "Print Partner",
                        DropReason.VALUE_NOT_FOUND),
                Arguments.of("value spans two tags", 1L, partnerLine, "tags", "mug sunset",
                        DropReason.VALUE_NOT_FOUND),
                Arguments.of("value within one tag", 1L, partnerLine, "tags", "sunset gift", null),
                Arguments.of("value with NBSP, listing with a space", 1L, partnerLine, "description",
                        "print partner", null),
                Arguments.of("value across the newline in the description", 1L, partnerLine, "description",
                        "partner. Made by me!", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void keepsOrDropsForTheFirstFailingReason(
            String name, long chunkId, String quote, String field, String value, DropReason expected) {
        VerificationResult result =
                verifier.verify(parsed(proposed(chunkId, quote, field, value)), RETRIEVED, LISTING);

        VerificationStats stats = result.stats();
        assertThat(stats.proposed()).isEqualTo(1);
        if (expected == null) {
            assertThat(result.findings()).hasSize(1);
            assertThat(stats.kept()).isEqualTo(1);
            assertThat(stats.totalDropped()).isZero();
        } else {
            assertThat(result.findings()).isEmpty();
            assertThat(stats.kept()).isZero();
            assertThat(stats.dropped(expected)).isEqualTo(1);
            assertThat(stats.totalDropped()).isEqualTo(1);
        }
    }

    @Test
    void aKeptFindingCopiesTheChunkLocationAndKeepsTheQuoteAsWritten() {
        ProposedFinding p = proposed(2, "Mockups  are not allowed for ﬁnished goods", "description", null);

        PolicyFinding f = verifier.verify(parsed(p), RETRIEVED, LISTING).findings().get(0);

        assertThat(f.citation()).isEqualTo(new PolicyFinding.Citation(
                MOCKUP.sourceFile(), MOCKUP.sectionPath(), MOCKUP.url(), MOCKUP.version(), p.quote()));
        assertThat(f.ruleId()).isEqualTo(p.ruleId());
        assertThat(f.field()).isEqualTo("description");
        assertThat(f.value()).isNull();
        assertThat(f.message()).isEqualTo(p.message());
        assertThat(f.suggestion()).isEqualTo(p.suggestion());
    }

    @Test
    void errorIsLoweredToWarningAndCountedOnlyWhenKept() {
        String quote = "If a production partner helps make";
        ProposedFinding error = withSeverity(proposed(1, quote, "description", null), Severity.ERROR);
        ProposedFinding warning = withSeverity(proposed(1, quote, "description", null), Severity.WARNING);
        ProposedFinding info = withSeverity(proposed(1, quote, "description", null), Severity.INFO);
        ProposedFinding droppedError = withSeverity(proposed(999, quote, "description", null), Severity.ERROR);

        VerificationResult result = verifier.verify(parsed(error, warning, info, droppedError), RETRIEVED, LISTING);

        assertThat(result.findings()).extracting(PolicyFinding::severity)
                .containsExactly(Severity.WARNING, Severity.WARNING, Severity.INFO);
        assertThat(result.stats().severityClamped()).isEqualTo(1);
        assertThat(result.stats().kept()).isEqualTo(3);
        assertThat(result.stats().dropped(DropReason.UNKNOWN_CHUNK)).isEqualTo(1);
    }

    @Test
    void schemaInvalidItemsFromTheParserAreCountedAsProposedAndDropped() {
        ParseResult parse = new ParseResult(
                List.of(proposed(1, "If a production partner helps make", "description", null)), 4);

        VerificationStats stats = verifier.verify(parse, RETRIEVED, LISTING).stats();

        assertThat(stats.proposed()).isEqualTo(4);
        assertThat(stats.kept()).isEqualTo(1);
        assertThat(stats.dropped(DropReason.SCHEMA_INVALID)).isEqualTo(3);
        assertThat(stats.dropped()).containsOnlyKeys(DropReason.values());
    }

    @Test
    void countsEveryReasonAndKeepsTheModelsOrder() {
        ParseResult parse = new ParseResult(List.of(
                proposed(1, "If a production partner helps make", "description", "print partner"),
                proposed(999, "If a production partner helps make", "description", null),
                proposed(2, "Mockups are not", "description", null),
                proposed(1, "Mockups are not allowed for finished goods", "description", null),
                proposed(1, "If a production partner helps make", "description", "nowhere"),
                proposed(2, "Mockups are not allowed for finished goods", "title", null)), 7);

        VerificationResult result = verifier.verify(parse, RETRIEVED, LISTING);

        VerificationStats stats = result.stats();
        assertThat(stats.proposed()).isEqualTo(7);
        assertThat(stats.kept()).isEqualTo(2);
        assertThat(stats.dropped()).containsExactlyInAnyOrderEntriesOf(Map.of(
                DropReason.UNKNOWN_CHUNK, 1,
                DropReason.QUOTE_LENGTH, 1,
                DropReason.QUOTE_NOT_FOUND, 1,
                DropReason.VALUE_NOT_FOUND, 1,
                DropReason.SCHEMA_INVALID, 1));
        assertThat(result.findings()).extracting(f -> f.citation().sourceFile())
                .containsExactly(PARTNER.sourceFile(), MOCKUP.sourceFile());
    }

    @Test
    void aDuplicateChunkIdInTheRetrievedSetUsesTheFirstOne() {
        var other = AnalysisFixtures.chunk(1, "other.html", "Other > Section", "Completely different text here.");

        VerificationResult result = verifier.verify(
                parsed(proposed(1, "If a production partner helps make", "description", null)),
                List.of(PARTNER, other), LISTING);

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).citation().sourceFile()).isEqualTo(PARTNER.sourceFile());
    }

    @Test
    void noRetrievedChunksDropsEverything() {
        VerificationResult result = verifier.verify(
                parsed(proposed(1, "If a production partner helps make", "description", null)), List.of(), LISTING);

        assertThat(result.findings()).isEmpty();
        assertThat(result.stats().dropped(DropReason.UNKNOWN_CHUNK)).isEqualTo(1);
    }

    @Test
    void theMinimumQuoteLengthIsAParameter() {
        ProposedFinding p = proposed(2, "Mockups are not", "description", null);

        assertThat(new CitationVerifier(15).verify(parsed(p), RETRIEVED, LISTING).findings()).hasSize(1);
        assertThat(new CitationVerifier(16).verify(parsed(p), RETRIEVED, LISTING).findings()).isEmpty();
        assertThatThrownBy(() -> new CitationVerifier(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankValueCannotBeProvedBySubstringOfAnything() {
        VerificationResult result = verifier.verify(
                parsed(proposed(1, "If a production partner helps make", "description", "  ")),
                RETRIEVED, Map.of("description", List.of("anything")));

        assertThat(result.stats().dropped(DropReason.VALUE_NOT_FOUND)).isEqualTo(1);
    }

    private static ProposedFinding withSeverity(ProposedFinding p, Severity severity) {
        return new ProposedFinding(p.ruleId(), severity, p.field(), p.value(), p.message(), p.suggestion(),
                p.chunkId(), p.quote());
    }
}
