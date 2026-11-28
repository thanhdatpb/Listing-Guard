package com.thanhdatpb.listingguard.api.workspace.audit;

import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.ListingCloner;
import com.thanhdatpb.listingguard.api.workspace.ListingEditor;
import com.thanhdatpb.listingguard.api.workspace.ListingImportService;
import com.thanhdatpb.listingguard.api.workspace.SkuPlan;
import com.thanhdatpb.listingguard.api.workspace.TemplateService.SourceFile;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceProperties;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import com.thanhdatpb.listingguard.workspace.sheets.SheetReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What the tests of the comparison of a clone with its original (ADR-039 §4) share: synthetic Amazon files with a Size column (a variation
 * attribute), the import and the clone through the real services, and the editor that saves a clone the way a person does. No column name here is
 * a real Amazon one (not verified against a real file); every account, SKU and title is invented.
 */
abstract class CloneAuditBase extends AuditIntegrationBase {

    /** One template: the item name first, the size before the brand. */
    protected static final List<String> FIRST = List.of("item_sku", "feed_product_type", "item_name", "brand_name", "bullet_point1",
            "product_description", "size_name", "color_name");
    /** Another template: other order, so the n-th column of a field is not at the same place. */
    protected static final List<String> SECOND = List.of("item_name", "item_sku", "feed_product_type", "size_name", "brand_name",
            "bullet_point1", "product_description", "color_name");

    @Autowired
    protected WorkspaceAccess access;
    @Autowired
    protected ListingImportService imports;
    @Autowired
    protected ListingEditor editor;
    @Autowired
    protected ListingCloner cloner;
    @Autowired
    protected WorkspaceProperties properties;
    @Autowired
    protected AuditContexts contexts;
    @Autowired
    protected ListingAuditor auditor;

    protected Actor admin(Team team) {
        return access.actor(team.admin()).orElseThrow();
    }

    protected Actor member(Team team) {
        return access.actor(team.member()).orElseThrow();
    }

    protected SellerAccount amazonShop(Team team, String name) {
        return accounts.create(team.client().getId(), Marketplace.AMAZON, name, "Nobrand", ExportFormat.XLSM);
    }

    protected static byte[] flatFile(List<String> fields, List<List<String>> rows) {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet template = book.createSheet("Template");
            write(template, 0, List.of("TemplateType=synthetic", "Version=0.0", "Settings=none"));
            write(template, 1, fields.stream().map(f -> "Label of " + f).toList());
            write(template, 2, fields);
            int row = 3;
            for (List<String> cells : rows) {
                write(template, row++, cells);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Sheet sheet, int index, List<String> cells) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < cells.size(); i++) {
            row.createCell(i).setCellValue(cells.get(i));
        }
    }

    /** One row in the order of {@code fields}. */
    protected static List<String> row(List<String> fields, Map<String, String> values) {
        return fields.stream().map(f -> values.getOrDefault(f, "")).toList();
    }

    /** Imports the file as the account's Amazon template and its rows as listings (the real import, through its commit). */
    protected void importFile(Team team, SellerAccount account, byte[] bytes) {
        SheetGrid grid = SheetReader.read(bytes, "Template", properties.limits(false));
        var view = imports.mappingView(admin(team), account.id(), TemplateKind.AMAZON_BULK, grid, 3);
        Map<Integer, String> targets = new LinkedHashMap<>();
        view.columns().forEach(c -> {
            if (!c.target().isEmpty()) {
                targets.put(c.index(), c.target());
            }
        });
        Params params = new Params(account.id(), TemplateKind.AMAZON_BULK, team.memberAccount().getId(), ListingStatus.DRAFT, "Template", 3, 4, targets,
                Map.of(), Set.of(), Set.of());
        imports.commit(admin(team), params, grid, new SourceFile(SheetReader.detect(bytes, properties.limits(false)), "template-file", bytes), null);
    }

    protected Listing bySku(Team team, SellerAccount account, String sku) {
        UUID id = jdbc.queryForObject("SELECT id FROM workspace_listing WHERE client_id = ? AND account_id = ? AND sku = ? AND deleted_at IS NULL",
                UUID.class, team.client().getId(), account.id(), sku);
        return listings.find(team.client().getId(), id).orElseThrow();
    }

    protected Listing reload(Team team, Listing listing) {
        return listings.find(team.client().getId(), listing.id()).orElseThrow();
    }

    /** Clones one listing into {@code target} with the suffix added to its SKU. */
    protected Listing clone(Team team, Listing source, SellerAccount target, String suffix) {
        return cloner.clone(member(team), new ListingCloner.Request(target.id(), List.of(source.id()), false,
                new SkuPlan(SkuPlan.Mode.PATTERN, "", suffix, "", "", null, 0, Map.of()), Map.of(), false)).get(0);
    }

    /** Saves the clone with these columns changed, as the editor page does; the audit hears it after the commit. */
    protected Listing save(Team team, Listing listing, Map<String, String> changes) {
        Listing now = reload(team, listing);
        Map<String, String> posted = new LinkedHashMap<>(now.data());
        posted.putAll(changes);
        editor.save(member(team), now.id(), now.version(), posted);
        return reload(team, listing);
    }

    protected List<Map<String, Object>> cloneFindings(Listing listing) {
        return jdbc.queryForList("SELECT pack, rule_id, level, source, field, span_start, span_end, message, suggestion FROM workspace_listing_finding "
                + "WHERE listing_id = ? AND rule_id = 'clone_leftover_from_source' ORDER BY position", listing.id());
    }

    protected String analysisVersionOf(Listing listing) {
        return jdbc.queryForObject("SELECT analysis_version FROM workspace_listing_audit WHERE listing_id = ?", String.class, listing.id());
    }

    protected static String text(Listing listing, String path) {
        return listing.fields().at(path).asText();
    }
}
