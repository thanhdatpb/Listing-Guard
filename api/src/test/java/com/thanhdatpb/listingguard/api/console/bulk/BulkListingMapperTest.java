package com.thanhdatpb.listingguard.api.console.bulk;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One row of cells into the standard listing JSON (ADR-029). Synthetic values only. */
class BulkListingMapperTest {
    private static final ConsoleMessages MESSAGES = BulkTestMessages.vi();


    private static final EditorMarketplace ETSY = EditorMarketplace.ETSY;
    private static final EditorMarketplace AMAZON = EditorMarketplace.AMAZON;

    private static BulkListingMapper.Mapped etsy(List<String> targets, Map<String, String> separators, String... cells) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            labels.add("Cột " + BulkTable.letters(i));
        }
        return BulkListingMapper.map(MESSAGES, ETSY, new ColumnMapping(targets, separators), labels, List.of(cells));
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    @Test
    void oneColumnOfTagsIsCutAtTheChosenSeparatorAndKeptAsWritten() {
        BulkListingMapper.Mapped comma = etsy(List.of("title", "tags"), Map.of("tags", "comma"), "Mug", " mug, ceramic ,");
        assertThat(texts(comma.listing().get("tags"))).as("the normalizer trims and drops blanks, not this class")
                .containsExactly(" mug", " ceramic ", "");

        BulkListingMapper.Mapped lines = etsy(List.of("tags"), Map.of("tags", "newline"), "a\nb\r\nc");
        assertThat(texts(lines.listing().get("tags"))).containsExactly("a", "b", "c");

        BulkListingMapper.Mapped pipe = etsy(List.of("tags"), Map.of("tags", "pipe"), "a|b");
        assertThat(texts(pipe.listing().get("tags"))).containsExactly("a", "b");
    }

    @Test
    void severalColumnsOfTagsAreOneTagEachAndNothingIsCutInside() {
        BulkListingMapper.Mapped mapped = etsy(List.of("tags", "tags", "tags"), Map.of("tags", "comma"), "a, b", "", "c");

        assertThat(texts(mapped.listing().get("tags"))).containsExactly("a, b", "", "c");
    }

    @Test
    void aListWithNothingInItIsAbsent() {
        assertThat(etsy(List.of("title", "tags"), Map.of(), "Mug", "").listing().has("tags")).isFalse();
        assertThat(etsy(List.of("tags", "tags"), Map.of(), "", "  ").listing().has("tags")).isFalse();
    }

    @Test
    void aBlankCellIsAnAbsentFieldAndAValueIsPassedOnAsWritten() {
        BulkListingMapper.Mapped mapped = etsy(List.of("title", "description", "category"), Map.of(), "  Mug  ", "", "Home");

        assertThat(mapped.listing().get("title").asText()).isEqualTo("  Mug  ");
        assertThat(mapped.listing().has("description")).isFalse();
        assertThat(mapped.listing().get("category").asText()).isEqualTo("Home");
        assertThat(mapped.error()).isNull();
    }

    @Test
    void aPlainDecimalPriceAndAWholeQuantityAreNumbers() {
        BulkListingMapper.Mapped mapped = etsy(List.of("price", "quantity"), Map.of(), "19.99", "5");

        assertThat(mapped.listing().get("price").decimalValue()).isEqualByComparingTo(new BigDecimal("19.99"));
        assertThat(mapped.listing().get("quantity").asLong()).isEqualTo(5);
    }

    @Test
    void aPriceOrQuantityThatCannotBeReadMakesTheRowUnreadableAndIsNeverGuessedAt() {
        BulkListingMapper.Mapped comma = etsy(List.of("title", "price"), Map.of(), "Mug", "19,99");
        assertThat(comma.error()).contains("Giá").contains("Cột B").contains("19,99");
        assertThat(comma.listing().has("price")).isFalse();

        assertThat(etsy(List.of("price"), Map.of(), "$5").error()).isNotNull();
        assertThat(etsy(List.of("price"), Map.of(), "1.234,5").error()).isNotNull();
        assertThat(etsy(List.of("quantity"), Map.of(), "5.5").error()).contains("Số lượng");
        assertThat(etsy(List.of("quantity"), Map.of(), "five").error()).isNotNull();
        assertThat(etsy(List.of("price", "quantity"), Map.of(), "", " ").error()).as("blank is absent, not an error").isNull();
    }

    @Test
    void aRowOfBlankCellsIsBlankAndAnUnmappedColumnIsLeftOut() {
        assertThat(etsy(List.of("title", ""), Map.of(), "", "  ").blank()).isTrue();

        BulkListingMapper.Mapped mapped = etsy(List.of("title", ""), Map.of(), "Mug", "a private note");
        assertThat(mapped.blank()).isFalse();
        assertThat(mapped.listing().toString()).doesNotContain("private");
        assertThat(mapped.listing().size()).isEqualTo(1);
    }

    @Test
    void theSkuTheParentSkuTheThemeAndTheAttributesAreNotListingFields() {
        ColumnMapping mapping = new ColumnMapping(List.of("sku", "title", "parent_sku", "variation_theme", "attribute", "attribute"),
                Map.of());
        List<String> labels = List.of("SKU", "Title", "Parent", "Theme", "Màu", "Size");

        BulkListingMapper.Mapped mapped = BulkListingMapper.map(MESSAGES, AMAZON, mapping, labels,
                List.of(" TEE-RED-S ", "Tee", " TEE-P", "Color-Size", "Red", " S "));

        assertThat(mapped.sku()).isEqualTo("TEE-RED-S");
        assertThat(mapped.parentSku()).isEqualTo("TEE-P");
        assertThat(mapped.theme()).isEqualTo("Color-Size");
        assertThat(mapped.attributes()).containsExactly(Map.entry("Màu", "Red"), Map.entry("Size", "S"));
        assertThat(mapped.listing().fieldNames()).toIterable().containsExactly("title");
    }

    @Test
    void amazonBulletsAcrossColumnsAndSearchTermsAsTextOrList() {
        ColumnMapping five = new ColumnMapping(List.of("bullet_points", "bullet_points", "bullet_points", "search_terms"), Map.of());
        BulkListingMapper.Mapped mapped = BulkListingMapper.map(MESSAGES, AMAZON, five, List.of("B1", "B2", "B3", "Terms"),
                List.of("one", "two", "", "red blue"));

        assertThat(texts(mapped.listing().get("bullet_points"))).containsExactly("one", "two", "");
        assertThat(mapped.listing().get("search_terms").isTextual()).as("one column is text").isTrue();

        ColumnMapping manyTerms = new ColumnMapping(List.of("search_terms", "search_terms"), Map.of());
        BulkListingMapper.Mapped list = BulkListingMapper.map(MESSAGES, AMAZON, manyTerms, List.of("T1", "T2"), List.of("red", "blue"));
        assertThat(texts(list.listing().get("search_terms"))).containsExactly("red", "blue");
    }

    @Test
    void aSingleColumnOfAmazonBulletsIsCutAtNewLinesByDefault() {
        ColumnMapping one = new ColumnMapping(List.of("bullet_points"), Map.of());

        BulkListingMapper.Mapped mapped = BulkListingMapper.map(MESSAGES, AMAZON, one, List.of("Bullets"), List.of("light\nstrong\r\nwarm"));

        assertThat(texts(mapped.listing().get("bullet_points"))).containsExactly("light", "strong", "warm");
    }

    @Test
    void aCellWithALineBreakAndATabInQuotesReachesTheListingWhole() {
        // the cell as DelimitedText gives it back from "\"line one\nline two\twith a tab\""
        DelimitedText.Table table = DelimitedText.parse("Mug\t\"line one\nline two\twith a tab\"\n", '\t');

        BulkListingMapper.Mapped mapped = etsy(List.of("title", "description"), Map.of(), table.rows().get(0).cells().toArray(String[]::new));

        assertThat(mapped.listing().get("description").asText()).isEqualTo("line one\nline two\twith a tab");
    }
}
