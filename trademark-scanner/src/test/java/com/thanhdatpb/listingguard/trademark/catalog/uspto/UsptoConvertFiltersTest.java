package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.TestRulesets;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap.Target;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The conversion filters (ADR-009, PR A2c): the shipped file, the strict loader and the file name dates. */
class UsptoConvertFiltersTest {

    private static final String SHIPPED = "uspto/uspto-convert-filters-2026-11.yaml";

    /** The shipped file is immutable: a change is a new file and a new version, so a new hash here is a new test. */
    private static final String SHIPPED_HASH = "7c7ee0a125b7fafbf0708358ae39c056fa9462e987881669ffc76b9528359eef";

    private final UsptoConvertFilters shipped = UsptoConvertFilters.loadClasspath(SHIPPED);
    private final UsptoStatusMap statusMap = UsptoTestXml.statusMap();

    private static String shippedText() throws IOException {
        try (InputStream in = UsptoConvertFiltersTest.class.getResourceAsStream("/" + SHIPPED)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theShippedFileIsUnverifiedAndHasTheSuggestedClasses() {
        assertThat(shipped.version()).isEqualTo("uspto-convert-filters-2026-11");
        assertThat(shipped.niceClasses()).containsExactly(9, 14, 16, 18, 20, 21, 24, 25, 26, 28);
        assertThat(shipped.textOnly()).isTrue();
        // source.status 'unverified' is checked by the loader: a file that says anything else does not load
    }

    @Test
    void theShippedClassesCoverEveryProductTypeOfTheShippedRuleset() {
        Set<Integer> productClasses = TestRulesets.shipped().productClasses().types().values().stream()
                .flatMap(List::stream).collect(Collectors.toCollection(TreeSet::new));

        assertThat(productClasses).isNotEmpty();
        assertThat(shipped.niceClasses()).containsAll(productClasses);
    }

    @Test
    void theShippedLiveCodesAreExactlyTheCodesTheMapSendsToRegisteredOrPending() {
        Set<String> expected = statusMap.entries().entrySet().stream()
                .filter(e -> e.getValue().target() == Target.REGISTERED || e.getValue().target() == Target.PENDING)
                .map(java.util.Map.Entry::getKey).collect(Collectors.toCollection(TreeSet::new));

        assertThat(shipped.liveStatusCodes()).isEqualTo(expected).hasSize(121);
        shipped.requireCodesIn(statusMap); // does not throw
        assertThat(shipped.isLive("700")).isTrue();
        assertThat(shipped.isLive("900")).as("expired").isFalse();
        assertThat(shipped.isLive("710")).as("cancelled").isFalse();
        assertThat(shipped.isLive("973")).as("no status of the mark").isFalse();
    }

    @Test
    void theShippedFileIsPinned() throws Exception {
        String text = shippedText().replace("\r\n", "\n");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));

        assertThat(shipped.contentHash()).isEqualTo(hash);
        assertThat(hash).as("a published version is immutable (ADR-002): change the file by adding a new version")
                .isEqualTo(SHIPPED_HASH);
    }

    @Test
    void aLiveCodeThatIsDroppedOrNotInTheMapIsRefused() {
        UsptoConvertFilters dropped = UsptoTestXml.filters(List.of(25), List.of("700", "973"), true);
        UsptoConvertFilters unlisted = UsptoTestXml.filters(List.of(25), List.of("700", "999"), true);

        assertThatThrownBy(() -> dropped.requireCodesIn(statusMap)).isInstanceOf(UsptoConvertFiltersException.class)
                .hasMessageContaining("973");
        assertThatThrownBy(() -> unlisted.requireCodesIn(statusMap)).isInstanceOf(UsptoConvertFiltersException.class)
                .hasMessageContaining("999");
        assertThatThrownBy(() -> new UsptoCatalogConverter(statusMap, dropped,
                new com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer(TestRulesets.test().normalization()),
                UsptoCatalogConverter.Options.defaults())).isInstanceOf(UsptoConvertFiltersException.class);
    }

    @Test
    void includesAClassOnlyWhenOneOfTheRecordsClassesIsListed() {
        assertThat(shipped.includesAnyClass(List.of(25))).isTrue();
        assertThat(shipped.includesAnyClass(List.of(35, 25))).isTrue();
        assertThat(shipped.includesAnyClass(List.of(35, 42))).isFalse();
        assertThat(shipped.includesAnyClass(List.of())).isFalse();
    }

    // --- file name dates ---

    @Test
    void readsTheDateOfAnApcNameAndOfAFullDate() {
        assertThat(shipped.fileNameDate("apc250131.xml")).isEqualTo(LocalDate.of(2025, 1, 31));
        assertThat(shipped.fileNameDate("uspto/apc241101.xml")).isEqualTo(LocalDate.of(2024, 11, 1));
        assertThat(shipped.fileNameDate("daily-20241102.xml")).isEqualTo(LocalDate.of(2024, 11, 2));
        assertThat(shipped.fileNameDate("uspto\\apc250101.xml")).as("only the last segment is read")
                .isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(shipped.fileNameDate("2026/apc250101.xml")).as("a folder is not the name")
                .isEqualTo(LocalDate.of(2025, 1, 1));
    }

    @Test
    void aNameWithoutARealDateHasNone() {
        assertThat(shipped.fileNameDate("daily.xml")).isNull();
        assertThat(shipped.fileNameDate("apc251301.xml")).as("month 13").isNull();
        assertThat(shipped.fileNameDate("apc250230.xml")).as("30 February").isNull();
        assertThat(shipped.fileNameDate("x12345678901.xml")).as("longer digit run").isNull();
    }

    // --- the strict loader ---

    private static String valid() {
        return "version: uspto-convert-filters-test\nformat: trademark-applications-daily V2.0\nchecked_at: 2026-11-11\n"
                + "source: {status: unverified, note: a test}\nnice_classes: [25]\ntext_only: true\n"
                + "live_status_codes: [\"700\"]\nfile_name_dates:\n  - '(?<year>[0-9]{4})(?<month>[0-9]{2})(?<day>[0-9]{2})'\n";
    }

    @Test
    void aValidFileLoads() {
        UsptoConvertFilters filters = UsptoConvertFilters.parse(valid());

        assertThat(filters.niceClasses()).containsExactly(25);
        assertThat(filters.fileNameDate("a20240102b")).isEqualTo(LocalDate.of(2024, 1, 2));
    }

    @Test
    void refusesWhatTheFormatDoesNotAllow() {
        assertRefused(valid().replace("status: unverified", "status: verified"), "unverified");
        assertRefused(valid().replace("text_only: true\n", ""), "missing key 'text_only'");
        assertRefused(valid().replace("text_only: true", "text_only: true\nextra: 1"), "unknown key 'extra'");
        assertRefused(valid().replace("text_only: true", "text_only: true\ntext_only: false"), "Duplicate");
        assertRefused(valid().replace("text_only: true", "text_only: yes please"), "text_only");
        assertRefused(valid().replace("[25]", "[]"), "non-empty");
        assertRefused(valid().replace("[25]", "[25, 25]"), "twice");
        assertRefused(valid().replace("[25]", "[46]"), "1 to 45");
        assertRefused(valid().replace("[25]", "[0]"), "1 to 45");
        assertRefused(valid().replace("[25]", "[\"25\"]"), "integer");
        assertRefused(valid().replace("[\"700\"]", "[700]"), "quoted string");
        assertRefused(valid().replace("[\"700\"]", "[\"70\"]"), "three digits");
        assertRefused(valid().replace("[\"700\"]", "[\"700\", \"700\"]"), "twice");
        assertRefused(valid().replace("[\"700\"]", "[]"), "non-empty");
        assertRefused(valid().replace("version: uspto-convert-filters-test", "version: Bad Version"), "version");
        assertRefused(valid().replace("V2.0", "V3.0"), "format");
        assertRefused(valid().replace("2026-11-11", "yesterday"), "checked_at");
    }

    @Test
    void refusesAFileNamePatternThatCannotGiveADate() {
        assertRefused(valid().replace("(?<year>[0-9]{4})(?<month>[0-9]{2})(?<day>[0-9]{2})", "([0-9]{8})"), "named groups");
        assertRefused(valid().replace("(?<day>[0-9]{2})", ""), "named groups");
        assertRefused(valid().replace("(?<year>[0-9]{4})", "(?<year>[0-9]{4})(?<yy>[0-9]{2})"), "exactly one");
        assertRefused(valid().replace("(?<day>[0-9]{2})", "(?<day>[0-9]{2})(?<other>x)"), "no other group name");
        assertRefused(valid().replace("(?<year>[0-9]{4})", "(?<year>[0-9]{4}"), "regular expression");
        assertRefused(valid().replace("file_name_dates:\n  - '(?<year>[0-9]{4})(?<month>[0-9]{2})(?<day>[0-9]{2})'\n",
                "file_name_dates: []\n"), "non-empty");
    }

    private static void assertRefused(String yaml, String fragment) {
        assertThatThrownBy(() -> UsptoConvertFilters.parse(yaml)).isInstanceOf(UsptoConvertFiltersException.class)
                .hasMessageContaining(fragment);
    }
}
