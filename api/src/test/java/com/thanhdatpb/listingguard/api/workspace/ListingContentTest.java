package com.thanhdatpb.listingguard.api.workspace;

import static com.thanhdatpb.listingguard.api.workspace.PlanFixtures.FIELDS;
import static com.thanhdatpb.listingguard.api.workspace.PlanFixtures.grid;
import static com.thanhdatpb.listingguard.api.workspace.PlanFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.ListingImportService.Draft;
import com.thanhdatpb.listingguard.api.workspace.StandardFields.Built;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Mapping;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.TemplateColumn;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceProperties.EditScope;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The editor builds what the import builds (ADR-062 §1), decided without a database: the standard fields, the title, the role and the hash
 * of a listing rebuilt from its stored columns equal what the import made from the file, and a change of one column rebuilds the field
 * it feeds. {@code data} is the only source; nothing is read back from the standard fields.
 */
class ListingContentTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ListingContent content = new ListingContent(StandardTargets.load(), EtsyFields.load(), mapper);

    private static final List<List<String>> FAMILY = List.of(
            row("P-1", "Synthetic shirt", "parent", "", "", "", "", ""),
            row("P-1-S", "Synthetic shirt S", "child", "P-1", "Soft", "Warm, snug", "S", "yes"),
            row("S-2", "Synthetic mug", "", "", "Ceramic", "", "", ""));

    private Template amazonTemplate(PlanFixtures f, Params params) {
        return f.template(TemplateKind.AMAZON_BULK, FIELDS, params);
    }

    private Template etsyTemplate(PlanFixtures f) {
        EtsyFields etsy = EtsyFields.load();
        List<TemplateColumn> columns = new java.util.ArrayList<>();
        Map<String, String> identity = new LinkedHashMap<>();
        for (int i = 0; i < etsy.fields().size(); i++) {
            columns.add(new TemplateColumn(i, etsy.fields().get(i), etsy.fields().get(i)));
            identity.put(etsy.fields().get(i), etsy.fields().get(i));
        }
        return new Template(UUID.randomUUID(), f.client, f.accountId, TemplateKind.ETSY_FIELDS, 1, "BUILTIN", null, null, null, 1, 2,
                List.of(etsy.fields()), columns, new Mapping(identity, Map.of(), Map.of()), WorkspaceTemplates.headerHash(columns), true, Instant.now());
    }

    @Test
    void aListingRebuiltFromItsColumnsEqualsWhatTheImportBuiltForEveryRow() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, FAMILY);
        Params params = f.params(TemplateKind.AMAZON_BULK, grid, f.memberId);
        Template template = amazonTemplate(f, params);

        for (Draft draft : f.service.prepare(f.admin, params, grid).drafts) {
            Built built = content.build(Marketplace.AMAZON, template, draft.data());

            assertThat(built.fields()).as(draft.sku()).isEqualTo(draft.fields());
            assertThat(built.title()).isEqualTo(draft.title());
            assertThat(built.sku()).isEqualTo(draft.sku());
            assertThat(built.parentSku().isEmpty() ? null : built.parentSku()).isEqualTo(draft.parentSku());
            ListingRole role = ListingContent.role(built, draft.role() == ListingRole.PARENT);
            assertThat(role).isEqualTo(draft.role());
            // one hash function for the import and the editor
            assertThat(ListingContent.hash(draft.data(), role)).isEqualTo(ListingImportService.hash(draft.data(), draft.role()));
        }
    }

    @Test
    void anEtsyListingWithRepeatedTagColumnsAndAnExtraColumnRebuildsTheSame() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.ETSY);
        List<String> header = List.of("SKU", "TITLE", "DESCRIPTION", "TAG1", "TAG2", "TAG3", "PRICE", "Notes");
        SheetGrid grid = new SheetGrid("Listings", List.of(header, row("E-1", "A mug", "Ceramic mug", "mug", "ceramic", "gift", "19.99", "internal")));
        Params params = f.paramsFrom(TemplateKind.ETSY_FIELDS, grid, f.memberId, 1);
        Draft draft = f.service.prepare(f.admin, params, grid).drafts.get(0);

        Built built = content.build(Marketplace.ETSY, etsyTemplate(f), draft.data());

        assertThat(built.fields()).isEqualTo(draft.fields());
        assertThat(built.fields().get("tags")).extracting(n -> n.asText()).containsExactly("mug", "ceramic", "gift");
        assertThat(built.fields().has("extra:Notes")).isFalse();
    }

    @Test
    void changingABulletAndTheSearchTermsRebuildsExactlyTheFieldsTheyFeed() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(List.of("item_sku", "item_name", "bullet_point1", "bullet_point2", "generic_keywords", "x_custom_flag"),
                List.of(row("A-1", "A title", "Soft", "Warm", "gift", "yes")));
        Params params = f.params(TemplateKind.AMAZON_BULK, grid, f.memberId);
        Template template = f.template(TemplateKind.AMAZON_BULK, List.of("item_sku", "item_name", "bullet_point1", "bullet_point2", "generic_keywords", "x_custom_flag"), params);
        Draft draft = f.service.prepare(f.admin, params, grid).drafts.get(0);

        Map<String, String> data = new LinkedHashMap<>(draft.data());
        data.put("bullet_point2", "Warmer than before");
        data.put("generic_keywords", "gift present");
        Built built = content.build(Marketplace.AMAZON, template, data);

        assertThat(built.fields().get("bullet_points")).extracting(n -> n.asText()).containsExactly("Soft", "Warmer than before");
        assertThat(built.fields().path("search_terms").asText()).isEqualTo("gift present");
        assertThat(built.fields().path("title").asText()).isEqualTo("A title");
        // the passthrough column is in the data and never in the standard fields
        assertThat(built.fields().toString()).doesNotContain("yes");
        assertThat(ListingContent.hash(data, ListingRole.STANDALONE)).isNotEqualTo(ListingContent.hash(draft.data(), ListingRole.STANDALONE));
    }

    @Test
    void theColumnsComeBackInTheOrderOfTheTemplate() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, FAMILY);
        Params params = f.params(TemplateKind.AMAZON_BULK, grid, f.memberId);
        Template template = amazonTemplate(f, params);
        Map<String, String> shuffled = new LinkedHashMap<>();
        shuffled.put("size_name", "S");
        shuffled.put("x_other", "stray");
        shuffled.put("item_sku", "A-1");
        shuffled.put("item_name", "T");

        assertThat(ListingContent.ordered(template, shuffled).keySet()).containsExactly("item_sku", "item_name", "size_name", "x_other");
    }

    // ---------------------------------------------------------------- what a posted form does to the columns

    private ListingEditor.Layout layout(Template template, Map<String, String> data) {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        ListingEditor editor = new ListingEditor(f.access, f.accounts, f.templates, f.listings, f.templateService, content, StandardTargets.load(),
                EtsyFields.load(), event -> { });
        SellerAccount account = new SellerAccount(f.accountId, f.client, Marketplace.AMAZON, "A", null, ExportFormat.XLSM, true, Instant.now());
        return editor.layout(template, account, data, false);
    }

    @Test
    void aPostedFormChangesOnlyTheColumnsTheBrowserSentAndKeepsTheLineBreaksOfTheSheet() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, FAMILY);
        Params params = f.params(TemplateKind.AMAZON_BULK, grid, f.memberId);
        Template template = amazonTemplate(f, params);
        Map<String, String> old = new LinkedHashMap<>();
        old.put("item_sku", "A-1");
        old.put("item_name", "A title");
        old.put("bullet_point1", "line one\nline two");
        old.put("size_name", "S");
        ListingEditor.Layout layout = layout(template, old);

        Map<String, String> posted = new LinkedHashMap<>();
        posted.put("item_sku", "HACKED");              // the SKU is not editable: its posted value is ignored
        posted.put("item_name", "A better title");     // changed
        posted.put("bullet_point1", "line one\r\nline two"); // the browser's line break: the same text
        posted.put("size_name", "  ");                 // blank: the cell is empty, as the import treats it

        Map<String, String> data = ListingEditor.merge(template, old, layout, posted);

        assertThat(data).containsEntry("item_sku", "A-1").containsEntry("item_name", "A better title")
                .containsEntry("bullet_point1", "line one\nline two").doesNotContainKey("size_name");
        // posting what is already stored changes nothing at all
        Map<String, String> same = new LinkedHashMap<>(old);
        same.put("bullet_point1", "line one\r\nline two");
        assertThat(ListingEditor.merge(template, old, layout, same)).isEqualTo(old);
        // a column that was not sent stays as it was
        assertThat(ListingEditor.merge(template, old, layout, Map.of("item_name", "Only this"))).containsEntry("size_name", "S")
                .containsEntry("item_name", "Only this");
    }

    @Test
    void aValueLongerThanACellIsRefused() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, FAMILY);
        Params params = f.params(TemplateKind.AMAZON_BULK, grid, f.memberId);
        Template template = amazonTemplate(f, params);
        Map<String, String> old = Map.of("item_sku", "A-1");
        ListingEditor.Layout layout = layout(template, old);

        assertThatThrownBy(() -> ListingEditor.merge(template, old, layout, Map.of("item_name", "x".repeat(ListingEditor.CELL_MAX + 1))))
                .isInstanceOf(WorkspaceException.class).extracting("code").isEqualTo("value.too_long");
    }

    @Test
    void theFormGroupsTheBulletColumnsTogetherAndFoldsAwayTheColumnsNoFieldWasGiven() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, FAMILY);
        Params params = f.params(TemplateKind.AMAZON_BULK, grid, f.memberId);
        Template template = amazonTemplate(f, params);

        ListingEditor.Layout layout = layout(template, Map.of("item_sku", "A-1", "bullet_point1", "a", "bullet_point2", "b", "x_custom_flag", "yes"));

        assertThat(layout.groups()).filteredOn(g -> g.target().equals("bullet_points")).singleElement()
                .satisfies(g -> assertThat(g.fields()).extracting(ListingEditor.Field::key).containsExactly("bullet_point1", "bullet_point2"));
        assertThat(layout.passthrough()).extracting(ListingEditor.Field::key).containsExactly("x_custom_flag");
        assertThat(layout.all()).filteredOn(ListingEditor.Field::readOnly).extracting(ListingEditor.Field::key).containsExactly("item_sku");
    }
}
