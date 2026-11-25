package com.thanhdatpb.listingguard.api.console.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.console.bulk.DelimitedText;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/samples/watchlist-template.csv} (ADR-053) is the sheet a pilot lead fills in: it must be read by the import of the
 * watchlist page as it stands, with the header the export writes, and hold only invented marks.
 */
class WatchlistTemplateTest {

    private static final Path TEMPLATE = Path.of("..", "docs", "samples", "watchlist-template.csv");

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    private static List<WatchlistTable.Row> rows() throws IOException {
        String text = Files.readString(TEMPLATE, StandardCharsets.UTF_8);
        return WatchlistTable.read(text, DelimitedText.detectDelimiter(text));
    }

    @Test
    void theImportReadsEveryRowOfTheTemplateWithItsAliasesClassesAndSwitch() throws IOException {
        List<WatchlistTable.Row> rows = rows();

        assertThat(rows).hasSize(4);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.errorCode()).as("line " + row.line()).isNull();
            assertThat(row.entry()).isNotNull();
        });
        EntryInput otters = rows.get(0).entry();
        assertThat(otters.mark()).isEqualTo("Brimwick Otters");
        assertThat(otters.aliases()).containsExactly("Otterz", "BWO", "Go Otters");
        assertThat(otters.niceClasses()).containsExactly(25, 21);
        assertThat(otters.note()).isEqualTo("Tên đội, từng bị claim ở listing áo");
        assertThat(otters.enabled()).isTrue();
        assertThat(rows.get(2).entry().aliases()).isEmpty();
        assertThat(rows.get(2).entry().note()).isEqualTo("Slogan: Light the harbor");
        assertThat(rows.get(3).entry().enabled()).as("a draft entry is kept and switched off").isFalse();
    }

    @Test
    void theHeaderIsTheOneTheExportWritesAndNoTwoNamesAreTheSameAfterNormalization() throws IOException {
        String text = Files.readString(TEMPLATE, StandardCharsets.UTF_8);
        assertThat(text.lines().findFirst().orElseThrow()).isEqualTo(String.join(",", WatchlistTable.HEADER));

        List<String> names = rows().stream().flatMap(row -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of(row.entry().mark()), row.entry().aliases().stream()))
                .map(WatchlistTemplateTest::normalize).toList();
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void theTemplateHoldsNoSerialNumberBecauseAnInventedOneWouldPointAtARealFile() throws IOException {
        assertThat(rows()).extracting(row -> row.entry().serialNumber()).containsOnlyNulls();
    }
}
