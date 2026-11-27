package com.thanhdatpb.listingguard.api.workspace;

import static com.thanhdatpb.listingguard.api.workspace.PlanFixtures.FIELDS;
import static com.thanhdatpb.listingguard.api.workspace.PlanFixtures.grid;
import static com.thanhdatpb.listingguard.api.workspace.PlanFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Plan;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.RowIssue;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.TemplateAction;
import com.thanhdatpb.listingguard.api.workspace.ListingImportService.Draft;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.Existing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceProperties.EditScope;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * What an import would do, decided without a database (ADR-059): the numbers of the preview, every problem of a row, the roles of a
 * variation family, the template versions, who may change what and the standard fields built from the columns. The stores are mocks; the
 * rules under test are in {@link ListingImportService}.
 */
class ListingImportPlanTest {

    private static final List<List<String>> FAMILY = List.of(
            row("P-1", "Synthetic shirt", "parent", "", "", "", "", ""),
            row("P-1-S", "Synthetic shirt S", "child", "P-1", "Soft", "Warm, snug", "S", "yes"),
            row("P-1-M", "Synthetic shirt M", "child", "P-1", "Soft", "", "M", ""),
            row("S-2", "Synthetic mug", "", "", "Ceramic", "", "", ""));

    private static Map<String, Draft> bySku(ListingImportService.Prepared prepared) {
        Map<String, Draft> drafts = new LinkedHashMap<>();
        prepared.drafts.forEach(d -> drafts.put(d.sku(), d));
        return drafts;
    }

    @Test
    void aFamilyIsReadWithItsRolesFieldsAndPassthrough() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, FAMILY);
        Params params = f.params(TemplateKind.AMAZON_BULK, grid, f.memberId);

        ListingImportService.Prepared prepared = f.service.prepare(f.admin, params, grid);

        assertThat(prepared.plan.counts()).satisfies(c -> {
            assertThat(c.rows()).isEqualTo(4);
            assertThat(c.created()).isEqualTo(4);
            assertThat(c.errors()).isZero();
        });
        assertThat(prepared.action).isEqualTo(TemplateAction.CREATE_FIRST);
        Map<String, Draft> drafts = bySku(prepared);
        assertThat(drafts.get("P-1").role()).isEqualTo(ListingRole.PARENT);
        assertThat(drafts.get("P-1-S").role()).isEqualTo(ListingRole.CHILD);
        assertThat(drafts.get("P-1-S").parentSku()).isEqualTo("P-1");
        assertThat(drafts.get("S-2").role()).isEqualTo(ListingRole.STANDALONE);
        Draft child = drafts.get("P-1-S");
        assertThat(child.title()).isEqualTo("Synthetic shirt S");
        assertThat(child.fields().get("bullet_points")).extracting(n -> n.asText()).containsExactly("Soft", "Warm, snug");
        assertThat(child.fields().path("attributes").path("size_name").asText()).isEqualTo("S");
        // a column that no standard field was given is kept as it is, and is not a standard field
        assertThat(child.data()).containsEntry("x_custom_flag", "yes");
        assertThat(child.fields().has("x_custom_flag")).isFalse();
        // the SKU and the parent SKU name the row; they are not fields of the listing
        assertThat(child.fields().has("item_sku")).isFalse();
        assertThat(child.owner()).isEqualTo(f.memberId);
    }

    @Test
    void aCellIsKeptExactlyAsWrittenInTheData() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, List.of(row("A-1", "  Padded title  ", "", "", "", "", "", "")));

        ListingImportService.Prepared prepared = f.service.prepare(f.admin, f.params(TemplateKind.AMAZON_BULK, grid, null), grid);

        assertThat(prepared.drafts.get(0).data().get("item_name")).isEqualTo("  Padded title  ");
        assertThat(prepared.drafts.get(0).title()).isEqualTo("Padded title");
    }

    @Test
    void everyProblemOfARowIsReportedAndTheRowIsNotWritten() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, List.of(
                row("", "No SKU", "", "", "", "", "", ""),
                row("D-1", "First duplicate", "", "", "", "", "", ""),
                row("D-1", "Second duplicate", "", "", "", "", "", ""),
                row("C-1", "Orphan", "child", "NOPE", "", "", "", ""),
                row("C-2", "Self parent", "child", "C-2", "", "", "", ""),
                row("C-3", "Child with no parent", "child", "", "", "", "", ""),
                row("OK-1", "Fine", "", "", "", "", "", "")));

        Plan plan = f.service.plan(f.admin, f.params(TemplateKind.AMAZON_BULK, grid, null), grid);

        assertThat(plan.counts().rows()).isEqualTo(7);
        assertThat(plan.counts().created()).isEqualTo(1);
        assertThat(plan.counts().errors()).isEqualTo(6);
        assertThat(plan.issues()).extracting(RowIssue::line, RowIssue::code).containsExactly(
                org.assertj.core.groups.Tuple.tuple(4, "missing_sku"),
                org.assertj.core.groups.Tuple.tuple(5, "duplicate_sku"),
                org.assertj.core.groups.Tuple.tuple(6, "duplicate_sku"),
                org.assertj.core.groups.Tuple.tuple(7, "parent_missing"),
                org.assertj.core.groups.Tuple.tuple(8, "parent_self"),
                org.assertj.core.groups.Tuple.tuple(9, "child_without_parent"));
    }

    @Test
    void aParentThatIsAlreadyInTheWorkspaceIsAccepted() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        Mockito.when(f.listings.existingSkus(Mockito.eq(f.client), Mockito.eq(f.accountId), Mockito.anyCollection())).thenReturn(Set.of("OLD-PARENT"));
        SheetGrid grid = grid(FIELDS, List.of(row("N-1", "Child of an old parent", "child", "OLD-PARENT", "", "", "", "")));

        Plan plan = f.service.plan(f.admin, f.params(TemplateKind.AMAZON_BULK, grid, null), grid);

        assertThat(plan.counts().errors()).isZero();
        assertThat(plan.counts().created()).isEqualTo(1);
    }

    @Test
    void aSkuThatIsNamedAsAParentByAnExistingListingBecomesAParent() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        Mockito.when(f.listings.parentsReferenced(Mockito.eq(f.client), Mockito.eq(f.accountId), Mockito.anyCollection())).thenReturn(Set.of("P-9"));
        SheetGrid grid = grid(FIELDS, List.of(row("P-9", "Parent re-imported alone", "", "", "", "", "", "")));

        ListingImportService.Prepared prepared = f.service.prepare(f.admin, f.params(TemplateKind.AMAZON_BULK, grid, null), grid);

        assertThat(prepared.drafts.get(0).role()).isEqualTo(ListingRole.PARENT);
    }

    @Test
    void aRowThatMatchesTheStoredListingIsUnchangedAndAChangedOneIsUpdated() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, FAMILY);
        Params params = f.params(TemplateKind.AMAZON_BULK, grid, f.memberId);
        Template template = f.template(TemplateKind.AMAZON_BULK, FIELDS, params);
        Mockito.when(f.templates.current(f.client, f.accountId, TemplateKind.AMAZON_BULK)).thenReturn(java.util.Optional.of(template));
        // what a first import stored, taken from the plan itself
        ListingImportService.Prepared first = f.service.prepare(f.admin, params, grid);
        assertThat(first.action).isEqualTo(TemplateAction.USE_EXISTING);
        Map<String, Existing> stored = new LinkedHashMap<>();
        for (Draft d : first.drafts) {
            stored.put(d.sku(), new Existing(UUID.randomUUID(), template.id(), ListingImportService.hash(d.data(), d.role()), d.owner(), 0,
                    d.role().name()));
        }
        Mockito.when(f.listings.existing(Mockito.eq(f.client), Mockito.eq(f.accountId), Mockito.anyCollection())).thenReturn(stored);

        Plan again = f.service.plan(f.admin, params, grid);
        assertThat(again.counts().unchanged()).isEqualTo(4);
        assertThat(again.counts().created()).isZero();
        assertThat(again.counts().updated()).isZero();

        List<List<String>> edited = new java.util.ArrayList<>(FAMILY);
        edited.set(3, row("S-2", "Synthetic mug, edited", "", "", "Ceramic", "", "", ""));
        Plan changed = f.service.plan(f.admin, params, grid(FIELDS, edited));
        assertThat(changed.counts().updated()).isEqualTo(1);
        assertThat(changed.counts().unchanged()).isEqualTo(3);
    }

    @Test
    void aDifferentFieldNameRowIsANewTemplateVersionThatOnlyAnAdminMayMake() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid oldGrid = grid(FIELDS, FAMILY);
        Params oldParams = f.params(TemplateKind.AMAZON_BULK, oldGrid, null);
        Template current = f.template(TemplateKind.AMAZON_BULK, FIELDS, oldParams);
        Mockito.when(f.templates.current(f.client, f.accountId, TemplateKind.AMAZON_BULK)).thenReturn(java.util.Optional.of(current));
        List<String> newFields = new java.util.ArrayList<>(FIELDS);
        newFields.add("brand_name");
        SheetGrid newGrid = grid(newFields, List.of(row("S-2", "Synthetic mug", "", "", "", "", "", "", "Nobrand")));
        Params newParams = f.params(TemplateKind.AMAZON_BULK, newGrid, null);

        Plan asAdmin = f.service.plan(f.admin, newParams, newGrid);
        Plan asMember = f.service.plan(f.member, newParams, newGrid);

        assertThat(asAdmin.templateAction()).isEqualTo(TemplateAction.CREATE_NEW_VERSION);
        assertThat(asAdmin.blocking()).isNull();
        assertThat(asAdmin.headerAdded()).containsExactly("brand_name");
        assertThat(asAdmin.headerRemoved()).isEmpty();
        assertThat(asMember.blocking()).isEqualTo("template.admin_required");
        assertThat(asMember.canWrite()).isFalse();
    }

    @Test
    void aMemberOnlyChangesListingsTheyAreResponsibleForWhileScopeIsOwn() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, List.of(row("MINE", "Mine", "", "", "", "", "", ""), row("THEIRS", "Theirs", "", "", "", "", "", ""),
                row("NOBODYS", "Nobodys", "", "", "", "", "", ""), row("NEW", "New", "", "", "", "", "", "")));
        Params params = f.params(TemplateKind.AMAZON_BULK, grid, null);
        Template template = f.template(TemplateKind.AMAZON_BULK, FIELDS, params);
        Mockito.when(f.templates.current(f.client, f.accountId, TemplateKind.AMAZON_BULK)).thenReturn(java.util.Optional.of(template));
        Mockito.when(f.listings.existing(Mockito.eq(f.client), Mockito.eq(f.accountId), Mockito.anyCollection())).thenReturn(Map.of(
                "MINE", new Existing(UUID.randomUUID(), template.id(), "x", f.memberId, 0, "STANDALONE"),
                "THEIRS", new Existing(UUID.randomUUID(), template.id(), "x", f.otherMemberId, 0, "STANDALONE"),
                "NOBODYS", new Existing(UUID.randomUUID(), template.id(), "x", null, 0, "STANDALONE")));

        ListingImportService.Prepared own = f.service.prepare(f.member, params, grid);

        assertThat(own.plan.issues()).extracting(RowIssue::sku, RowIssue::code).containsExactly(
                org.assertj.core.groups.Tuple.tuple("THEIRS", "not_owner"), org.assertj.core.groups.Tuple.tuple("NOBODYS", "not_owner"));
        assertThat(own.plan.counts().updated()).isEqualTo(1);
        assertThat(own.plan.counts().created()).isEqualTo(1);
        assertThat(bySku(own).get("NEW").owner()).isEqualTo(f.memberId);

        PlanFixtures all = new PlanFixtures(EditScope.ALL);
        all.account(Marketplace.AMAZON);
        Template allTemplate = all.template(TemplateKind.AMAZON_BULK, FIELDS, all.params(TemplateKind.AMAZON_BULK, grid, null));
        Mockito.when(all.templates.current(all.client, all.accountId, TemplateKind.AMAZON_BULK)).thenReturn(java.util.Optional.of(allTemplate));
        Mockito.when(all.listings.existing(Mockito.eq(all.client), Mockito.eq(all.accountId), Mockito.anyCollection())).thenReturn(Map.of(
                "THEIRS", new Existing(UUID.randomUUID(), allTemplate.id(), "x", all.otherMemberId, 0, "STANDALONE")));
        assertThat(all.service.plan(all.member, all.params(TemplateKind.AMAZON_BULK, grid, null), grid).counts().errors()).isZero();
    }

    @Test
    void anAdminsOwnerColumnNamesAnAccountOfTheTeamAndAnUnknownEmailIsAnError() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        List<String> fields = List.of("item_sku", "item_name", "owner");
        SheetGrid grid = grid(fields, List.of(row("A-1", "One", "Member@Example.test"), row("A-2", "Two", "stranger@example.test"),
                row("A-3", "Three", "")));
        Params params = f.params(TemplateKind.AMAZON_BULK, grid, f.adminId);

        ListingImportService.Prepared prepared = f.service.prepare(f.admin, params, grid);

        assertThat(prepared.plan.issues()).extracting(RowIssue::sku, RowIssue::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("A-2", "owner_unknown"));
        assertThat(bySku(prepared).get("A-1").owner()).isEqualTo(f.memberId);
        assertThat(bySku(prepared).get("A-3").owner()).isEqualTo(f.adminId);
    }

    @Test
    void aDefaultOwnerOutsideTheClientIsRefused() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, FAMILY);
        Params params = f.params(TemplateKind.AMAZON_BULK, grid, UUID.randomUUID());

        assertThatThrownBy(() -> f.service.plan(f.admin, params, grid)).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("owner.unknown"));
    }

    @Test
    void theMappingNeedsExactlyOneSkuAndOnlyTargetsOfTheKind() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, FAMILY);
        Params good = f.params(TemplateKind.AMAZON_BULK, grid, null);

        Map<Integer, String> noSku = new LinkedHashMap<>(good.targets());
        noSku.remove(0);
        assertThatThrownBy(() -> f.service.plan(f.admin, withTargets(good, noSku), grid)).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("mapping.sku.required"));
        Map<Integer, String> twoSkus = new LinkedHashMap<>(good.targets());
        twoSkus.put(3, "sku");
        assertThatThrownBy(() -> f.service.plan(f.admin, withTargets(good, twoSkus), grid)).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("mapping.target.duplicate"));
        Map<Integer, String> unknown = new LinkedHashMap<>(good.targets());
        unknown.put(7, "who_made");
        assertThatThrownBy(() -> f.service.plan(f.admin, withTargets(good, unknown), grid)).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("mapping.target.unknown"));
        Map<Integer, String> twoTitles = new LinkedHashMap<>(good.targets());
        twoTitles.put(7, "title");
        assertThatThrownBy(() -> f.service.plan(f.admin, withTargets(good, twoTitles), grid)).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("mapping.target.duplicate"));
    }

    private static Params withTargets(Params base, Map<Integer, String> targets) {
        return new Params(base.accountId(), base.kind(), base.defaultOwner(), base.newStatus(), base.sheet(), base.fieldRow(),
                base.firstDataRow(), targets, base.separators());
    }

    @Test
    void blankRowsAreSkippedAndCellsRightOfTheFieldRowAreCountedAsIgnored() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        List<String> wide = new java.util.ArrayList<>(row("A-1", "One", "", "", "", "", "", ""));
        wide.add("stray");
        SheetGrid grid = grid(FIELDS, List.of(wide, row("", "", "", "", "", "", "", ""), row("A-2", "Two", "", "", "", "", "", "")));

        Plan plan = f.service.plan(f.admin, f.params(TemplateKind.AMAZON_BULK, grid, null), grid);

        assertThat(plan.counts().rows()).isEqualTo(2);
        assertThat(plan.ignoredCells()).isEqualTo(1);
    }

    @Test
    void aMappingStartsFromWhatTheTemplateRemembersThenFromAGuessAndSaysWhich() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, FAMILY);
        var guessed = f.service.mappingView(f.admin, f.accountId, TemplateKind.AMAZON_BULK, grid, 3);

        assertThat(guessed.columns()).filteredOn(c -> c.target().equals("sku")).extracting(c -> c.origin()).containsExactly("guessed");
        assertThat(guessed.columns().get(0).header()).isEqualTo("item_sku");
        assertThat(guessed.columns()).filteredOn(c -> c.target().equals("bullet_points")).hasSize(2);
        assertThat(guessed.columns().get(7).target()).isEmpty();
        assertThat(guessed.columns().get(7).origin()).isEqualTo("none");

        // an administrator changes the last column to the title; the next import starts from it and says it is remembered
        Params edited = withTargets(f.params(TemplateKind.AMAZON_BULK, grid, null), Map.of(0, "sku", 7, "description"));
        Template template = f.template(TemplateKind.AMAZON_BULK, FIELDS, edited);
        Mockito.when(f.templates.current(f.client, f.accountId, TemplateKind.AMAZON_BULK)).thenReturn(java.util.Optional.of(template));
        var remembered = f.service.mappingView(f.member, f.accountId, TemplateKind.AMAZON_BULK, grid, 3);
        assertThat(remembered.columns().get(7).target()).isEqualTo("description");
        assertThat(remembered.columns().get(7).origin()).isEqualTo("remembered");
        // a column the template already has and the administrator left as it is stays as it is: a guess never overrides a confirmed choice
        assertThat(remembered.columns().get(1).target()).isEmpty();
    }

    @Test
    void anEtsySheetFillsTheFixedFieldsAndKeepsWhatItCannotPlace() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.ETSY);
        List<String> header = List.of("SKU", "TITLE", "DESCRIPTION", "TAG1", "TAG2", "TAG3", "PRICE", "Notes");
        SheetGrid grid = new SheetGrid("Listings", List.of(header, row("E-1", "A mug", "Ceramic mug", "mug", "ceramic", "gift", "19.99", "internal")));
        Params params = f.paramsFrom(TemplateKind.ETSY_FIELDS, grid, f.memberId, 1);

        ListingImportService.Prepared prepared = f.service.prepare(f.admin, params, grid);

        assertThat(prepared.action).isEqualTo(TemplateAction.BUILTIN);
        assertThat(prepared.plan.counts().created()).isEqualTo(1);
        Draft draft = prepared.drafts.get(0);
        assertThat(draft.fields().get("tags")).extracting(n -> n.asText()).containsExactly("mug", "ceramic", "gift");
        assertThat(draft.fields().path("price").asText()).isEqualTo("19.99");
        assertThat(draft.data()).containsEntry("tags", "mug").containsEntry("tags~2", "ceramic").containsEntry("tags~3", "gift");
        assertThat(draft.data()).containsEntry("extra:Notes", "internal");
        assertThat(draft.fields().has("extra:Notes")).isFalse();
        assertThat(prepared.mapping.source()).containsEntry("TAG2", "tags").containsEntry("TITLE", "title");
    }

    @Test
    void aListSeparatorCutsOneCellIntoEntries() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.ETSY);
        SheetGrid grid = new SheetGrid("Listings", List.of(row("SKU", "Tags"), row("E-1", "mug, ceramic ,gift,")));
        Params base = f.paramsFrom(TemplateKind.ETSY_FIELDS, grid, null, 1);
        Params withComma = new Params(base.accountId(), base.kind(), base.defaultOwner(), ListingStatus.LIVE, base.sheet(), base.fieldRow(),
                base.firstDataRow(), base.targets(), Map.of("tags", "comma"));

        Draft draft = f.service.prepare(f.admin, withComma, grid).drafts.get(0);

        assertThat(draft.fields().get("tags")).extracting(n -> n.asText()).containsExactly("mug", "ceramic", "gift");
    }

    @Test
    void anAmazonCustomSheetIsKeyedBySkuAndTellsWhichRowsHaveNoListingYet() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        Mockito.when(f.listings.existingSkus(Mockito.eq(f.client), Mockito.eq(f.accountId), Mockito.anyCollection())).thenReturn(Set.of("P-1-S"));
        List<String> fields = List.of("item_sku", "surface_name", "text_input_label");
        SheetGrid grid = grid(fields, List.of(row("P-1-S", "Front", "Name"), row("GHOST", "Front", "Number"), row("P-1-S", "Back", "Name"),
                row("", "Front", "Nothing")));
        Params params = f.params(TemplateKind.AMAZON_CUSTOM, grid, null);

        Plan plan = f.service.plan(f.admin, params, grid);

        assertThat(plan.counts().rows()).isEqualTo(4);
        assertThat(plan.counts().errors()).isEqualTo(3);
        assertThat(plan.counts().created()).isEqualTo(1);
        assertThat(plan.customizationsUnlinked()).isEqualTo(1);
        assertThat(plan.issues()).extracting(RowIssue::sku, RowIssue::code).contains(org.assertj.core.groups.Tuple.tuple("GHOST", "customization_unlinked"));
    }

    @Test
    void aTemplateKindOfAnotherMarketplaceIsRefused() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.ETSY);
        SheetGrid grid = grid(FIELDS, FAMILY);

        assertThatThrownBy(() -> f.service.plan(f.admin, f.params(TemplateKind.AMAZON_BULK, grid, null), grid))
                .isInstanceOfSatisfying(WorkspaceException.class, e -> assertThat(e.code()).isEqualTo("kind.marketplace"));
    }

    @Test
    void aFieldRowThatIsEmptyOrAfterTheDataIsRefused() {
        PlanFixtures f = new PlanFixtures(EditScope.OWN);
        f.account(Marketplace.AMAZON);
        SheetGrid grid = grid(FIELDS, FAMILY);
        Params good = f.params(TemplateKind.AMAZON_BULK, grid, null);

        assertThatThrownBy(() -> f.service.plan(f.admin, new Params(good.accountId(), good.kind(), null, ListingStatus.DRAFT, "Template", 40, 41,
                good.targets(), Map.of()), grid)).isInstanceOfSatisfying(WorkspaceException.class, e -> assertThat(e.code()).isEqualTo("header.empty"));
        assertThatThrownBy(() -> f.service.plan(f.admin, new Params(good.accountId(), good.kind(), null, ListingStatus.DRAFT, "Template", 3, 3,
                good.targets(), Map.of()), grid)).isInstanceOfSatisfying(WorkspaceException.class, e -> assertThat(e.code()).isEqualTo("rows.order"));
    }
}
