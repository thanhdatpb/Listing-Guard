package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.ListingImportService;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService.Block;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService.Result;
import com.thanhdatpb.listingguard.api.workspace.TemplateService.SourceFile;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceExportLog;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceExports;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceExports.Kind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceExports.Medium;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceProperties;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import com.thanhdatpb.listingguard.workspace.sheets.SheetReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The export with the gate on and the background audit on (ADR-063 §9): a listing that is ready and that the gate now holds back is not
 * exported, one that an ADMIN's reason still holds for is, and the confirmation counts the listings that were not checked for trademark. The
 * real audit worker, a real catalog and real rules run; every client, mark and listing is synthetic.
 */
class WorkspaceExportGateIntegrationTest extends AuditIntegrationBase {

    @Autowired
    ListingImportService imports;
    @Autowired
    ListingStatusService statusService;
    @Autowired
    WorkspaceExports exports;
    @Autowired
    WorkspaceExportLog exportLog;
    @Autowired
    WorkspaceAccess access;
    @Autowired
    WorkspaceProperties properties;

    private Actor admin(Team team) {
        return access.actor(team.admin()).orElseThrow();
    }

    private Actor member(Team team) {
        return access.actor(team.member()).orElseThrow();
    }

    /** A flat file with the fields the rules want, title and all, built with POI: a settings row, the field names, then one row per title. */
    private static byte[] file(String prefix, List<String> titles) throws IOException {
        List<String> fields = List.of("item_sku", "item_name", "brand_name", "product_description", "bullet_point1", "standard_price", "quantity");
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Template");
            Row settings = sheet.createRow(0);
            settings.createCell(0).setCellValue("TemplateType=synthetic");
            Row names = sheet.createRow(1);
            for (int i = 0; i < fields.size(); i++) {
                names.createCell(i).setCellValue(fields.get(i));
            }
            for (int r = 0; r < titles.size(); r++) {
                Row row = sheet.createRow(2 + r);
                row.createCell(0).setCellValue(prefix + (r + 1));
                row.createCell(1).setCellValue(titles.get(r));
                row.createCell(2).setCellValue("Nobrand");
                row.createCell(3).setCellValue("A plain synthetic description of the product.");
                row.createCell(4).setCellValue("A plain synthetic bullet");
                row.createCell(5).setCellValue(19.99);
                row.createCell(6).setCellValue(5);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            return out.toByteArray();
        }
    }

    private SellerAccount importFile(Team team, String name, byte[] bytes) {
        SellerAccount account = accounts.create(team.client().getId(), Marketplace.AMAZON, name, null, ExportFormat.TXT);
        SheetGrid grid = SheetReader.read(bytes, "Template", properties.limits(false));
        Actor admin = admin(team);
        var view = imports.mappingView(admin, account.id(), TemplateKind.AMAZON_BULK, grid, 2);
        Map<Integer, String> targets = new LinkedHashMap<>();
        view.columns().forEach(c -> {
            if (!c.target().isEmpty()) {
                targets.put(c.index(), c.target());
            }
        });
        imports.commit(admin, new Params(account.id(), TemplateKind.AMAZON_BULK, team.memberAccount().getId(), ListingStatus.DRAFT, "Template", 2, 3,
                targets, Map.of(), Set.of(), Set.of()), grid, new SourceFile(SheetReader.detect(bytes, properties.limits(false)), "template-file", bytes), null);
        audit();
        return account;
    }

    private List<UUID> idsOf(Team team, SellerAccount account) {
        return jdbc.queryForList("SELECT id FROM workspace_listing WHERE client_id = ? AND account_id = ? AND deleted_at IS NULL ORDER BY sku", UUID.class,
                team.client().getId(), account.id());
    }

    private Listing find(Team team, SellerAccount account, String sku) {
        return listings.find(team.client().getId(), jdbc.queryForObject("SELECT id FROM workspace_listing WHERE account_id = ? AND sku = ? AND deleted_at IS NULL",
                UUID.class, account.id(), sku)).orElseThrow();
    }

    private WorkspaceExports.Request request(SellerAccount account, List<UUID> ids) {
        return new WorkspaceExports.Request(account.id(), Kind.BULK, Medium.TXT, null, null, ids, false, false, null);
    }

    @Test
    void aReadyListingThatTheGateNowHoldsIsNotExportedAndOneAnAdministratorOpenedIs() throws IOException {
        Team team = team();
        serve("g", "Qualmark");
        SellerAccount account = importFile(team, "Shop Gate", file("G-", List.of("Qualmark ceramic mug one", "Qualmark ceramic mug two",
                "Plain ceramic mug three", "Plain ceramic mug four")));
        Listing q1 = find(team, account, "G-1");
        Listing q2 = find(team, account, "G-2");
        List<Listing> others = List.of(find(team, account, "G-3"), find(team, account, "G-4"));
        var verdicts = statusService.verdicts(team.client().getId(), idsOf(team, account));
        assertThat(verdicts.get(q1.id()).blocks()).contains(Block.ERROR, Block.HIGH);
        assertThat(verdicts.get(q2.id()).blocks()).contains(Block.HIGH);
        List<Listing> clean = others.stream().filter(l -> verdicts.get(l.id()).mayGo()).toList();
        assertThat(clean).as("the plain listings pass the rules of the synthetic template").isNotEmpty();

        // the member cannot move a held listing; the administrator opens one with a reason and the clean ones go ready
        assertThat(statusService.markReady(member(team), List.of(q1.id()), false, null)).singleElement()
                .satisfies(o -> assertThat(o.result()).isEqualTo(Result.BLOCKED));
        assertThat(statusService.markReady(admin(team), List.of(q1.id()), true, "The mark is the seller's own brand")).singleElement()
                .satisfies(o -> assertThat(o.result()).isEqualTo(Result.MOVED_OPENED));
        statusService.markReady(member(team), clean.stream().map(Listing::id).toList(), false, null);
        // the other held listing was ready before the catalog knew the mark (the database is set as it would have been)
        jdbc.update("UPDATE workspace_listing SET status = 'READY_TO_EXPORT' WHERE id = ?", q2.id());

        List<UUID> ids = new ArrayList<>(List.of(q1.id(), q2.id()));
        clean.forEach(l -> ids.add(l.id()));
        WorkspaceExports.Preview preview = exports.preview(admin(team), request(account, ids));

        assertThat(preview.gateActive()).isTrue();
        assertThat(preview.auditActive()).isTrue();
        assertThat(preview.exportable()).isEqualTo(1 + clean.size());
        assertThat(preview.opened()).isEqualTo(1);
        assertThat(preview.untrademarked()).isZero();
        assertThat(preview.held()).singleElement().satisfies(h -> {
            assertThat(h.sku()).isEqualTo("G-2");
            assertThat(h.why()).isEqualTo("gate");
            assertThat(h.blocks()).contains(Block.HIGH);
        });

        WorkspaceExports.Result result = exports.export(admin(team), new WorkspaceExports.Request(account.id(), Kind.BULK, Medium.TXT, null, null, ids, false,
                false, preview.exportable()));

        String text = new String(result.bytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).contains("G-1\t").doesNotContain("G-2\t");
        assertThat(find(team, account, "G-1").status()).isEqualTo(ListingStatus.EXPORTED);
        assertThat(find(team, account, "G-2").status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
        assertThat(listings.exportedAt(team.client().getId(), List.of(q2.id()))).isEmpty();
        var log = exportLog.recent(team.client().getId(), account.id(), 1).get(0);
        assertThat(log.listings()).isEqualTo(1 + clean.size());
        assertThat(log.blocked()).isEqualTo(1);
        assertThat(log.opened()).isEqualTo(1);
        assertThat(log.untrademarked()).isZero();

        // an exported listing sent back to a draft keeps the reason for as long as its text is the text it was given for
        assertThat(statusService.markDraft(admin(team), q1.id())).isTrue();
        assertThat(statusService.verdicts(team.client().getId(), List.of(q1.id())).get(q1.id()).opened()).isNotNull();
    }

    @Test
    void aClientWithNoTrademarkSetUpExportsAndTheConfirmationAndTheLogCountTheListingsUncheckedForTrademark() throws IOException {
        Team team = team();
        SellerAccount account = importFile(team, "Shop Unchecked", file("U-", List.of("Plain ceramic mug one", "Plain ceramic mug two",
                "Plain ceramic mug three")));
        var verdicts = statusService.verdicts(team.client().getId(), idsOf(team, account));
        List<UUID> clean = idsOf(team, account).stream().filter(id -> verdicts.get(id).mayGo()).toList();
        assertThat(clean).isNotEmpty();
        assertThat(clean).allSatisfy(id -> {
            assertThat(verdicts.get(id).trademarkChecked()).isFalse();
            assertThat(verdicts.get(id).trademarkReason()).isEqualTo(AuditTypes.TrademarkReason.NOT_CONFIGURED);
        });
        assertThat(statusService.markReady(member(team), clean, false, null)).extracting(ListingStatusService.Outcome::result).containsOnly(Result.MOVED);

        WorkspaceExports.Preview preview = exports.preview(member(team), request(account, clean));
        assertThat(preview.exportable()).isEqualTo(clean.size());
        assertThat(preview.untrademarked()).isEqualTo(clean.size());
        assertThat(preview.groups().get(0).untrademarked()).isEqualTo(clean.size());
        assertThat(preview.canExport()).isTrue();

        WorkspaceExports.Result result = exports.export(member(team), new WorkspaceExports.Request(account.id(), Kind.BULK, Medium.TXT, null, null, clean,
                false, false, preview.exportable()));

        assertThat(result.untrademarked()).isEqualTo(clean.size());
        assertThat(exportLog.recent(team.client().getId(), account.id(), 1).get(0).untrademarked()).isEqualTo(clean.size());
        assertThat(clean.stream().map(id -> listings.find(team.client().getId(), id).orElseThrow().status()).collect(Collectors.toSet()))
                .containsExactly(ListingStatus.EXPORTED);
    }

    @Test
    void aListingThatWasNeverAuditedIsHeldAtTheExportEvenWhenItWasMadeReadyByTheDatabase() throws IOException {
        Team team = team();
        SellerAccount account = importFile(team, "Shop Never", file("N-", List.of("Plain ceramic mug one")));
        Listing l = find(team, account, "N-1");
        jdbc.update("DELETE FROM workspace_listing_audit WHERE listing_id = ?", l.id());
        jdbc.update("UPDATE workspace_listing SET status = 'READY_TO_EXPORT' WHERE id = ?", l.id());

        WorkspaceExports.Preview preview = exports.preview(admin(team), request(account, List.of(l.id())));

        assertThat(preview.exportable()).isZero();
        assertThat(preview.held()).singleElement().satisfies(h -> {
            assertThat(h.why()).isEqualTo("gate");
            assertThat(h.blocks()).containsExactly(Block.UNCHECKED);
        });
        assertThat(preview.canExport()).isFalse();
    }

    @Test
    void readingTheBytesOfAnExportedFileLeavesTheListingsReadableByTheSameReader() throws IOException {
        // the file the export makes is read by the reader that the import uses, with the same limits
        Team team = team();
        SellerAccount account = importFile(team, "Shop Reader", file("R-", List.of("Plain ceramic mug one", "Plain ceramic mug two")));
        var verdicts = statusService.verdicts(team.client().getId(), idsOf(team, account));
        List<UUID> clean = idsOf(team, account).stream().filter(id -> verdicts.get(id).mayGo()).toList();
        statusService.markReady(member(team), clean, false, null);
        WorkspaceExports.Request request = new WorkspaceExports.Request(account.id(), Kind.BULK, Medium.TXT, null, null, clean, false, false, clean.size());

        WorkspaceExports.Result result = exports.export(member(team), request);

        SheetGrid grid = SheetReader.read(new ByteArrayInputStream(result.bytes()).readAllBytes(), "data", properties.limits(false));
        assertThat(grid.row(1)).containsExactly("TemplateType=synthetic");
        assertThat(grid.row(2)).startsWith("item_sku", "item_name", "brand_name");
        assertThat(grid.rowCount()).isEqualTo(2 + clean.size());
    }
}
