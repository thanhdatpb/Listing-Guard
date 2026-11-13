package com.thanhdatpb.listingguard.api.console.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** What a Sheet puts on the clipboard, read into rows (ADR-029). Synthetic text only. */
class DelimitedTextTest {

    private static List<List<String>> cells(DelimitedText.Table table) {
        return table.rows().stream().map(DelimitedText.Row::cells).toList();
    }

    private static List<Integer> lines(DelimitedText.Table table) {
        return table.rows().stream().map(DelimitedText.Row::line).toList();
    }

    @Test
    void readsTabSeparatedRowsAndRecordsTheLineWhereEachStarts() {
        DelimitedText.Table table = DelimitedText.parse("a\tb\nc\td\n", '\t');

        assertThat(cells(table)).containsExactly(List.of("a", "b"), List.of("c", "d"));
        assertThat(lines(table)).containsExactly(1, 2);
    }

    @Test
    void aQuotedCellMayHoldATabALineBreakAndADoubledQuote() {
        String text = "title\tdescription\nMug\t\"line one\nline two\twith a tab and \"\"quotes\"\"\"\nCup\tplain\n";

        DelimitedText.Table table = DelimitedText.parse(text, '\t');

        assertThat(cells(table)).containsExactly(
                List.of("title", "description"),
                List.of("Mug", "line one\nline two\twith a tab and \"quotes\""),
                List.of("Cup", "plain"));
        // the line break inside the quotes counts: the third row starts on line 4 of the text
        assertThat(lines(table)).containsExactly(1, 2, 4);
    }

    @Test
    void windowsAndOldMacLineBreaksEndRowsAndAreKeptAsNewlineInsideQuotes() {
        assertThat(cells(DelimitedText.parse("a\tb\r\nc\td\r\n", '\t'))).containsExactly(List.of("a", "b"), List.of("c", "d"));
        assertThat(cells(DelimitedText.parse("a\rb\r", '\t'))).containsExactly(List.of("a"), List.of("b"));
        assertThat(cells(DelimitedText.parse("\"x\r\ny\"\tz", '\t'))).containsExactly(List.of("x\ny", "z"));
    }

    @Test
    void aBlankRowInTheMiddleKeepsItsPlaceSoTheAnswerLinesUpWithTheSheet() {
        DelimitedText.Table table = DelimitedText.parse("a\n\nb\n", '\t');

        assertThat(cells(table)).containsExactly(List.of("a"), List.of(""), List.of("b"));
        assertThat(lines(table)).containsExactly(1, 2, 3);
        assertThat(table.rows().get(1).blank()).isTrue();
    }

    @Test
    void blankRowsAtTheEndAreDroppedAndALastRowNeedsNoLineBreak() {
        assertThat(cells(DelimitedText.parse("a\n\n\n", '\t'))).containsExactly(List.of("a"));
        assertThat(cells(DelimitedText.parse("a\t\t\nb", '\t'))).containsExactly(List.of("a", "", ""), List.of("b"));
        assertThat(DelimitedText.parse("", '\t').rows()).isEmpty();
        assertThat(DelimitedText.parse("\n\n", '\t').rows()).isEmpty();
    }

    @Test
    void aTrailingDelimiterMakesAnEmptyLastCell() {
        assertThat(cells(DelimitedText.parse("x\t", '\t'))).containsExactly(List.of("x", ""));
    }

    @Test
    void aQuoteInTheMiddleOfAnUnquotedCellIsAnOrdinaryCharacter() {
        assertThat(cells(DelimitedText.parse("5\" mug\tab\"c\n", '\t'))).containsExactly(List.of("5\" mug", "ab\"c"));
    }

    @Test
    void aQuoteThatIsNeverClosedIsRefusedWithTheLineItOpenedOn() {
        assertThatThrownBy(() -> DelimitedText.parse("a\tb\nc\t\"never closed\nmore\n", '\t'))
                .isInstanceOfSatisfying(DelimitedText.FormatException.class, e -> assertThat(e.line).isEqualTo(2));
    }

    @Test
    void aByteOrderMarkIsDropped() {
        assertThat(cells(DelimitedText.parse("﻿SKU,Title\n", ','))).containsExactly(List.of("SKU", "Title"));
    }

    @Test
    void theDelimiterIsFoundFromTheFirstRowOutsideQuotes() {
        assertThat(DelimitedText.detectDelimiter("a\tb\tc\n1,2,3\n")).isEqualTo('\t');
        assertThat(DelimitedText.detectDelimiter("a,b,c\n1\t2\t3\n")).isEqualTo(',');
        assertThat(DelimitedText.detectDelimiter("a;b;c\n")).isEqualTo(';');
        assertThat(DelimitedText.detectDelimiter("\"a,b\"\tc\n")).as("a comma inside quotes").isEqualTo('\t');
        assertThat(DelimitedText.detectDelimiter("single column\nnext\n")).as("none at all").isEqualTo('\t');
        assertThat(DelimitedText.detectDelimiter("﻿SKU,TITLE\n")).isEqualTo(',');
    }
}
