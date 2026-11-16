package com.thanhdatpb.listingguard.api.console.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryView;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reading and writing the watchlist as a table (ADR-031): what a Sheet pastes, what a CSV holds, what the export writes. */
class WatchlistTableTest {

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    private static EntryView view(String mark, boolean enabled) {
        return new EntryView(UUID.randomUUID(), mark, List.of("Otterz"), List.of(25), "12345678", "note", enabled, "api-key:k",
                Instant.EPOCH, "api-key:k", Instant.EPOCH);
    }

    @Test
    void withoutAHeaderTheColumnsAreTakenInTheDocumentedOrder() {
        List<WatchlistTable.Row> rows = WatchlistTable.read("Brimwick Otters\tOtterz; BWO\t25, 28\t12345678\tnote\tfalse\n", '\t');

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.line()).isEqualTo(1);
            assertThat(row.entry().mark()).isEqualTo("Brimwick Otters");
            assertThat(row.entry().aliases()).containsExactly("Otterz", "BWO");
            assertThat(row.entry().niceClasses()).containsExactly(25, 28);
            assertThat(row.entry().serialNumber()).isEqualTo("12345678");
            assertThat(row.entry().note()).isEqualTo("note");
            assertThat(row.entry().enabled()).isFalse();
        });
    }

    @Test
    void aHeaderInEnglishOrVietnameseAnyOrderFindsTheColumnsAndIgnoresTheOthers() {
        String named = "Nhãn hiệu\tGhi chú\tNhóm Nice\tunknown\nBrimwick Otters\tlý do\t25\tx\n";

        assertThat(WatchlistTable.read(named, '\t')).singleElement().satisfies(row -> {
            assertThat(row.line()).isEqualTo(2);
            assertThat(row.entry().mark()).isEqualTo("Brimwick Otters");
            assertThat(row.entry().note()).isEqualTo("lý do");
            assertThat(row.entry().niceClasses()).containsExactly(25);
            assertThat(row.entry().aliases()).isEmpty();
        });
        String notAHeader = "Ghi chú\tNhãn hiệu\n" + "lý do\tBrimwick Otters\n";
        assertThat(WatchlistTable.read(notAHeader, '\t')).as("the first cell is not the mark column, so both rows are data")
                .hasSize(2);
    }

    @Test
    void aCellInQuotesMayHoldATabALineBreakAndAQuote() {
        String sheet = "\"Brimwick\tOtters\"\t\"one\ntwo\"\t\t\t\"say \"\"hi\"\"\"\n";

        WatchlistTable.Row row = WatchlistTable.read(sheet, '\t').get(0);

        assertThat(row.entry().mark()).isEqualTo("Brimwick\tOtters");
        assertThat(row.entry().aliases()).containsExactly("one", "two");
        assertThat(row.entry().note()).isEqualTo("say \"hi\"");
    }

    @Test
    void aClassThatIsNotANumberAndAnEnabledThatIsNotOnOrOffAreRefusedPerRow() {
        List<WatchlistTable.Row> rows = WatchlistTable.read("A\t\t25;abc\nB\t\t\t\t\tmaybe\nC\t\t25\n", '\t');

        assertThat(rows).extracting(WatchlistTable.Row::errorCode).containsExactly("classes-format", "enabled-format", null);
        assertThat(rows.get(0).errorValue()).isEqualTo("25;abc");
        assertThat(rows.get(2).entry().mark()).isEqualTo("C");
    }

    @Test
    void aMergeUpdatesByNormalizedMarkKeepsTheRestAndSaysWhatEachRowDoes() {
        List<EntryView> current = List.of(view("Brimwick Otters", true), view("Quillmark Rovers", false));
        List<WatchlistTable.Row> rows = WatchlistTable.read(
                "BRIMWICK-OTTERS\tOtterz\t25\t12345678\tnote\nPaddle Hard\nQuillmark Rovers\tOtterz\t25\t12345678\tnew note\n", '\t');

        WatchlistTable.Merge merge = WatchlistTable.merge(current, rows, WatchlistTableTest::normalize);

        assertThat(merge.rows()).extracting(WatchlistTable.MergedRow::outcome).containsExactly(WatchlistTable.Outcome.UPDATED,
                WatchlistTable.Outcome.ADDED, WatchlistTable.Outcome.UPDATED);
        assertThat(merge.entries()).extracting(EntryInput::mark).containsExactly("BRIMWICK-OTTERS", "Quillmark Rovers",
                "Paddle Hard");
        assertThat(merge.entries().get(1).enabled()).as("a row with no enabled column keeps the entry's own").isFalse();
        assertThat(merge.entries().get(2).enabled()).as("a new entry is on").isTrue();
    }

    @Test
    void aRowThatChangesNothingIsUnchanged() {
        List<EntryView> current = List.of(view("Brimwick Otters", true));

        WatchlistTable.Merge merge = WatchlistTable.merge(current,
                WatchlistTable.read("Brimwick Otters\tOtterz\t25\t12345678\tnote\n", '\t'), WatchlistTableTest::normalize);

        assertThat(merge.rows()).singleElement().satisfies(row -> assertThat(row.outcome()).isEqualTo(WatchlistTable.Outcome.UNCHANGED));
    }

    @Test
    void theExportQuotesWhatNeedsItAndGuardsFormulas() {
        String csv = WatchlistTable.csv(List.of(view("=1+1", true), view("a,b", false)));

        assertThat(csv).startsWith("﻿mark,aliases,classes,serial,note,enabled\r\n")
                .contains("'=1+1,Otterz,25,12345678,note,true\r\n").contains("\"a,b\",Otterz,25,12345678,note,false\r\n");
        assertThat(WatchlistTable.guard("@x")).isEqualTo("'@x");
        assertThat(WatchlistTable.guard("x")).isEqualTo("x");
        assertThat(WatchlistTable.unguard("'=x")).isEqualTo("=x");
        assertThat(WatchlistTable.unguard("'normal")).as("only a guarded formula loses its quote").isEqualTo("'normal");
    }
}
