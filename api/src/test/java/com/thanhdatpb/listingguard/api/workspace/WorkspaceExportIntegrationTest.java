package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Result;
import com.thanhdatpb.listingguard.api.workspace.TemplateService.SourceFile;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceExports.Kind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceExports.Medium;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.ListingFilter;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetFormat;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import com.thanhdatpb.listingguard.workspace.sheets.SheetReader;
import com.thanhdatpb.listingguard.workspace.sheets.SheetValidations;
import com.thanhdatpb.listingguard.workspace.sheets.TextEncoding;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The export of ADR-063 on a real database with synthetic files: import a template (an {@code .xlsm} with a settings row, a label row, data
 * validations, a second sheet, a defined name and a stand-in for the macro project) and its listings, mark them ready, export, read the file
 * back, and compare it with what was imported cell by cell. Every test also checks the final state in the database: the status, the time, the
 * history entry and the export log. Nothing here was read from a real Amazon template; the gate is switched off in this context (its combinations
 * are in {@code ListingStatusGateTest} and {@code WorkspaceGateIntegrationTest}; the export with the gate on is in
 * {@code WorkspaceExportGateIntegrationTest}).
 */
@SpringBootTest(properties = {
        "listing-guard.workspace.enabled=true",
        "listing-guard.workspace.block-on-error=false",
        "spring.servlet.multipart.max-file-size=25MB",
        "spring.servlet.multipart.max-request-size=60MB",
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class WorkspaceExportIntegrationTest {

    private record Team(Client client, Actor admin, Actor member, Actor other) {
    }

    @Autowired
    ClientProvisioning provisioning;
    @Autowired
    ConsoleAccountRepository console;
    @Autowired
    WorkspaceAccess access;
    @Autowired
    WorkspaceAccounts accounts;
    @Autowired
    WorkspaceTemplates templates;
    @Autowired
    WorkspaceListings listings;
    @Autowired
    ListingImportService imports;
    @Autowired
    ListingStatusService statusService;
    @Autowired
    WorkspaceExports exports;
    @Autowired
    WorkspaceExportLog exportLog;
    @Autowired
    WorkspaceProperties properties;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    com.thanhdatpb.listingguard.api.client.ClientRepository clients;
    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate transaction;

    @MockitoBean
    JavaMailSender mailSender;

    // ------------------------------------------------------------------ setup

    private Team team() {
        Client client = provisioning.createClient("wx" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        String id = UUID.randomUUID().toString();
        ConsoleUser admin = new ConsoleUser("admin-" + id + "@example.test", client.getId());
        ConsoleUser member = new ConsoleUser("member-" + id + "@example.test", client.getId());
        ConsoleUser other = new ConsoleUser("other-" + id + "@example.test", client.getId());
        console.saveAndFlush(new ConsoleAccount(admin.email(), client.getId(), ConsoleRole.ADMIN));
        console.saveAndFlush(new ConsoleAccount(member.email(), client.getId(), ConsoleRole.MEMBER));
        console.saveAndFlush(new ConsoleAccount(other.email(), client.getId(), ConsoleRole.MEMBER));
        return new Team(client, access.actor(admin).orElseThrow(), access.actor(member).orElseThrow(), access.actor(other).orElseThrow());
    }

    private SellerAccount amazon(Team team, String name, ExportFormat format) {
        return accounts.create(team.client().getId(), Marketplace.AMAZON, name, null, format);
    }

    private Result run(Actor actor, SellerAccount account, TemplateKind kind, byte[] bytes, String sheet, int fieldRow, UUID owner) {
        SheetGrid grid = SheetReader.read(bytes, sheet, properties.limits(false));
        var view = imports.mappingView(actor, account.id(), kind, grid, fieldRow);
        Map<Integer, String> targets = new LinkedHashMap<>();
        view.columns().forEach(c -> {
            if (!c.target().isEmpty()) {
                targets.put(c.index(), c.target());
            }
        });
        Params params = new Params(account.id(), kind, owner, ListingStatus.DRAFT, sheet, fieldRow, fieldRow + 1, targets, Map.of(), Set.of(), Set.of());
        return imports.commit(actor, params, grid, new SourceFile(SheetReader.detect(bytes, properties.limits(false)), "template-file", bytes), null);
    }

    /** An Amazon account with the template and the five synthetic listings of {@link ExportFixtures}, all owned by the member and marked ready. */
    private SellerAccount readyAccount(Team team, String name, ExportFormat format, byte[] file) {
        SellerAccount account = amazon(team, name, format);
        run(team.admin(), account, TemplateKind.AMAZON_BULK, file, "Template", 3, team.member().accountId());
        statusService.markReady(team.admin(), allIds(team, account), false, null);
        return account;
    }

    private List<UUID> allIds(Team team, SellerAccount account) {
        return jdbc.queryForList("SELECT id FROM workspace_listing WHERE client_id = ? AND account_id = ? AND deleted_at IS NULL ORDER BY sku", UUID.class,
                team.client().getId(), account.id());
    }

    private Listing listing(Team team, SellerAccount account, String sku) {
        UUID id = jdbc.queryForObject("SELECT id FROM workspace_listing WHERE client_id = ? AND account_id = ? AND sku = ? AND deleted_at IS NULL",
                UUID.class, team.client().getId(), account.id(), sku);
        return listings.find(team.client().getId(), id).orElseThrow();
    }

    private WorkspaceExports.Request request(SellerAccount account, List<UUID> ids, Medium medium) {
        return new WorkspaceExports.Request(account.id(), Kind.BULK, medium, null, null, ids, false, false, null);
    }

    /** Previews, then exports with the count the preview showed, the way the page does. */
    private WorkspaceExports.Result export(Actor actor, WorkspaceExports.Request request) {
        int count = exports.preview(actor, request).exportable();
        return exports.export(actor, new WorkspaceExports.Request(request.accountId(), request.kind(), request.medium(), request.encoding(),
                request.templateId(), request.listingIds(), request.checkValues(), request.withHeader(), count));
    }

    private static Map<String, List<String>> bySku(SheetGrid grid, int firstDataRow) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (int r = firstDataRow; r <= grid.rowCount(); r++) {
            List<String> cells = new ArrayList<>(grid.row(r));
            if (!cells.isEmpty()) {
                rows.put(cells.get(0), cells);
            }
        }
        return rows;
    }

    private static List<String> padded(List<String> cells, int width) {
        List<String> padded = new ArrayList<>(cells);
        while (padded.size() < width) {
            padded.add("");
        }
        return padded;
    }

    private static byte[] zipEntry(byte[] zip, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (entry.getName().equals(name)) {
                    return in.readAllBytes();
                }
            }
        }
        return null;
    }

    private long logRows(Team team) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workspace_export_log WHERE client_id = ?", Long.class, team.client().getId());
    }

    // ------------------------------------------------------------------ the round trip: .xlsm

    @Test
    void importThenExportWithoutAnyChangeGivesBackEveryCellOfTheDataRowsAndKeepsTheRestOfTheMacroWorkbook() throws IOException {
        Team team = team();
        byte[] original = ExportFixtures.flatFileXlsm("X-");
        SellerAccount account = readyAccount(team, "Shop One", ExportFormat.XLSM, original);
        SheetGrid before = SheetReader.read(original, "Template", properties.limits(false));
        List<UUID> ids = allIds(team, account);
        assertThat(ids).hasSize(5);

        WorkspaceExports.Result result = export(team.member(), request(account, ids, null));

        // the default of the account is Excel, and the file is a macro workbook again
        assertThat(result.files()).hasSize(1);
        assertThat(result.files().get(0).name()).endsWith(".xlsm");
        assertThat(result.exported()).isEqualTo(5);
        byte[] file = result.bytes();
        assertThat(SheetReader.detect(file, properties.limits(false))).isEqualTo(SheetFormat.XLSM);
        SheetGrid after = SheetReader.read(file, "Template", properties.limits(false));
        // the rows above the data are as they were
        for (int r = 1; r < ExportFixtures.FIRST_DATA_ROW; r++) {
            assertThat(after.row(r)).as("row " + r).isEqualTo(before.row(r));
        }
        // every data row of the import is in the file, cell by cell (the order is the families', parents first)
        Map<String, List<String>> wanted = bySku(before, ExportFixtures.FIRST_DATA_ROW);
        Map<String, List<String>> got = bySku(after, ExportFixtures.FIRST_DATA_ROW);
        assertThat(got.keySet()).containsExactlyInAnyOrderElementsOf(wanted.keySet());
        wanted.forEach((sku, cells) -> assertThat(padded(got.get(sku), 16)).as(sku).isEqualTo(padded(cells, 16)));
        List<String> order = new ArrayList<>(got.keySet());
        assertThat(order).containsExactly("X-P1", "X-P1-M", "X-P1-S", "X-S2", "X-V1");

        // the numbers are numeric cells, the codes and the text stay text
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            var rows = book.getSheet("Template");
            var m = rows.getRow(ExportFixtures.FIRST_DATA_ROW);       // X-P1-M, the second data row of the file
            assertThat(m.getCell(0).getStringCellValue()).isEqualTo("X-P1-M");
            assertThat(m.getCell(13).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(m.getCell(13).getNumericCellValue()).isEqualTo(19.99);
            assertThat(m.getCell(14).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(m.getCell(14).getNumericCellValue()).isEqualTo(7.0);
            var s2 = rows.getRow(ExportFixtures.FIRST_DATA_ROW + 2);
            assertThat(s2.getCell(0).getStringCellValue()).isEqualTo("X-S2");
            assertThat(s2.getCell(15).getCellType()).isEqualTo(CellType.STRING);
            assertThat(s2.getCell(15).getStringCellValue()).isEqualTo("007");
            assertThat(s2.getCell(10).getStringCellValue()).isEqualTo("Line one\nLine two\tafter the tab");
            var v1 = rows.getRow(ExportFixtures.FIRST_DATA_ROW + 3);
            assertThat(v1.getCell(13).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(v1.getCell(13).getNumericCellValue()).isEqualTo(12.0);
            assertThat(v1.getCell(2).getStringCellValue()).isEqualTo("Áo thun đẹp");
            assertThat(book.getName("ProductTypes")).isNotNull();
        }
        // the macro project is the same bytes, the other sheet and the validations are there
        assertThat(zipEntry(file, "xl/vbaProject.bin")).isEqualTo(zipEntry(original, "xl/vbaProject.bin")).isNotNull();
        assertThat(SheetReader.read(file, "Valid Values", properties.limits(false)).row(1)).containsExactly("SHIRT", "MUG");
        assertThat(SheetReader.read(file, "Instructions", properties.limits(false)).row(1)).containsExactly("Read me");
        var rulesBefore = SheetValidations.read(original, "Template", ExportFixtures.FIRST_DATA_ROW, properties.limits(false));
        var rulesAfter = SheetValidations.read(file, "Template", ExportFixtures.FIRST_DATA_ROW, properties.limits(false));
        assertThat(rulesAfter.allowed()).isEqualTo(rulesBefore.allowed());
        assertThat(rulesAfter.allowed().get(1)).containsExactlyInAnyOrder("SHIRT", "MUG");
        assertThat(rulesAfter.allowed().get(11)).containsExactlyInAnyOrder("S", "M", "L");

        // the final state: every listing is exported with a time and an EXPORT entry, and the log has the counts of the file
        for (UUID id : ids) {
            Listing l = listings.find(team.client().getId(), id).orElseThrow();
            assertThat(l.status()).isEqualTo(ListingStatus.EXPORTED);
            assertThat(listings.exportedAt(team.client().getId(), List.of(id))).containsKey(id);
            assertThat(listings.history(team.client().getId(), id)).first().satisfies(h -> {
                assertThat(h.kind().name()).isEqualTo("EXPORT");
                assertThat(h.status()).isEqualTo(ListingStatus.EXPORTED);
                assertThat(h.changedBy()).isEqualTo(team.member().accountId());
            });
        }
        var log = exportLog.recent(team.client().getId(), account.id(), 10);
        assertThat(log).hasSize(1);
        assertThat(log.get(0).format().name()).isEqualTo("XLSM");
        assertThat(log.get(0).listings()).isEqualTo(5);
        assertThat(log.get(0).templateVersion()).isEqualTo(1);
        assertThat(log.get(0).exportedBy()).isEqualTo(team.member().accountId());
        // the gate is off here, so nothing was held, and every listing is unchecked for trademark: the audit is not on
        assertThat(log.get(0).blocked()).isZero();
        assertThat(log.get(0).untrademarked()).isEqualTo(5);
        // an exported listing cannot be exported again until it is a draft and ready again
        assertThat(exports.preview(team.member(), request(account, ids, null)).exportable()).isZero();
    }

    // ------------------------------------------------------------------ the round trip: .txt

    @Test
    void theTextFileGivesBackTheHeaderRowsAndEveryDataCellAndCountsTheCellsItHadToChange() throws IOException {
        Team team = team();
        byte[] original = ExportFixtures.flatFileXlsm("T-");
        SellerAccount account = readyAccount(team, "Shop Text", ExportFormat.TXT, original);
        SheetGrid before = SheetReader.read(original, "Template", properties.limits(false));

        WorkspaceExports.Result result = export(team.admin(), request(account, allIds(team, account), null));

        // the account's default is the text file; the cell with a tab and a line break was changed, and counted
        assertThat(result.files().get(0).name()).endsWith(".txt");
        assertThat(result.replacedCells()).isEqualTo(1);
        String text = new String(result.bytes(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("TemplateType=synthetic\tVersion=0.0\tSettings=none").contains("\r\n");
        SheetGrid grid = SheetReader.read(result.bytes(), "data", properties.limits(false));
        for (int r = 1; r < ExportFixtures.FIRST_DATA_ROW; r++) {
            assertThat(padded(grid.row(r), 16)).as("header row " + r).isEqualTo(padded(before.row(r), 16));
        }
        Map<String, List<String>> wanted = bySku(before, ExportFixtures.FIRST_DATA_ROW);
        Map<String, List<String>> got = bySku(grid, ExportFixtures.FIRST_DATA_ROW);
        assertThat(got.keySet()).containsExactlyInAnyOrderElementsOf(wanted.keySet());
        wanted.forEach((sku, cells) -> {
            List<String> expected = padded(cells, 16).stream().map(c -> c.replaceAll("[\t\r\n]", " ")).toList();
            assertThat(padded(got.get(sku), 16)).as(sku).isEqualTo(expected);
        });
        // a whole number has no ".0", a price keeps its decimals, the code keeps its zeros, the Vietnamese letters are intact
        assertThat(got.get("T-V1")).contains("12", "Áo thun đẹp");
        assertThat(got.get("T-P1-S")).contains("19.99", "5");
        assertThat(got.get("T-S2")).contains("007");
        var log = exportLog.recent(team.client().getId(), account.id(), 1).get(0);
        assertThat(log.format().name()).isEqualTo("TXT");
        assertThat(log.encoding()).isEqualTo("UTF-8");
        assertThat(log.replacedCells()).isEqualTo(1);
    }

    @Test
    void anEncodingThatCannotWriteACharacterRefusesTheWholeExportAndChangesNothing() throws IOException {
        Team team = team();
        SellerAccount account = readyAccount(team, "Shop Latin", ExportFormat.TXT, ExportFixtures.flatFileXlsm("L-"));
        List<UUID> ids = allIds(team, account);
        WorkspaceExports.Request latin = new WorkspaceExports.Request(account.id(), Kind.BULK, Medium.TXT, TextEncoding.ISO_8859_1, null, ids, false, false,
                exports.preview(team.admin(), request(account, ids, Medium.TXT)).exportable());

        assertThatThrownBy(() -> exports.export(team.admin(), latin)).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("export.sheet.unencodable"));

        // the transaction was rolled back: the listings are still ready, nothing is stamped, nothing is logged
        for (UUID id : ids) {
            assertThat(listings.find(team.client().getId(), id).orElseThrow().status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
        }
        assertThat(listings.exportedAt(team.client().getId(), ids)).isEmpty();
        assertThat(logRows(team)).isZero();
        // the same selection goes in an encoding that can write it
        WorkspaceExports.Result bom = export(team.admin(), new WorkspaceExports.Request(account.id(), Kind.BULK, Medium.TXT, TextEncoding.UTF_16LE, null, ids,
                false, false, null));
        assertThat(bom.bytes()).startsWith((byte) 0xFF, (byte) 0xFE);
        assertThat(exportLog.recent(team.client().getId(), account.id(), 1).get(0).encoding()).isEqualTo("UTF-16LE");
    }

    // ------------------------------------------------------------------ the clipboard

    @Test
    void theClipboardTextIsTheDataRowsInTheTemplatesOrderWithOrWithoutTheHeaderRows() throws IOException {
        Team team = team();
        SellerAccount account = readyAccount(team, "Shop Paste", ExportFormat.XLSM, ExportFixtures.flatFileXlsm("C-"));
        List<UUID> ids = allIds(team, account);

        WorkspaceExports.Result withHeader = export(team.admin(), new WorkspaceExports.Request(account.id(), Kind.BULK, Medium.CLIPBOARD, null, null,
                ids.subList(0, 2), false, true, null));

        assertThat(withHeader.files()).isEmpty();
        assertThat(withHeader.clipboard()).startsWith("TemplateType=synthetic\tVersion=0.0\tSettings=none\n");
        String[] lines = withHeader.clipboard().split("\n");
        assertThat(lines).hasSize(5);
        assertThat(lines[2]).startsWith("item_sku\tfeed_product_type\titem_name");
        assertThat(lines[3]).startsWith("C-P1\t");
        assertThat(lines[4]).startsWith("C-P1-M\t");

        WorkspaceExports.Result plain = export(team.admin(), new WorkspaceExports.Request(account.id(), Kind.BULK, Medium.CLIPBOARD, null, null,
                ids.subList(2, 4), false, false, null));
        assertThat(plain.clipboard()).startsWith("C-P1-S\tSHIRT\t").contains("\nC-S2\tMUG\t").endsWith("\t9.5\t3\t007\n");
        // a cell that holds a tab or a line break is quoted, nothing is replaced
        assertThat(plain.clipboard()).contains("\"Line one\nLine two\tafter the tab\"");
        assertThat(plain.replacedCells()).isZero();
        assertThat(exportLog.recent(team.client().getId(), account.id(), 2)).extracting(e -> e.format().name()).containsOnly("CLIPBOARD");
        assertThat(listing(team, account, "C-S2").status()).isEqualTo(ListingStatus.EXPORTED);
    }

    // ------------------------------------------------------------------ a template without a workbook

    @Test
    void aTemplateMadeFromATextFileHasNoExcelOptionAndTheDefaultFallsBackToText() throws IOException {
        Team team = team();
        SellerAccount account = amazon(team, "Shop Tsv", ExportFormat.XLSM);
        run(team.admin(), account, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFileTsv("S-"), "data", 3, team.member().accountId());
        statusService.markReady(team.admin(), allIds(team, account), false, null);
        List<UUID> ids = allIds(team, account);

        WorkspaceExports.Preview defaulted = exports.preview(team.admin(), request(account, ids, null));
        assertThat(defaulted.medium()).isEqualTo(Medium.TXT);
        assertThat(defaulted.mediumFellBack()).isTrue();
        assertThat(defaulted.excelProblem()).isEqualTo("nofile");
        assertThat(defaulted.canExport()).isTrue();

        WorkspaceExports.Preview excel = exports.preview(team.admin(), request(account, ids, Medium.EXCEL));
        assertThat(excel.problems()).contains("excel.nofile");
        assertThat(excel.canExport()).isFalse();
        assertThatThrownBy(() -> exports.export(team.admin(), new WorkspaceExports.Request(account.id(), Kind.BULK, Medium.EXCEL, null, null, ids, false,
                false, excel.exportable()))).isInstanceOfSatisfying(WorkspaceException.class, e -> assertThat(e.code()).isEqualTo("export.blocked"));
        assertThat(listing(team, account, "S-P1").status()).isEqualTo(ListingStatus.READY_TO_EXPORT);

        WorkspaceExports.Result text = export(team.admin(), request(account, ids, null));
        SheetGrid grid = SheetReader.read(text.bytes(), "data", properties.limits(false));
        assertThat(bySku(grid, 4).keySet()).contains("S-P1", "S-P1-S", "S-P1-M", "S-S2");
        assertThat(grid.row(3)).containsExactlyElementsOf(WorkspaceFixtures.FIELDS);
    }

    // ------------------------------------------------------------------ several template versions

    @Test
    void listingsOfSeveralTemplateVersionsGoOneFilePerVersionOrOneVersionAtATime() throws IOException {
        Team team = team();
        SellerAccount account = amazon(team, "Shop Versions", ExportFormat.XLSM);
        UUID owner = team.member().accountId();
        run(team.admin(), account, TemplateKind.AMAZON_BULK, ExportFixtures.flatFileXlsm("V-"), "Template", 3, owner);
        // a header with one more column is the next version; the listings that import it use it, the others stay on the first
        List<String> wider = new ArrayList<>(ExportFixtures.FIELDS);
        wider.add("extra_note");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(new ArrayList<>(List.<Object>of("V-NEW1", "MUG", "A second-version mug", "Nobrand", "", "", "", "", "", "", "Wider", "", "", 8.5, 2, "", "note")));
        run(team.admin(), account, TemplateKind.AMAZON_BULK, WorkspaceFixtures.asMacroEnabled(ExportFixtures.flatFile(wider, rows, true)), "Template", 3,
                owner);
        statusService.markReady(team.admin(), allIds(team, account), false, null);
        List<UUID> ids = allIds(team, account);
        assertThat(ids).hasSize(6);
        assertThat(listing(team, account, "V-P1").templateId()).isNotEqualTo(listing(team, account, "V-NEW1").templateId());

        WorkspaceExports.Preview preview = exports.preview(team.admin(), request(account, ids, Medium.EXCEL));
        assertThat(preview.groups()).extracting(g -> g.template().version()).containsExactly(1, 2);
        assertThat(preview.groups()).extracting(WorkspaceExports.Group::listings).containsExactly(5, 1);
        // the paste needs one version
        assertThat(exports.preview(team.admin(), request(account, ids, Medium.CLIPBOARD)).problems()).contains("clipboard.versions");

        // choosing the newer version exports only its listings; the others stay ready
        UUID newer = listing(team, account, "V-NEW1").templateId();
        WorkspaceExports.Result one = export(team.admin(), new WorkspaceExports.Request(account.id(), Kind.BULK, Medium.EXCEL, null, newer, ids, false,
                false, null));
        assertThat(one.files()).hasSize(1);
        assertThat(one.files().get(0).name()).contains("-v2-");
        assertThat(one.exported()).isEqualTo(1);
        assertThat(listing(team, account, "V-NEW1").status()).isEqualTo(ListingStatus.EXPORTED);
        assertThat(listing(team, account, "V-P1").status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
        SheetGrid second = SheetReader.read(one.files().get(0).bytes(), "Template", properties.limits(false));
        assertThat(second.row(3)).containsExactlyElementsOf(wider);
        assertThat(second.row(4).get(16)).isEqualTo("note");

        // the rest goes as its own file
        List<UUID> rest = ids.stream().filter(id -> !id.equals(listing(team, account, "V-NEW1").id())).toList();
        WorkspaceExports.Result remaining = export(team.admin(), request(account, rest, Medium.EXCEL));
        assertThat(remaining.files()).hasSize(1).first().satisfies(f -> assertThat(f.name()).contains("-v1-"));

        // both at once are two files in one zip, oldest version first
        for (UUID id : ids) {
            assertThat(statusService.markDraft(team.admin(), id)).isTrue();
        }
        statusService.markReady(team.admin(), ids, false, null);
        WorkspaceExports.Result both = export(team.admin(), request(account, ids, Medium.TXT));
        assertThat(both.files()).extracting(WorkspaceExports.ExportFile::name).satisfiesExactly(a -> assertThat(a).contains("-v1-").endsWith(".txt"),
                b -> assertThat(b).contains("-v2-").endsWith(".txt"));
        assertThat(both.downloadName()).endsWith(".zip");
        assertThat(both.contentType()).isEqualTo("application/zip");
        assertThat(zipEntry(both.bytes(), both.files().get(0).name())).isEqualTo(both.files().get(0).bytes());
        assertThat(exportLog.recent(team.client().getId(), account.id(), 10)).extracting(e -> e.templateVersion()).contains(1, 2);
    }

    // ------------------------------------------------------------------ who and what may be exported

    @Test
    void onlyReadyListingsThePersonMayChangeAreExportedAndTheOthersAreListedWithTheirReason() throws IOException {
        Team team = team();
        byte[] file = ExportFixtures.flatFileXlsm("R-");
        SellerAccount account = amazon(team, "Shop Rules", ExportFormat.XLSM);
        run(team.admin(), account, TemplateKind.AMAZON_BULK, file, "Template", 3, team.member().accountId());
        Listing mineReady = listing(team, account, "R-S2");
        Listing mineDraft = listing(team, account, "R-V1");
        Listing othersReady = listing(team, account, "R-P1-S");
        jdbc.update("UPDATE workspace_listing SET owner_account_id = ? WHERE id = ?", team.other().accountId(), othersReady.id());
        statusService.markReady(team.admin(), List.of(mineReady.id(), othersReady.id()), false, null);

        WorkspaceExports.Preview preview = exports.preview(team.member(), request(account, List.of(mineReady.id(), mineDraft.id(), othersReady.id(),
                UUID.randomUUID()), null));

        assertThat(preview.exportable()).isEqualTo(1);
        assertThat(preview.held()).extracting(WorkspaceExports.Held::sku, WorkspaceExports.Held::why).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("R-V1", "not_ready"), org.assertj.core.groups.Tuple.tuple("R-P1-S", "forbidden"));
        assertThat(preview.outside()).isEqualTo(1);
        // the administrator may change the other person's listing
        assertThat(exports.preview(team.admin(), request(account, List.of(mineReady.id(), othersReady.id()), null)).exportable()).isEqualTo(2);

        // exporting what the preview showed leaves the held listings as they were
        WorkspaceExports.Result result = export(team.member(), request(account, List.of(mineReady.id(), mineDraft.id(), othersReady.id()), null));
        assertThat(result.exported()).isEqualTo(1);
        assertThat(result.held()).isEqualTo(2);
        assertThat(listing(team, account, "R-S2").status()).isEqualTo(ListingStatus.EXPORTED);
        assertThat(listing(team, account, "R-V1").status()).isEqualTo(ListingStatus.DRAFT);
        assertThat(listing(team, account, "R-P1-S").status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
        assertThat(exportLog.recent(team.client().getId(), account.id(), 1).get(0).blocked()).isEqualTo(2);
    }

    @Test
    void anExportStopsWhenTheNumberOfListingsIsNoLongerTheOneThePersonConfirmed() throws IOException {
        Team team = team();
        SellerAccount account = readyAccount(team, "Shop Changed", ExportFormat.XLSM, ExportFixtures.flatFileXlsm("H-"));
        List<UUID> ids = allIds(team, account);
        int shown = exports.preview(team.admin(), request(account, ids, null)).exportable();
        // somebody sends one of them back to a draft after the confirmation page was shown
        assertThat(statusService.markDraft(team.admin(), ids.get(0))).isTrue();

        assertThatThrownBy(() -> exports.export(team.admin(), new WorkspaceExports.Request(account.id(), Kind.BULK, null, null, null, ids, false, false,
                shown))).isInstanceOfSatisfying(WorkspaceException.class, e -> assertThat(e.code()).isEqualTo("export.changed"));
        assertThatThrownBy(() -> exports.export(team.admin(), request(account, ids, null))).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("export.changed"));
        assertThat(allIds(team, account).stream().map(id -> listings.find(team.client().getId(), id).orElseThrow().status()).filter(
                s -> s == ListingStatus.EXPORTED)).isEmpty();
        assertThat(logRows(team)).isZero();
    }

    @Test
    void aSelectionOfAnotherClientOrAnotherAccountExportsNothing() throws IOException {
        Team team = team();
        Team stranger = team();
        SellerAccount account = readyAccount(team, "Shop Mine", ExportFormat.XLSM, ExportFixtures.flatFileXlsm("M-"));
        SellerAccount second = readyAccount(team, "Shop Second", ExportFormat.XLSM, ExportFixtures.flatFileXlsm("N-"));
        List<UUID> mine = allIds(team, account);

        // another client cannot even name the account
        assertThatThrownBy(() -> exports.preview(stranger.admin(), request(account, mine, null))).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("account.unknown"));
        // the listings of another account of the same client are not found in this one
        WorkspaceExports.Preview mixed = exports.preview(team.admin(), request(account, allIds(team, second), null));
        assertThat(mixed.exportable()).isZero();
        assertThat(mixed.outside()).isEqualTo(5);
        assertThat(mixed.problems()).isEmpty();
        assertThat(mixed.canExport()).isFalse();
        // and the stranger's own account does not find these listings
        SellerAccount strangers = amazon(stranger, "Shop Stranger", ExportFormat.XLSM);
        assertThat(exports.preview(stranger.admin(), request(strangers, mine, null)).outside()).isEqualTo(5);
        assertThat(exports.preview(stranger.admin(), request(strangers, mine, null)).exportable()).isZero();
    }

    @Test
    void anEtsyAccountAndAnEmptyOrTooLargeSelectionHaveAProblemNotAFile() {
        Team team = team();
        SellerAccount shop = accounts.create(team.client().getId(), Marketplace.ETSY, "Shop Etsy", null, ExportFormat.XLSM);

        assertThat(exports.preview(team.admin(), request(shop, List.of(UUID.randomUUID()), null)).problems()).contains("etsy");
        SellerAccount amazon = amazon(team, "Shop Empty", ExportFormat.XLSM);
        assertThat(exports.preview(team.admin(), request(amazon, List.of(), null)).problems()).contains("none");
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i <= exports.maxListings(); i++) {
            many.add(UUID.randomUUID());
        }
        assertThat(exports.preview(team.admin(), request(amazon, many, null)).problems()).contains("too_many");
    }

    // ------------------------------------------------------------------ values outside the template's lists

    @Test
    void aValueOutsideTheListsOfTheTemplateIsAWarningOnTheConfirmationAndNeverStopsTheExport() throws IOException {
        Team team = team();
        List<List<Object>> data = new ArrayList<>(ExportFixtures.dataRows("W-"));
        data.add(List.of("W-HAT", "HAT", "A synthetic hat", "Nobrand", "", "", "", "", "", "", "A hat", "XL", "", 5, 1, ""));
        SellerAccount account = readyAccount(team, "Shop Values", ExportFormat.XLSM, WorkspaceFixtures.asMacroEnabled(ExportFixtures.flatFile(data)));
        List<UUID> ids = allIds(team, account);

        WorkspaceExports.Request checked = new WorkspaceExports.Request(account.id(), Kind.BULK, null, null, null, ids, true, false, null);
        WorkspaceExports.Preview preview = exports.preview(team.admin(), checked);

        var validation = preview.groups().get(0).validation();
        assertThat(validation.state()).isEqualTo("CHECKED");
        assertThat(validation.listRules()).isEqualTo(2);
        assertThat(validation.complete()).isTrue();
        assertThat(validation.warnings()).extracting(WorkspaceExports.ValueWarning::header, WorkspaceExports.ValueWarning::values).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("feed_product_type", List.of("HAT")),
                org.assertj.core.groups.Tuple.tuple("size_name", List.of("XL")));
        assertThat(preview.warnings()).isEqualTo(2);
        assertThat(preview.canExport()).isTrue();
        // not asked: no check
        assertThat(exports.preview(team.admin(), request(account, ids, null)).groups().get(0).validation().state()).isEqualTo("OFF");

        WorkspaceExports.Result result = export(team.admin(), checked);
        assertThat(result.exported()).isEqualTo(6);
        assertThat(result.warnings()).isEqualTo(2);
        assertThat(exportLog.recent(team.client().getId(), account.id(), 1).get(0).warnings()).isEqualTo(2);
    }

    @Test
    void aTemplateWithoutAnyListRuleIsSkippedAndSaysSo() throws IOException {
        Team team = team();
        SellerAccount account = readyAccount(team, "Shop Plain", ExportFormat.XLSM,
                WorkspaceFixtures.asMacroEnabled(ExportFixtures.flatFile(ExportFixtures.FIELDS, ExportFixtures.dataRows("P-"), false)));

        var preview = exports.preview(team.admin(), new WorkspaceExports.Request(account.id(), Kind.BULK, null, null, null, allIds(team, account), true,
                false, null));

        assertThat(preview.groups().get(0).validation().state()).isEqualTo("NONE");
        assertThat(preview.groups().get(0).validation().warnings()).isEmpty();
        // a template with no workbook has nothing to read the lists from
        SellerAccount tsv = amazon(team, "Shop Plain Tsv", ExportFormat.TXT);
        run(team.admin(), tsv, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFileTsv("Q-"), "data", 3, team.member().accountId());
        statusService.markReady(team.admin(), allIds(team, tsv), false, null);
        assertThat(exports.preview(team.admin(), new WorkspaceExports.Request(tsv.id(), Kind.BULK, null, null, null, allIds(team, tsv), true, false, null))
                .groups().get(0).validation().state()).isEqualTo("NO_FILE");
    }

    // ------------------------------------------------------------------ Amazon Custom

    @Test
    void theCustomizeRowsOfTheSelectedSkusGoInTheCustomTemplateAndNoStatusChanges() throws IOException {
        Team team = team();
        SellerAccount account = readyAccount(team, "Shop Custom", ExportFormat.XLSM, ExportFixtures.flatFileXlsm("U-"));
        byte[] custom = WorkspaceFixtures.customizeFile(List.of(List.of("U-P1-S", "Front", "Your name"), List.of("U-P1-M", "Front", "Your date"),
                List.of("U-UNLINKED", "Back", "Nothing")));
        Result imported = run(team.admin(), account, TemplateKind.AMAZON_CUSTOM, custom, "Custom", 2, team.member().accountId());
        assertThat(imported).isNotNull();
        List<UUID> ids = List.of(listing(team, account, "U-P1-S").id(), listing(team, account, "U-P1-M").id(), listing(team, account, "U-S2").id());

        WorkspaceExports.Preview preview = exports.preview(team.admin(), new WorkspaceExports.Request(account.id(), Kind.CUSTOM, Medium.TXT, null, null,
                ids, false, false, null));
        assertThat(preview.exportable()).isEqualTo(2);
        assertThat(preview.missingCustom()).isEqualTo(1);
        assertThat(preview.groups().get(0).template().kind()).isEqualTo(TemplateKind.AMAZON_CUSTOM);

        WorkspaceExports.Result result = export(team.admin(), new WorkspaceExports.Request(account.id(), Kind.CUSTOM, Medium.TXT, null, null, ids, false,
                false, null));

        SheetGrid grid = SheetReader.read(result.bytes(), "data", properties.limits(false));
        assertThat(grid.row(1)).containsExactly("Custom settings");
        assertThat(grid.row(2)).containsExactly("item_sku", "surface_name", "text_input_label");
        assertThat(bySku(grid, 3)).containsOnlyKeys("U-P1-S", "U-P1-M");
        assertThat(bySku(grid, 3).get("U-P1-M")).containsExactly("U-P1-M", "Front", "Your date");
        assertThat(result.files().get(0).name()).contains("-custom-");
        // the status belongs to the bulk file: it did not change, and no EXPORT entry was written
        assertThat(listing(team, account, "U-P1-S").status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
        assertThat(listings.exportedAt(team.client().getId(), ids)).isEmpty();
        var log = exportLog.recent(team.client().getId(), account.id(), 1).get(0);
        assertThat(log.templateKind()).isEqualTo(TemplateKind.AMAZON_CUSTOM);
        assertThat(log.listings()).isEqualTo(2);
        // and the bulk file of the same listings still goes after it
        assertThat(export(team.admin(), request(account, ids, Medium.TXT)).exported()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ the filter, "on sale", Etsy

    @Test
    void theListingsToExportCanBeChosenByAFilterThatDefaultsToReady() throws IOException {
        Team team = team();
        SellerAccount account = readyAccount(team, "Shop Filter", ExportFormat.XLSM, ExportFixtures.flatFileXlsm("F-"));
        assertThat(statusService.markDraft(team.admin(), listing(team, account, "F-V1").id())).isTrue();

        List<UUID> ready = exports.idsOf(team.admin(), account.id(), new ListingFilter(null, null, ListingStatus.READY_TO_EXPORT, null, false, ""));
        assertThat(ready).hasSize(4).doesNotContain(listing(team, account, "F-V1").id());
        List<UUID> mug = exports.idsOf(team.admin(), account.id(), new ListingFilter(null, null, null, null, false, "S2"));
        assertThat(mug).containsExactly(listing(team, account, "F-S2").id());
        assertThatThrownBy(() -> exports.idsOf(team.admin(), UUID.randomUUID(), ListingFilter.none())).isInstanceOf(WorkspaceException.class);
    }

    @Test
    void anExportedListingIsMarkedOnSaleOnlyByAPersonWhoMayChangeItAndAnUnexportedOneIsNot() throws IOException {
        Team team = team();
        SellerAccount account = readyAccount(team, "Shop Live", ExportFormat.XLSM, ExportFixtures.flatFileXlsm("O-"));
        Listing s2 = listing(team, account, "O-S2");
        Listing v1 = listing(team, account, "O-V1");
        export(team.admin(), request(account, List.of(s2.id()), null));

        assertThat(exports.markLive(team.member(), s2.id())).isTrue();
        assertThat(listing(team, account, "O-S2").status()).isEqualTo(ListingStatus.LIVE);
        assertThat(listings.history(team.client().getId(), s2.id())).first().satisfies(h -> assertThat(h.kind().name()).isEqualTo("STATUS"));
        // the time of the export stays when it goes live
        assertThat(listings.exportedAt(team.client().getId(), List.of(s2.id()))).containsKey(s2.id());
        // a listing that was not exported is not "on sale", and the other member may not touch this one
        assertThat(exports.markLive(team.member(), v1.id())).isFalse();
        assertThat(listing(team, account, "O-V1").status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
        assertThatThrownBy(() -> exports.markLive(team.other(), s2.id())).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("forbidden"));
        // from "on sale" a person can send it back to a draft to change it and export it again
        assertThat(statusService.markDraft(team.member(), s2.id())).isTrue();
        assertThat(listing(team, account, "O-S2").status()).isEqualTo(ListingStatus.DRAFT);
    }

    @Autowired
    ListingEditor editor;

    private Map<String, String> etsyForm(String sku, String title) {
        Map<String, String> posted = new LinkedHashMap<>();
        posted.put("sku", sku);
        posted.put("title", title);
        posted.put("description", "A plain description.");
        posted.put("tags", "mug, ceramic, gift");
        posted.put("price", "19.99");
        posted.put("quantity", "5");
        posted.put("category", "Mugs");
        posted.put("who_made", "i_did");
        posted.put("when_made", "made_to_order");
        return posted;
    }

    @Test
    void anEtsyListingPostedByHandGoesLiveFromReadyOnlyAndIsLoggedWithCounts() throws IOException {
        Team team = team();
        SellerAccount shop = accounts.create(team.client().getId(), Marketplace.ETSY, "Shop Etsy Live", null, ExportFormat.XLSM);
        Listing mug = editor.create(team.member(), shop.id(), etsyForm("E-1", "Plain ceramic mug"));
        Listing draft = editor.create(team.member(), shop.id(), etsyForm("E-2", "Another plain mug"));
        statusService.markReady(team.member(), List.of(mug.id()), false, null);

        assertThat(exports.markPosted(team.member(), UUID.randomUUID()).result()).isEqualTo(WorkspaceExports.PostResult.UNKNOWN);
        assertThat(exports.markPosted(team.member(), draft.id()).result()).isEqualTo(WorkspaceExports.PostResult.NOT_READY);
        assertThat(exports.markPosted(team.other(), mug.id()).result()).isEqualTo(WorkspaceExports.PostResult.FORBIDDEN);
        assertThat(listings.find(team.client().getId(), mug.id()).orElseThrow().status()).isEqualTo(ListingStatus.READY_TO_EXPORT);

        assertThat(exports.markPosted(team.member(), mug.id()).result()).isEqualTo(WorkspaceExports.PostResult.POSTED);

        // the final state: live, with the time, an EXPORT entry in the history and one log row with counts and nothing else
        Listing live = listings.find(team.client().getId(), mug.id()).orElseThrow();
        assertThat(live.status()).isEqualTo(ListingStatus.LIVE);
        assertThat(live.version()).isEqualTo(mug.version() + 2);
        assertThat(listings.exportedAt(team.client().getId(), List.of(mug.id()))).containsKey(mug.id());
        assertThat(listings.history(team.client().getId(), mug.id())).first().satisfies(h -> {
            assertThat(h.kind().name()).isEqualTo("EXPORT");
            assertThat(h.status()).isEqualTo(ListingStatus.LIVE);
        });
        var log = exportLog.recent(team.client().getId(), shop.id(), 5);
        assertThat(log).hasSize(1);
        assertThat(log.get(0).format().name()).isEqualTo("ETSY_POSTED");
        assertThat(log.get(0).templateKind()).isEqualTo(TemplateKind.ETSY_FIELDS);
        assertThat(log.get(0).listings()).isEqualTo(1);
        assertThat(log.get(0).untrademarked()).isEqualTo(1);
        // posting twice is not posting again
        assertThat(exports.markPosted(team.member(), mug.id()).result()).isEqualTo(WorkspaceExports.PostResult.NOT_READY);
        assertThat(logRows(team)).isEqualTo(1);
        // an Amazon listing goes out as a file, not by hand
        SellerAccount amazon = readyAccount(team, "Shop Amazon Hand", ExportFormat.XLSM, ExportFixtures.flatFileXlsm("K-"));
        assertThat(exports.markPosted(team.admin(), listing(team, amazon, "K-S2").id()).result()).isEqualTo(WorkspaceExports.PostResult.NOT_ETSY);
    }

    // ------------------------------------------------------------------ purge and export of the log

    @Test
    void theOperatorsExportWritesTheLogAndThePurgeDeletesItBeforeTheTemplateItPointsTo() throws IOException {
        Team team = team();
        SellerAccount account = readyAccount(team, "Shop Purge", ExportFormat.TXT, ExportFixtures.flatFileXlsm("Z-"));
        export(team.admin(), request(account, allIds(team, account), null));
        assertThat(logRows(team)).isEqualTo(1);
        Client client = clients.findById(team.client().getId()).orElseThrow();
        WorkspaceDataCommands commands = new WorkspaceDataCommands(jdbc, clients, mapper, transaction, new java.io.PrintStream(new java.io.ByteArrayOutputStream()));

        assertThat(commands.counts(client.getId())).containsEntry("workspace_export_log", 1L);
        Path dir = Files.createTempDirectory("lg-export-log");
        try {
            commands.export(client, dir.resolve("out").toString(), false);
            String log = Files.readString(dir.resolve("out").resolve("export-log.tsv"), StandardCharsets.UTF_8);
            assertThat(log.split("\n")).hasSize(2);
            assertThat(log).contains("Shop Purge").contains("TXT").contains("UTF-8").contains("admin-");
            assertThat(Files.readString(dir.resolve("out").resolve("manifest.json"))).contains("\"export_log_rows\" : 1");
            // the listing's own export carries the time and the reason of its trademark part (none here: the audit is off)
            String tsv = Files.readString(dir.resolve("out").resolve("Shop_Purge__AMAZON_BULK__v1.tsv"), StandardCharsets.UTF_8);
            assertThat(tsv).contains("\"\"exported_at\"\":");
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        String code = WorkspaceDataCommands.confirmationCode(client.getId(), commands.counts(client.getId()));
        commands.purge(client, code);
        assertThat(logRows(team)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_template WHERE client_id = ?", Long.class, client.getId())).isZero();
    }

    @Test
    void aWorkspaceThatNeverExportedExportsAsItDid() throws IOException {
        Team team = team();
        SellerAccount account = readyAccount(team, "Shop Never", ExportFormat.TXT, ExportFixtures.flatFileXlsm("Y-"));
        assertThat(account).isNotNull();
        Client client = clients.findById(team.client().getId()).orElseThrow();
        WorkspaceDataCommands commands = new WorkspaceDataCommands(jdbc, clients, mapper, transaction, new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        Path dir = Files.createTempDirectory("lg-never");
        try {
            commands.export(client, dir.resolve("out").toString(), false);
            assertThat(dir.resolve("out").resolve("export-log.tsv")).doesNotExist();
            assertThat(Files.readString(dir.resolve("out").resolve("manifest.json"))).doesNotContain("export_log");
            assertThat(Files.readString(dir.resolve("out").resolve("Shop_Never__AMAZON_BULK__v1.tsv"))).doesNotContain("exported_at");
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
