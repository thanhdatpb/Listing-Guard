package com.thanhdatpb.listingguard.api.console.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.console.bulk.BulkTable.HeaderMode;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A pasted table with and without a header row, odd columns and the row limit (ADR-029). Synthetic text only. */
class BulkTableTest {

    private static final int MAX_ROWS = 200;

    private static BulkTable.Prepared prepare(String text, HeaderMode mode) {
        return BulkTable.prepare(text, '\t', mode, EditorMarketplace.ETSY, Set.of(), MAX_ROWS);
    }

    @Test
    void aHeaderRowIsRecognizedFromTheNamesOfItsCells() {
        BulkTable.Prepared table = prepare("Tiêu đề\tTag\nMug\tgift, mug\nCup\tgift\n", HeaderMode.AUTO);

        assertThat(table.header()).isTrue();
        assertThat(table.labels()).containsExactly("Tiêu đề", "Tag");
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0).line()).isEqualTo(2);
    }

    @Test
    void withoutAHeaderEveryRowIsDataAndTheColumnsAreNamedLikeASheet() {
        BulkTable.Prepared table = prepare("Retro mug\tgift\nCup\tmug\n", HeaderMode.AUTO);

        assertThat(table.header()).isFalse();
        assertThat(table.labels()).containsExactly("Cột A", "Cột B");
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0).line()).isEqualTo(1);
    }

    @Test
    void theSellerOverridesTheGuessEitherWay() {
        // a first row of titles that happens to hold a name from the list
        BulkTable.Prepared asData = prepare("Title\tx\nMug\ty\n", HeaderMode.NO);
        assertThat(asData.header()).isFalse();
        assertThat(asData.rows()).hasSize(2);

        BulkTable.Prepared asHeader = prepare("Ten hang\tGhi chú\nMug\ty\n", HeaderMode.YES);
        assertThat(asHeader.header()).isTrue();
        assertThat(asHeader.labels()).containsExactly("Ten hang", "Ghi chú");
    }

    @Test
    void aBlankHeaderCellGetsALetterAndARepeatedOneGetsANumber() {
        BulkTable.Prepared table = prepare("Tag\t\tTag\nx\ty\tz\n", HeaderMode.YES);

        assertThat(table.labels()).containsExactly("Tag", "Cột B", "Tag (2)");
    }

    @Test
    void aLabelTheAccountHasAMappingForMakesAHeaderEvenWhenNoNameInTheListMatches() {
        String text = "Tên hàng\nMug\n";

        assertThat(prepare(text, HeaderMode.AUTO).header()).isFalse();
        assertThat(BulkTable.prepare(text, '\t', HeaderMode.AUTO, EditorMarketplace.ETSY, Set.of("Tên hàng"), MAX_ROWS).header())
                .isTrue();
    }

    @Test
    void aRowWithMoreCellsThanTheHeaderAddsAColumn() {
        BulkTable.Prepared table = prepare("Title\tTag\nMug\ta\textra\n", HeaderMode.YES);

        assertThat(table.labels()).containsExactly("Title", "Tag", "Cột C");
    }

    @Test
    void aBlankRowKeepsItsPlaceAmongTheDataRows() {
        BulkTable.Prepared table = prepare("Title\nMug\n\nCup\n", HeaderMode.YES);

        assertThat(table.rows()).hasSize(3);
        assertThat(table.rows().get(1).blank()).isTrue();
        assertThat(table.rows().get(2).line()).isEqualTo(4);
    }

    @Test
    void moreRowsThanTheLimitAreRefusedAsAWholeAndNeverCut() {
        StringBuilder text = new StringBuilder("Title\n");
        for (int i = 1; i <= 5; i++) {
            text.append("Mug ").append(i).append('\n');
        }

        assertThatThrownBy(() -> BulkTable.prepare(text.toString(), '\t', HeaderMode.YES, EditorMarketplace.ETSY, Set.of(), 4))
                .isInstanceOfSatisfying(BulkTable.Refused.class, e -> {
                    assertThat(e.status).isEqualTo(400);
                    assertThat(e.getMessage()).contains("5 dòng").contains("giới hạn 4");
                });
        // the header row is not a data row: exactly the limit passes
        assertThat(BulkTable.prepare(text.toString(), '\t', HeaderMode.YES, EditorMarketplace.ETSY, Set.of(), 5).rows()).hasSize(5);
    }

    @Test
    void anEmptyPasteAndAHeaderOnlyPasteAreRefusedWithAReason() {
        assertThatThrownBy(() -> prepare("", HeaderMode.AUTO)).isInstanceOf(BulkTable.Refused.class)
                .hasMessageContaining("Chưa có dữ liệu");
        assertThatThrownBy(() -> prepare("\n\n", HeaderMode.AUTO)).isInstanceOf(BulkTable.Refused.class);
        assertThatThrownBy(() -> prepare("Tiêu đề\tTag\n", HeaderMode.AUTO)).isInstanceOf(BulkTable.Refused.class)
                .hasMessageContaining("Chỉ có dòng tiêu đề");
    }

    @Test
    void aQuoteThatIsNeverClosedIsRefusedWithTheLine() {
        assertThatThrownBy(() -> prepare("Title\n\"open\nMug\n", HeaderMode.AUTO)).isInstanceOf(BulkTable.Refused.class)
                .hasMessageContaining("dòng 2");
    }

    @Test
    void columnLettersFollowASpreadsheet() {
        assertThat(BulkTable.letters(0)).isEqualTo("A");
        assertThat(BulkTable.letters(25)).isEqualTo("Z");
        assertThat(BulkTable.letters(26)).isEqualTo("AA");
        assertThat(BulkTable.letters(27)).isEqualTo("AB");
        assertThat(BulkTable.letters(701)).isEqualTo("ZZ");
        assertThat(BulkTable.letters(702)).isEqualTo("AAA");
    }
}
