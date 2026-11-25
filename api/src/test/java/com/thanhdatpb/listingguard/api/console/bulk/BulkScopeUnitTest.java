package com.thanhdatpb.listingguard.api.console.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.check.CheckScope;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Detail;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Item;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Kind;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Row;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Status;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

/** The pieces of the content scope (ADR-052) that need no database: the default, the mapped fields, the status and the export. */
class BulkScopeUnitTest {

    private static final ConsoleMessages VI = BulkTestMessages.vi();
    private static final ConsoleMessages EN = BulkTestMessages.en();
    private static final List<String> LEFT_OUT = List.of("price", "category");

    private static BulkProperties properties(String scope) {
        return new BulkProperties(200, DataSize.ofMegabytes(1), scope);
    }

    // ---- the default

    @Test
    void withNoSettingTheScopeFollowsThePilotProfile() {
        assertThat(properties("").scopeFor(true)).isEqualTo(CheckScope.Kind.CONTENT);
        assertThat(properties("").scopeFor(false)).as("every other deployment keeps the old check").isEqualTo(CheckScope.Kind.FULL);
    }

    @Test
    void aConfiguredScopeWinsOverThePilotProfileInBothDirections() {
        assertThat(properties("full").scopeFor(true)).isEqualTo(CheckScope.Kind.FULL);
        assertThat(properties("content").scopeFor(false)).isEqualTo(CheckScope.Kind.CONTENT);
        assertThat(properties(" Content ").scopeFor(false)).as("case and blanks do not matter").isEqualTo(CheckScope.Kind.CONTENT);
    }

    @Test
    void aValueThatIsNeitherContentNorFullStopsTheStart() {
        assertThatThrownBy(() -> properties("partial")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default-scope");
    }

    @Test
    void theSettingBindsFromItsPropertyName() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("listing-guard.console.bulk.default-scope", "content")));
        BulkProperties bound = binder.bind("listing-guard.console.bulk", BulkProperties.class).get();

        assertThat(bound.defaultScope()).isEqualTo("content");
        assertThat(bound.maxRows()).isEqualTo(200);

        BulkProperties none = new Binder(new MapConfigurationPropertySource(Map.of("listing-guard.console.bulk.max-rows", "50")))
                .bind("listing-guard.console.bulk", BulkProperties.class).get();
        assertThat(none.defaultScope()).isEmpty();
    }

    // ---- what the mapping gives the check

    @Test
    void theCheckedFieldsAreTheListingFieldsOfTheMappedColumnsAndNothingElse() {
        ColumnMapping mapping = new ColumnMapping(List.of("sku", "title", "tags", "tags", "", "price", "attribute", "parent_sku"), Map.of());

        assertThat(mapping.mappedFields(EditorMarketplace.ETSY)).containsExactlyInAnyOrder("title", "tags", "price");
        assertThat(new ColumnMapping(List.of("title", "brand", "bullet_points", "search_terms"), Map.of())
                .mappedFields(EditorMarketplace.AMAZON)).containsExactlyInAnyOrder("title", "brand", "bullet_points", "search_terms");
    }

    @Test
    void theScopeIsStoredOnlyWhenItIsAKnownOne() {
        assertThat(new BulkMappingStore.Stored(Map.of("A", "title"), Map.of(), "content").scope()).isEqualTo("content");
        assertThat(new BulkMappingStore.Stored(Map.of("A", "title"), Map.of(), "everything").scope()).isNull();
        assertThat(new BulkMappingStore.Stored(Map.of("A", "title"), Map.of()).scope()).isNull();
        ColumnMapping mapping = new ColumnMapping(List.of("title"), Map.of());
        assertThat(mapping.toStored(EditorMarketplace.ETSY, List.of("Tiêu đề"), "full").scope()).isEqualTo("full");
        assertThat(mapping.toStored(EditorMarketplace.ETSY, List.of("Tiêu đề")).scope()).isNull();
    }

    // ---- the status of a row

    private static BulkResult.Rules rules(String readiness, CheckScope.Kind scope, List<String> skipped, Item... items) {
        return new BulkResult.Rules(true, null, readiness, null, "PARTIAL".equals(readiness) ? null : 90, List.of(items), scope, skipped);
    }

    private static Item item(String level) {
        return new Item(level, BulkText.level(VI, level), "rule_" + level, "title", "a finding", "do something", null, null, null, null);
    }

    private static Detail detail(BulkResult.Rules rules) {
        return new Detail(rules, new BulkResult.Trademark(Kind.RESULT, null, null, List.of()),
                new BulkResult.Variation(Kind.NONE, null, List.of()), null);
    }

    @Test
    void aCleanRowOfAContentCheckIsOkContentAndItsSummaryNamesWhatWasNotChecked() {
        BulkAssessment.Assessed assessed = BulkAssessment.of(VI, detail(rules("PARTIAL", CheckScope.Kind.CONTENT, LEFT_OUT)));

        assertThat(assessed.status()).isEqualTo(Status.OK_CONTENT);
        assertThat(assessed.status().label(VI)).isEqualTo("Đạt (nội dung)");
        assertThat(assessed.status().label(EN)).isEqualTo("OK (content)");
        assertThat(assessed.status().group()).as("the filter button OK shows it").isEqualTo("OK");
        assertThat(assessed.summary()).isEqualTo("Không có phát hiện. [chưa kiểm: price, category]");
    }

    @Test
    void aFindingStillMakesAContentRowAnErrorOrAWarning() {
        assertThat(BulkAssessment.of(VI, detail(rules("PARTIAL", CheckScope.Kind.CONTENT, LEFT_OUT, item("error")))).status())
                .isEqualTo(Status.ERROR);
        assertThat(BulkAssessment.of(VI, detail(rules("PARTIAL", CheckScope.Kind.CONTENT, LEFT_OUT, item("warning")))).status())
                .isEqualTo(Status.WARNING);
    }

    @Test
    void aContentRowThatIsIncompleteIsStillAWarningAndNeverOk() {
        assertThat(BulkAssessment.of(VI, detail(rules("INCOMPLETE", CheckScope.Kind.CONTENT, List.of()))).status())
                .isEqualTo(Status.WARNING);
    }

    @Test
    void aRowOfTheFullCheckIsExactlyWhatItWas() {
        BulkAssessment.Assessed assessed = BulkAssessment.of(VI, detail(rules("READY", CheckScope.Kind.FULL, List.of())));

        assertThat(assessed.status()).isEqualTo(Status.OK);
        assertThat(assessed.summary()).isEqualTo("Không có phát hiện.");
        BulkResult.Rules old = new BulkResult.Rules(true, null, "READY", null, 90, List.of());
        assertThat(old.scope()).isEqualTo(CheckScope.Kind.FULL);
        assertThat(old.skippedFields()).isEmpty();
        assertThat(BulkAssessment.of(VI, detail(old))).isEqualTo(assessed);
    }

    @Test
    void anOkContentRowIsJudgedAndCounted() {
        Row row = new Row(1, 2, "S-1", Status.OK_CONTENT, Status.OK_CONTENT.label(VI), 0, null, "Không có phát hiện.", null);

        assertThat(row.checked()).isTrue();
        BulkResult.Counts counts = BulkResult.Counts.of(List.of(row, new Row(2, 3, "S-2", Status.OK, "Đạt", 0, null, "", null)));
        assertThat(counts.okContent()).isEqualTo(1);
        assertThat(counts.ok()).isEqualTo(1);
        assertThat(counts.judged()).isEqualTo(2);
        assertThat(new BulkResult.Counts(3, 1, 1, 1, 0, 0, 0).okContent()).isZero();
    }

    // ---- what goes back to the Sheet

    private static final List<Row> ROWS = List.of(
            new Row(1, 2, "S-1", Status.OK_CONTENT, "Đạt (nội dung)", 0, null, "Không có phát hiện.", null),
            new Row(2, 3, null, Status.EMPTY, "Dòng trống", 0, null, "", null),
            new Row(3, 4, "S-3", Status.UNREADABLE, "Không đọc được", 0, null, "Không đọc được: giá", null),
            new Row(4, 5, "S-4", Status.ERROR, "Lỗi", 1, null, "1 phát hiện.", null));

    @Test
    void aContentBlockHasAThirdCellThatNamesTheScopeForEveryJudgedRowOnly() {
        String[] lines = BulkExport.sheetBlock(VI, ROWS, true, true).split("\n", -1);

        assertThat(lines).hasSize(ROWS.size() + 1);
        assertThat(lines[0]).isEqualTo("Trạng thái\tTóm tắt\tPhạm vi");
        assertThat(lines[1]).isEqualTo("Đạt (nội dung)\tKhông có phát hiện.\tKiểm nội dung");
        assertThat(lines[2]).as("a blank row is a blank line of three empty cells").isEqualTo("\t\t");
        assertThat(lines[3]).as("a row that was not judged has no scope").isEqualTo("Không đọc được\tKhông đọc được: giá\t");
        assertThat(lines[4]).isEqualTo("Lỗi\t1 phát hiện.\tKiểm nội dung");
    }

    @Test
    void aFullBlockAndAFullCsvAreExactlyTheOnesTheyWere() {
        assertThat(BulkExport.sheetBlock(VI, ROWS, true, false)).isEqualTo(BulkExport.sheetBlock(VI, ROWS, true));
        assertThat(BulkExport.sheetBlock(VI, ROWS, true)).doesNotContain("Phạm vi").doesNotContain("Kiểm nội dung");
        assertThat(BulkExport.csv(VI, ROWS, false)).isEqualTo(BulkExport.csv(VI, ROWS));
        assertThat(BulkExport.csv(VI, ROWS)).doesNotContain("Phạm vi").doesNotContain("Kiểm nội dung");
    }

    @Test
    void aContentCsvHasALastColumnThatNamesTheScopeInBothLanguages() {
        String vi = BulkExport.csv(VI, ROWS, true);
        String en = BulkExport.csv(EN, ROWS, true);

        assertThat(vi).contains("Dòng,SKU,Trạng thái,Số phát hiện,Tóm tắt,Phạm vi\r\n");
        assertThat(vi).contains("2,S-1,Đạt (nội dung),0,Không có phát hiện.,Kiểm nội dung\r\n");
        assertThat(vi).contains("3,,Dòng trống,0,,\r\n");
        assertThat(en).contains("Line,SKU,Status,Findings,Summary,Scope\r\n");
        assertThat(en).contains(",Content check\r\n");
    }
}
