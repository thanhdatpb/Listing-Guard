package com.thanhdatpb.listingguard.api.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRoles;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Mapping;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.TemplateColumn;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceProperties.EditScope;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.Mockito;
import org.springframework.util.unit.DataSize;

/**
 * Everything the plan tests (no database) build: the mocked stores, the service, a flat file like Amazon's with a settings row and a label
 * row above the field names, and the actors. Every SKU, title and e-mail is invented.
 */
final class PlanFixtures {

    static final List<String> FIELDS = List.of("item_sku", "item_name", "parentage", "parent_sku", "bullet_point1", "bullet_point2",
            "size_name", "x_custom_flag");

    final UUID client = UUID.randomUUID();
    final UUID accountId = UUID.randomUUID();
    final UUID adminId = UUID.randomUUID();
    final UUID memberId = UUID.randomUUID();
    final UUID otherMemberId = UUID.randomUUID();
    final Actor admin = new Actor(adminId, client, "admin@example.test", true);
    final Actor member = new Actor(memberId, client, "member@example.test", false);
    final WorkspaceAccounts accounts = Mockito.mock(WorkspaceAccounts.class);
    final WorkspaceTemplates templates = Mockito.mock(WorkspaceTemplates.class);
    final WorkspaceListings listings = Mockito.mock(WorkspaceListings.class);
    final ConsoleAccountRoles roles = Mockito.mock(ConsoleAccountRoles.class);
    final ImportStages stages = Mockito.mock(ImportStages.class);
    final StandardTargets targets = StandardTargets.load();
    final EtsyFields etsy = EtsyFields.load();
    final ListingImportService service;
    final TemplateService templateService;
    final WorkspaceAccess access;

    PlanFixtures(EditScope scope) {
        WorkspaceProperties properties = properties(scope);
        access = new WorkspaceAccess(roles, properties);
        templateService = new TemplateService(accounts, templates, listings, etsy, targets, properties);
        service = new ListingImportService(accounts, templates, listings, templateService, stages, roles, access, targets, etsy,
                new ObjectMapper());
        Mockito.when(roles.emails(client)).thenReturn(Map.of(adminId, "admin@example.test", memberId, "member@example.test",
                otherMemberId, "other@example.test"));
        Mockito.when(templates.current(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(java.util.Optional.empty());
        Mockito.when(listings.existing(Mockito.any(), Mockito.any(), Mockito.anyCollection())).thenReturn(Map.of());
        Mockito.when(listings.existingSkus(Mockito.any(), Mockito.any(), Mockito.anyCollection())).thenReturn(java.util.Set.of());
        Mockito.when(listings.parentsReferenced(Mockito.any(), Mockito.any(), Mockito.anyCollection())).thenReturn(java.util.Set.of());
        Mockito.when(listings.customizations(Mockito.any(), Mockito.any(), Mockito.anyCollection())).thenReturn(Map.of());
    }

    static WorkspaceProperties properties(EditScope scope) {
        return new WorkspaceProperties(true, scope, DataSize.ofMegabytes(10), DataSize.ofMegabytes(20), 10_000, 1500, 20, Duration.ofHours(1),
                0.01, DataSize.ofMegabytes(256), DataSize.ofMegabytes(64));
    }

    SellerAccount account(Marketplace marketplace) {
        SellerAccount account = new SellerAccount(accountId, client, marketplace, "Shop One", null, ExportFormat.XLSM, true, Instant.now());
        Mockito.when(accounts.find(client, accountId)).thenReturn(java.util.Optional.of(account));
        return account;
    }

    /** Settings row, label row, field-name row, then the data rows given. */
    static SheetGrid grid(List<String> fields, List<List<String>> data) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("TemplateType=synthetic", "Version=0"));
        rows.add(fields.stream().map(f -> "Label of " + f).toList());
        rows.add(fields);
        rows.addAll(data);
        return new SheetGrid("Template", rows);
    }

    static List<String> row(String... cells) {
        return List.of(cells);
    }

    /** The mapping a person would confirm for FIELDS: what the guess proposes. */
    Params params(TemplateKind kind, SheetGrid grid, UUID owner) {
        return paramsFrom(kind, grid, owner, 3);
    }

    Params paramsFrom(TemplateKind kind, SheetGrid grid, UUID owner, int fieldRow) {
        var view = service.mappingView(admin, accountId, kind, grid, fieldRow);
        Map<Integer, String> targets = new LinkedHashMap<>();
        view.columns().forEach(c -> {
            if (!c.target().isEmpty()) {
                targets.put(c.index(), c.target());
            }
        });
        return new Params(accountId, kind, owner, ListingStatus.DRAFT, "Template", fieldRow, fieldRow + 1, targets, Map.of());
    }

    /** A template version whose header is {@code fields} and whose mapping is {@code params}'. */
    Template template(TemplateKind kind, List<String> fields, Params params) {
        List<TemplateColumn> columns = WorkspaceTemplates.columnsOf(fields, fields.size());
        Mapping mapping = templateService.mapping(kind, columns, params.targets(), params.separators());
        return new Template(UUID.randomUUID(), client, accountId, kind, 1, "XLSX", "t.xlsx", "0".repeat(64), "Template", 3, 4,
                List.of(List.of("settings"), List.of("labels"), fields), columns, mapping, WorkspaceTemplates.headerHash(columns), true,
                Instant.now());
    }
}
