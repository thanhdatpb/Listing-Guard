package com.thanhdatpb.listingguard.api.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.console.ConsoleVendorResources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The Java side of finding feedback (ADR-034) equals the database side of V37: the enums, the packs and the note limit are the
 * {@code CHECK} constraints, so a value added on one side cannot be refused by the other. Also the note rules, the address, and the
 * pinned htmx file. Runs from the module directory, as surefire does.
 */
class FeedbackModelTest {

    private static final Path V37 = Path.of("src/main/resources/db/migration/V37__finding_verdict.sql");

    private static String migration() throws IOException {
        return Files.readString(V37, StandardCharsets.UTF_8);
    }

    private static List<String> constraintValues(String constraint) throws IOException {
        Matcher matcher = Pattern.compile(constraint + " CHECK \\(\\w+ IN \\(([^)]*)\\)\\)").matcher(migration());
        assertThat(matcher.find()).as(constraint).isTrue();
        return Arrays.stream(matcher.group(1).split(",")).map(value -> value.trim().replace("'", "")).toList();
    }

    @Test
    void theEnumsAndThePacksAreTheConstraintsOfV37() throws IOException {
        assertThat(constraintValues("ck_finding_verdict_verdict"))
                .containsExactlyElementsOf(Arrays.stream(FeedbackVerdict.values()).map(Enum::name).toList());
        assertThat(constraintValues("ck_finding_verdict_source"))
                .containsExactlyElementsOf(Arrays.stream(FeedbackSource.values()).map(Enum::name).toList());
        assertThat(constraintValues("ck_finding_verdict_pack")).containsExactlyElementsOf(FeedbackTarget.PACKS);
    }

    @Test
    void theNoteLimitIsTheConstraintOfV37() throws IOException {
        assertThat(migration()).contains("char_length(note) BETWEEN 1 AND " + FindingFeedbackService.MAX_NOTE_CHARS + ")");
    }

    @Test
    void aNoteIsTrimmedBlankIsNoNoteAndOneOverTheLimitIsRefusedNeverCut() {
        assertThat(FindingFeedbackService.cleanNote(null)).isNull();
        assertThat(FindingFeedbackService.cleanNote("   \n ")).isNull();
        assertThat(FindingFeedbackService.cleanNote("  a\r\nb  ")).isEqualTo("a\nb");
        String limit = "é".repeat(FindingFeedbackService.MAX_NOTE_CHARS);
        assertThat(FindingFeedbackService.cleanNote(limit)).isEqualTo(limit);
        // code points, not UTF-16 units: an emoji is one character
        assertThat(FindingFeedbackService.cleanNote("😀".repeat(FindingFeedbackService.MAX_NOTE_CHARS))).isNotNull();
        assertThatThrownBy(() -> FindingFeedbackService.cleanNote(limit + "x"))
                .isInstanceOfSatisfying(InvalidFeedbackException.class, e -> {
                    assertThat(e.reason()).isEqualTo(InvalidFeedbackException.Reason.NOTE_TOO_LONG);
                    assertThat(e.noteLength()).isEqualTo(FindingFeedbackService.MAX_NOTE_CHARS + 1);
                    assertThat(e.getMessage()).doesNotContain("é");
                });
    }

    @Test
    void aVerdictIsReadInAnyCaseAndNothingElseIsGuessed() {
        assertThat(FeedbackVerdict.parse(" false_positive ")).contains(FeedbackVerdict.FALSE_POSITIVE);
        assertThat(FeedbackVerdict.parse("WRONG")).isEmpty();
        assertThat(FeedbackVerdict.parse(null)).isEmpty();
        assertThat(FeedbackVerdict.FALSE_POSITIVE.css()).isEqualTo("false-positive");
    }

    @Test
    void anAddressNamesAStoringPackAndANonNegativeIndex() {
        UUID id = UUID.randomUUID();
        assertThat(FeedbackTarget.listingRules(id, 3).key()).isEqualTo("listing-rules-" + id + "-3");
        assertThatThrownBy(() -> new FeedbackTarget("personalization-screening", id, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedbackTarget("listing-rules", id, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRatesAreFourDecimalsAndNullWithoutVerdicts() {
        assertThat(FeedbackStatsService.rate(1, 3)).isEqualTo(0.3333);
        assertThat(FeedbackStatsService.rate(0, 0)).isNull();
        FeedbackStats.VerdictTotalsView totals = FeedbackStatsService.totals(List.of());
        assertThat(totals.byVerdict()).containsOnlyKeys("CORRECT", "FALSE_POSITIVE", "NOT_USEFUL").allSatisfy((k, v) -> assertThat(v).isZero());
        assertThat(totals.falsePositiveRate()).isNull();
    }

    @Test
    void theHtmxVersionIsThePomsAndItsFileIsOnTheClassPath() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pom).contains("<htmx.version>" + ConsoleVendorResources.HTMX_VERSION + "</htmx.version>");
        assertThat(new DefaultResourceLoader().getResource(ConsoleVendorResources.HTMX_LOCATION + "htmx.min.js").exists()).isTrue();
    }
}
