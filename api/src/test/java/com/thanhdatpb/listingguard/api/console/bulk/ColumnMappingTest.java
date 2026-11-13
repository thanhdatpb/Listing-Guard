package com.thanhdatpb.listingguard.api.console.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.console.bulk.BulkTargets.Target;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import com.thanhdatpb.listingguard.input.StandardListingAdapter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What a column can be, the guess from a header, the remembered mapping and the checks before a run (ADR-029). */
class ColumnMappingTest {

    private static final EditorMarketplace ETSY = EditorMarketplace.ETSY;
    private static final EditorMarketplace AMAZON = EditorMarketplace.AMAZON;

    private static String guess(EditorMarketplace marketplace, String header) {
        return BulkTargets.guess(marketplace, header).map(Target::key).orElse("(none)");
    }

    @Test
    void headersAreComparedWithoutAccentsCaseOrPunctuation() {
        assertThat(BulkTargets.normalize("Tiêu đề")).isEqualTo("tieude");
        assertThat(BulkTargets.normalize("TIEU_DE")).isEqualTo("tieude");
        assertThat(BulkTargets.normalize("Bullet Point 1")).isEqualTo("bulletpoint1");
        assertThat(BulkTargets.normalize("  Số lượng ")).isEqualTo("soluong");
        assertThat(BulkTargets.normalize("—")).isEmpty();
    }

    @Test
    void aHeaderIsGuessedFromAListOfNamesAndNothingElse() {
        assertThat(guess(ETSY, "Tiêu đề")).isEqualTo("title");
        assertThat(guess(ETSY, "TITLE")).isEqualTo("title");
        assertThat(guess(ETSY, "Mô tả")).isEqualTo("description");
        assertThat(guess(ETSY, "Tag 3")).as("a numbered column of a list target").isEqualTo("tags");
        assertThat(guess(ETSY, "tags")).isEqualTo("tags");
        assertThat(guess(ETSY, "who_made")).isEqualTo("who_made");
        assertThat(guess(ETSY, "SKU cha")).isEqualTo("parent_sku");
        assertThat(guess(ETSY, "Màu sắc")).isEqualTo("attribute");
        assertThat(guess(AMAZON, "bullet_point5")).isEqualTo("bullet_points");
        assertThat(guess(AMAZON, "Bullet 1")).isEqualTo("bullet_points");
        assertThat(guess(AMAZON, "Thương hiệu")).isEqualTo("brand");
        assertThat(guess(AMAZON, "Search Terms")).isEqualTo("search_terms");
        assertThat(guess(AMAZON, "Variation Theme")).isEqualTo("variation_theme");

        assertThat(guess(ETSY, "Ghi chú")).as("a column the list does not know").isEqualTo("(none)");
        assertThat(guess(ETSY, "")).isEqualTo("(none)");
        assertThat(guess(AMAZON, "Tag 1")).as("Amazon has no tags").isEqualTo("(none)");
        assertThat(guess(ETSY, "Title 2")).as("a target that takes one column has no numbered form").isEqualTo("(none)");
    }

    @Test
    void everyListingTargetIsAFieldOfTheStandardInputOfItsMarketplace() {
        for (EditorMarketplace marketplace : EditorMarketplace.values()) {
            for (Target target : BulkTargets.of(marketplace)) {
                if (!target.listingField()) {
                    continue;
                }
                ObjectNode listing = JsonNodeFactory.instance.objectNode();
                listing.put(target.key(), "x");
                assertThat(StandardListingAdapter.toStandardInput(marketplace.code(), listing).has(target.key()))
                        .as(marketplace + " " + target.key()).isTrue();
            }
        }
    }

    @Test
    void noHeaderNameBelongsToTwoTargetsOfOneMarketplace() {
        for (EditorMarketplace marketplace : EditorMarketplace.values()) {
            Set<String> seen = new HashSet<>();
            for (Target target : BulkTargets.of(marketplace)) {
                for (String name : target.names()) {
                    assertThat(seen.add(name)).as(marketplace + " name " + name).isTrue();
                }
            }
        }
    }

    @Test
    void theMappingIsStartedFromTheGuessAndAColumnThatMatchesNothingIsSkipped() {
        ColumnMapping mapping = ColumnMapping.suggest(ETSY, List.of("SKU", "Tiêu đề", "Tag 1", "Tag 2", "Ghi chú"), null);

        assertThat(mapping.targets()).containsExactly("sku", "title", "tags", "tags", "");
        assertThat(mapping.separatorOf(BulkTargets.find(ETSY, "tags").orElseThrow())).isEqualTo("comma");
        assertThat(mapping.separatorOf(BulkTargets.find(AMAZON, "bullet_points").orElseThrow())).isEqualTo("newline");
    }

    @Test
    void aTargetThatTakesOneColumnGoesToTheFirstColumnThatClaimsIt() {
        ColumnMapping mapping = ColumnMapping.suggest(ETSY, List.of("Title", "Tiêu đề", "Giá"), null);

        assertThat(mapping.targets()).containsExactly("title", "", "price");
    }

    @Test
    void aTableOfOneUnrecognizedColumnIsReadAsTheTitles() {
        assertThat(ColumnMapping.suggest(ETSY, List.of("Cột A"), null).targets()).containsExactly("title");
        assertThat(ColumnMapping.suggest(ETSY, List.of("Ghi chú", "Cột B"), null).targets()).containsExactly("", "");
    }

    @Test
    void theMappingTheAccountUsedBeforeBeatsTheGuessAndFillsInWhatNoNameMatches() {
        BulkMappingStore.Stored stored = new BulkMappingStore.Stored(
                Map.of("Tên hàng", "title", "Từ khóa", "tags", "Giá", "category"), Map.of("tags", "semicolon"));

        ColumnMapping mapping = ColumnMapping.suggest(ETSY, List.of("Tên hàng", "Từ khóa", "Giá", "Mô tả"), stored);

        assertThat(mapping.targets()).as("a remembered label wins, even over a name in the list").containsExactly("title", "tags",
                "category", "description");
        assertThat(mapping.separatorOf(BulkTargets.find(ETSY, "tags").orElseThrow())).isEqualTo("semicolon");
    }

    @Test
    void aRememberedTargetThatTheMarketplaceDoesNotHaveIsIgnored() {
        BulkMappingStore.Stored stored = new BulkMappingStore.Stored(Map.of("Hãng", "brand", "Tiêu đề", "no-such-target"),
                Map.of("brand", "comma", "tags", "no-such-separator"));

        ColumnMapping mapping = ColumnMapping.suggest(ETSY, List.of("Hãng", "Tiêu đề"), stored);

        assertThat(mapping.targets()).containsExactly("", "title");
        assertThat(mapping.separatorOf(BulkTargets.find(ETSY, "tags").orElseThrow())).isEqualTo("comma");
    }

    @Test
    void whatIsRememberedIsLabelsTargetsAndSeparatorsOnly() {
        ColumnMapping mapping = new ColumnMapping(List.of("sku", "title", "", "tags"), Map.of("tags", "pipe", "materials", "comma"));

        BulkMappingStore.Stored stored = mapping.toStored(ETSY, List.of("Mã", "Tên", "Ghi chú", "Từ khóa"));

        assertThat(stored.columns()).containsOnly(Map.entry("Mã", "sku"), Map.entry("Tên", "title"), Map.entry("Từ khóa", "tags"));
        assertThat(stored.separators()).containsOnly(Map.entry("tags", "pipe"));
    }

    @Test
    void aTargetGivenToTwoColumnsIsRefusedWithBothNames() {
        ColumnMapping mapping = new ColumnMapping(List.of("title", "title", "tags", "tags"), Map.of());

        List<String> problems = mapping.problems(ETSY, List.of("Tên", "Tiêu đề", "Tag 1", "Tag 2"));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("Tiêu đề").contains("2 cột").contains("Tên, Tiêu đề");
    }

    @Test
    void aMappingThatReadsNoListingFieldIsRefused() {
        ColumnMapping onlySku = new ColumnMapping(List.of("sku", ""), Map.of());

        assertThat(onlySku.problems(ETSY, List.of("SKU", "x"))).singleElement().asString().contains("ít nhất một cột");
        assertThat(new ColumnMapping(List.of("title"), Map.of()).problems(ETSY, List.of("Tên"))).isEmpty();
    }

    @Test
    void aSubmittedFormIsReadByColumnPositionAndAnUnknownValueIsRefused() {
        ColumnMapping mapping = ColumnMapping.fromParams(ETSY, 3,
                Map.of("col.0", "sku", "col.1", "title", "sep.tags", "newline", "sep.materials", "pipe"));

        assertThat(mapping.targets()).containsExactly("sku", "title", "");
        assertThat(mapping.separatorOf(BulkTargets.find(ETSY, "tags").orElseThrow())).isEqualTo("newline");
        assertThat(mapping.separatorOf(BulkTargets.find(ETSY, "materials").orElseThrow())).isEqualTo("pipe");

        assertThatThrownBy(() -> ColumnMapping.fromParams(ETSY, 1, Map.of("col.0", "brand")))
                .isInstanceOfSatisfying(BulkTable.Refused.class, e -> assertThat(e.status).isEqualTo(400));
        assertThatThrownBy(() -> ColumnMapping.fromParams(ETSY, 1, Map.of("col.0", "title", "sep.tags", "tilde")))
                .isInstanceOf(BulkTable.Refused.class);
    }
}
