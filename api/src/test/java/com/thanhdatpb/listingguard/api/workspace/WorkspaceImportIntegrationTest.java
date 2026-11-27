package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Plan;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Result;
import com.thanhdatpb.listingguard.api.workspace.TemplateService.SourceFile;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.HistoryEntry;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.ListingFilter;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetFormat;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import com.thanhdatpb.listingguard.workspace.sheets.SheetReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The workspace against a real database (ADR-059): the first import and the template it makes, the same file again, a changed cell, a
 * changed header, SKU problems, .xlsm and TSV, Amazon Custom rows, history, soft delete, optimistic locking, the permission rules for a
 * MEMBER, tenant isolation, and the admin export and purge. Every name is invented and no file was read from a real Amazon template, so
 * what a real template looks like is not verified here.
 */
@SpringBootTest(properties = {
        "listing-guard.workspace.enabled=true",
        "listing-guard.workspace.history-keep=3",
        "spring.servlet.multipart.max-file-size=25MB",
        "spring.servlet.multipart.max-request-size=60MB",
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class WorkspaceImportIntegrationTest {

    private record Team(Client client, Actor admin, Actor member, Actor otherMember) {
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
    ImportStages stages;
    @Autowired
    ListingImportService imports;
    @Autowired
    TemplateService templateService;
    @Autowired
    WorkspaceProperties properties;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ClientRepository clients;
    @Autowired
    TransactionTemplate transaction;
    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    @MockitoBean
    JavaMailSender mailSender;

    private Team team() {
        Client client = provisioning.createClient("ws" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        String id = UUID.randomUUID().toString();
        ConsoleUser admin = new ConsoleUser("admin-" + id + "@example.test", client.getId());
        ConsoleUser member = new ConsoleUser("member-" + id + "@example.test", client.getId());
        ConsoleUser other = new ConsoleUser("other-" + id + "@example.test", client.getId());
        console.saveAndFlush(new ConsoleAccount(admin.email(), client.getId(), ConsoleRole.ADMIN));
        console.saveAndFlush(new ConsoleAccount(member.email(), client.getId(), ConsoleRole.MEMBER));
        console.saveAndFlush(new ConsoleAccount(other.email(), client.getId(), ConsoleRole.MEMBER));
        return new Team(client, access.actor(admin).orElseThrow(), access.actor(member).orElseThrow(), access.actor(other).orElseThrow());
    }

    private SellerAccount account(Team team, Marketplace marketplace, String name) {
        return accounts.create(team.client().getId(), marketplace, name, null, ExportFormat.XLSM);
    }

    /** The steps of the Console, as a service call: read the sheet, take the guessed mapping as confirmed, preview, write. */
    private Result run(Actor actor, SellerAccount account, TemplateKind kind, byte[] bytes, String sheet, int fieldRow, UUID owner) {
        SheetGrid grid = SheetReader.read(bytes, sheet, properties.limits(false));
        Params params = params(actor, account, kind, grid, sheet, fieldRow, owner);
        Plan plan = imports.plan(actor, params, grid);
        Result result = imports.commit(actor, params, grid,
                new SourceFile(SheetReader.detect(bytes, properties.limits(false)), "template-file", bytes), null);
        assertThat(result.counts()).as("the commit does what the preview said").isEqualTo(plan.counts());
        return result;
    }

    private Params params(Actor actor, SellerAccount account, TemplateKind kind, SheetGrid grid, String sheet, int fieldRow, UUID owner) {
        var view = imports.mappingView(actor, account.id(), kind, grid, fieldRow);
        Map<Integer, String> targets = new LinkedHashMap<>();
        view.columns().forEach(c -> {
            if (!c.target().isEmpty()) {
                targets.put(c.index(), c.target());
            }
        });
        return new Params(account.id(), kind, owner, ListingStatus.DRAFT, sheet, fieldRow, fieldRow + 1, targets, Map.of());
    }

    private long count(String table, UUID client) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE client_id = ?", Long.class, client);
    }

    private Listing listing(Team team, SellerAccount account, String sku) {
        UUID id = jdbc.queryForObject("SELECT id FROM workspace_listing WHERE client_id = ? AND account_id = ? AND sku = ? AND deleted_at IS NULL",
                UUID.class, team.client().getId(), account.id(), sku);
        return listings.find(team.client().getId(), id).orElseThrow();
    }

    // ------------------------------------------------------------------ first import and the template it makes

    @Test
    void theFirstImportMakesTheTemplateFromTheFileAndKeepsEverythingAboveTheData() throws IOException {
        Team team = team();
        SellerAccount account = account(team, Marketplace.AMAZON, "Shop A");
        byte[] file = WorkspaceFixtures.flatFile("A-");

        Result result = run(team.admin(), account, TemplateKind.AMAZON_BULK, file, "Template", 3, team.member().accountId());

        assertThat(result.counts().created()).isEqualTo(4);
        assertThat(result.templateCreated()).isTrue();
        Template template = templates.current(team.client().getId(), account.id(), TemplateKind.AMAZON_BULK).orElseThrow();
        assertThat(template.version()).isEqualTo(1);
        assertThat(template.sourceFormat()).isEqualTo("XLSX");
        assertThat(template.sheetName()).isEqualTo("Template");
        assertThat(template.fieldRow()).isEqualTo(3);
        assertThat(template.firstDataRow()).isEqualTo(4);
        // the rows above the data are kept verbatim, and so is the order of the columns
        assertThat(template.headerRows()).isEqualTo(WorkspaceFixtures.headerRows());
        assertThat(template.columns()).extracting(c -> c.header()).containsExactlyElementsOf(WorkspaceFixtures.FIELDS);
        assertThat(template.columns()).extracting(c -> c.index()).containsExactlyElementsOf(java.util.stream.IntStream.range(0, 16).boxed().toList());
        // the original file, byte for byte
        assertThat(templates.fileBytes(team.client().getId(), template.id()).orElseThrow()).isEqualTo(file);
        assertThat(template.fileSha256()).isEqualTo(WorkspaceJson.sha256(file));
        // the mapping a person confirmed is remembered with the template
        assertThat(template.mapping().targets()).containsEntry("item_sku", "sku").containsEntry("item_name", "title")
                .containsEntry("bullet_point1", "bullet_points").doesNotContainKey("x_custom_flag");

        Listing child = listing(team, account, "A-P1-S");
        assertThat(child.role()).isEqualTo(ListingRole.CHILD);
        assertThat(child.parentSku()).isEqualTo("A-P1");
        assertThat(child.title()).isEqualTo("Synthetic shirt S");
        assertThat(child.templateId()).isEqualTo(template.id());
        assertThat(child.ownerAccountId()).isEqualTo(team.member().accountId());
        assertThat(child.status()).isEqualTo(ListingStatus.DRAFT);
        assertThat(child.version()).isZero();
        // every column is kept by key, a column no standard field was given too
        assertThat(child.data()).containsEntry("x_custom_flag", "yes").containsEntry("standard_price", "19.99").containsEntry("bullet_point1", "Soft");
        assertThat(child.fields().path("bullet_points")).hasSize(2);
        assertThat(child.fields().path("attributes").path("size_name").asText()).isEqualTo("S");
        assertThat(listing(team, account, "A-P1").role()).isEqualTo(ListingRole.PARENT);
        assertThat(listing(team, account, "A-S2").role()).isEqualTo(ListingRole.STANDALONE);
    }

    @Test
    void runningTheSameFileAgainChangesNothing() throws IOException {
        Team team = team();
        SellerAccount account = account(team, Marketplace.AMAZON, "Shop B");
        byte[] file = WorkspaceFixtures.flatFile("B-");
        run(team.admin(), account, TemplateKind.AMAZON_BULK, file, "Template", 3, null);

        Result again = run(team.admin(), account, TemplateKind.AMAZON_BULK, file, "Template", 3, null);

        assertThat(again.counts().created()).isZero();
        assertThat(again.counts().updated()).isZero();
        assertThat(again.counts().unchanged()).isEqualTo(4);
        assertThat(again.templateCreated()).isFalse();
        assertThat(count("workspace_listing", team.client().getId())).isEqualTo(4);
        assertThat(count("workspace_template", team.client().getId())).isEqualTo(1);
        assertThat(listing(team, account, "B-S2").version()).isZero();
        assertThat(count("workspace_listing_history", team.client().getId())).isEqualTo(4);
    }

    @Test
    void aChangedCellUpdatesOnlyThatListingAndKeepsItsHistory() throws IOException {
        Team team = team();
        SellerAccount account = account(team, Marketplace.AMAZON, "Shop C");
        run(team.admin(), account, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFile("C-"), "Template", 3, null);
        List<List<String>> edited = new java.util.ArrayList<>(WorkspaceFixtures.dataRows("C-"));
        edited.set(3, List.of("C-S2", "MUG", "Synthetic mug, edited", "Nobrand", "", "", "", "Ceramic", "", "cup", "A synthetic mug", "", "", "9.5", "3", ""));

        Result result = run(team.admin(), account, TemplateKind.AMAZON_BULK,
                WorkspaceFixtures.flatFile("C-", WorkspaceFixtures.FIELDS, edited), "Template", 3, null);

        assertThat(result.counts().updated()).isEqualTo(1);
        assertThat(result.counts().unchanged()).isEqualTo(3);
        Listing mug = listing(team, account, "C-S2");
        assertThat(mug.title()).isEqualTo("Synthetic mug, edited");
        assertThat(mug.version()).isEqualTo(1);
        List<HistoryEntry> history = listings.history(team.client().getId(), mug.id());
        assertThat(history).extracting(HistoryEntry::version).containsExactly(1L, 0L);
        assertThat(history.get(0).changedBy()).isEqualTo(team.admin().accountId());
    }

    @Test
    void historyKeepsOnlyTheLatestVersions() throws IOException {
        Team team = team();
        SellerAccount account = account(team, Marketplace.AMAZON, "Shop D");
        for (int i = 0; i < 6; i++) {
            List<List<String>> rows = List.of(List.of("D-1", "SHIRT", "Title " + i, "", "", "", "", "", "", "", "", "", "", "", "", ""));
            run(team.admin(), account, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFile("D-", WorkspaceFixtures.FIELDS, rows), "Template", 3, null);
        }

        Listing listing = listing(team, account, "D-1");

        assertThat(listing.version()).isEqualTo(5);
        assertThat(listings.history(team.client().getId(), listing.id())).extracting(HistoryEntry::version).containsExactly(5L, 4L, 3L);
    }

    // ------------------------------------------------------------------ template versions

    @Test
    void aDifferentHeaderMakesANewVersionAndTheOldOneStaysForTheListingsThatUseIt() throws IOException {
        Team team = team();
        SellerAccount account = account(team, Marketplace.AMAZON, "Shop E");
        run(team.admin(), account, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFile("E-"), "Template", 3, null);
        Template v1 = templates.current(team.client().getId(), account.id(), TemplateKind.AMAZON_BULK).orElseThrow();
        List<String> wider = new java.util.ArrayList<>(WorkspaceFixtures.FIELDS);
        wider.add("country_of_origin");
        List<List<String>> rows = List.of(java.util.stream.Stream.concat(WorkspaceFixtures.dataRows("E-").get(3).stream(), java.util.stream.Stream.of("VN")).toList());
        byte[] file = WorkspaceFixtures.flatFile("E-", wider, rows);

        // a MEMBER cannot make it
        SheetGrid grid = SheetReader.read(file, "Template", properties.limits(false));
        Params params = params(team.admin(), account, TemplateKind.AMAZON_BULK, grid, "Template", 3, null);
        Plan asMember = imports.plan(team.member(), params, grid);
        assertThat(asMember.blocking()).isEqualTo("template.admin_required");
        assertThatThrownBy(() -> imports.commit(team.member(), params, grid, new SourceFile(SheetFormat.XLSX, "f", file), null))
                .isInstanceOfSatisfying(WorkspaceException.class, e -> assertThat(e.code()).isEqualTo("template.admin_required"));
        assertThat(count("workspace_template", team.client().getId())).isEqualTo(1);

        Result result = imports.commit(team.admin(), params, grid, new SourceFile(SheetFormat.XLSX, "f", file), null);

        assertThat(result.templateCreated()).isTrue();
        assertThat(result.templateVersion()).isEqualTo(2);
        Template v2 = templates.current(team.client().getId(), account.id(), TemplateKind.AMAZON_BULK).orElseThrow();
        assertThat(v2.version()).isEqualTo(2);
        assertThat(templates.find(team.client().getId(), v1.id())).isPresent();
        assertThat(templates.list(team.client().getId(), account.id())).extracting(Template::version).containsExactly(2, 1);
        // only the listing that was in the file moves to the new version; the others keep the one they were made with
        assertThat(listing(team, account, "E-S2").templateId()).isEqualTo(v2.id());
        assertThat(listing(team, account, "E-S2").data()).containsEntry("country_of_origin", "VN");
        assertThat(listing(team, account, "E-P1").templateId()).isEqualTo(v1.id());
    }

    @Test
    void anAdminUploadsATemplateWithoutAnyRowAndTheSameHeaderCreatesNothing() throws IOException {
        Team team = team();
        SellerAccount account = account(team, Marketplace.AMAZON, "Shop F");
        byte[] file = WorkspaceFixtures.asMacroEnabled(WorkspaceFixtures.flatFile("F-", WorkspaceFixtures.FIELDS, List.of()));
        SheetGrid grid = SheetReader.read(file, "Template", properties.limits(true));
        Params params = params(team.admin(), account, TemplateKind.AMAZON_BULK, grid, "Template", 3, null);

        assertThatThrownBy(() -> templateService.createFromFile(team.member(), account.id(), TemplateKind.AMAZON_BULK, grid, 3, 4,
                new SourceFile(SheetFormat.XLSM, "t.xlsm", file), params.targets(), Map.of())).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("forbidden"));
        var created = templateService.createFromFile(team.admin(), account.id(), TemplateKind.AMAZON_BULK, grid, 3, 4,
                new SourceFile(SheetFormat.XLSM, "t.xlsm", file), params.targets(), Map.of());
        var again = templateService.createFromFile(team.admin(), account.id(), TemplateKind.AMAZON_BULK, grid, 3, 4,
                new SourceFile(SheetFormat.XLSM, "t.xlsm", file), params.targets(), Map.of());

        assertThat(created.created()).isTrue();
        assertThat(created.template().sourceFormat()).isEqualTo("XLSM");
        // the macro project travels with the file byte for byte, and is never run
        assertThat(templates.fileBytes(team.client().getId(), created.template().id()).orElseThrow()).isEqualTo(file);
        assertThat(again.created()).isFalse();
        assertThat(again.template().id()).isEqualTo(created.template().id());
        assertThat(count("workspace_template", team.client().getId())).isEqualTo(1);
    }

    @Test
    void xlsmAndTsvGiveTheSameListingsAsXlsx() throws IOException {
        Team team = team();
        SellerAccount xlsm = account(team, Marketplace.AMAZON, "Shop G");
        SellerAccount tsv = account(team, Marketplace.AMAZON, "Shop H");
        byte[] macro = WorkspaceFixtures.asMacroEnabled(WorkspaceFixtures.flatFile("G-"));

        Result fromMacro = run(team.admin(), xlsm, TemplateKind.AMAZON_BULK, macro, "Template", 3, null);
        Result fromText = run(team.admin(), tsv, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFileTsv("H-"), "data", 3, null);

        assertThat(fromMacro.counts().created()).isEqualTo(4);
        assertThat(fromText.counts().created()).isEqualTo(4);
        Template macroTemplate = templates.current(team.client().getId(), xlsm.id(), TemplateKind.AMAZON_BULK).orElseThrow();
        Template textTemplate = templates.current(team.client().getId(), tsv.id(), TemplateKind.AMAZON_BULK).orElseThrow();
        assertThat(macroTemplate.sourceFormat()).isEqualTo("XLSM");
        assertThat(templates.fileBytes(team.client().getId(), macroTemplate.id()).orElseThrow()).isEqualTo(macro);
        assertThat(textTemplate.sourceFormat()).isEqualTo("TSV");
        assertThat(templates.fileBytes(team.client().getId(), textTemplate.id())).isEmpty();
        assertThat(textTemplate.headerRows()).isEqualTo(WorkspaceFixtures.headerRows());
        assertThat(macroTemplate.headerHash()).isEqualTo(textTemplate.headerHash());
        assertThat(listing(team, tsv, "H-P1-S").data()).isEqualTo(listing(team, xlsm, "G-P1-S").data().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().replace("G-", "H-"))));
    }

    // ------------------------------------------------------------------ problems of a row

    @Test
    void rowsWithAProblemAreListedAndNeverWritten() throws IOException {
        Team team = team();
        SellerAccount account = account(team, Marketplace.AMAZON, "Shop I");
        List<List<String>> rows = List.of(
                List.of("", "MUG", "No SKU", "", "", "", "", "", "", "", "", "", "", "", "", ""),
                List.of("I-D", "MUG", "Dup one", "", "", "", "", "", "", "", "", "", "", "", "", ""),
                List.of("I-D", "MUG", "Dup two", "", "", "", "", "", "", "", "", "", "", "", "", ""),
                List.of("I-C", "MUG", "Orphan", "", "child", "I-NOPE", "", "", "", "", "", "", "", "", "", ""),
                List.of("I-OK", "MUG", "Fine", "", "", "", "", "", "", "", "", "", "", "", "", ""));

        Result result = run(team.admin(), account, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFile("I-", WorkspaceFixtures.FIELDS, rows),
                "Template", 3, null);

        assertThat(result.counts().rows()).isEqualTo(5);
        assertThat(result.counts().created()).isEqualTo(1);
        assertThat(result.counts().errors()).isEqualTo(4);
        assertThat(count("workspace_listing", team.client().getId())).isEqualTo(1);
        assertThat(listing(team, account, "I-OK").title()).isEqualTo("Fine");
    }

    @Test
    void aDeletedListingFreesItsSkuAndKeepsItsHistory() throws IOException {
        Team team = team();
        SellerAccount account = account(team, Marketplace.AMAZON, "Shop J");
        byte[] file = WorkspaceFixtures.flatFile("J-");
        run(team.admin(), account, TemplateKind.AMAZON_BULK, file, "Template", 3, null);
        Listing mug = listing(team, account, "J-S2");

        assertThat(listings.softDelete(team.client().getId(), mug.id(), mug.version(), team.admin().accountId())).isTrue();
        assertThat(listings.find(team.client().getId(), mug.id())).isEmpty();
        assertThat(listings.list(team.client().getId(), ListingFilter.none(), 0).total()).isEqualTo(3);
        Result again = run(team.admin(), account, TemplateKind.AMAZON_BULK, file, "Template", 3, null);

        assertThat(again.counts().created()).isEqualTo(1);
        assertThat(count("workspace_listing", team.client().getId())).isEqualTo(5);
        assertThat(listings.history(team.client().getId(), mug.id())).isNotEmpty();
    }

    @Test
    void aSecondWriterWithAStaleVersionChangesNothing() throws IOException {
        Team team = team();
        SellerAccount account = account(team, Marketplace.AMAZON, "Shop K");
        run(team.admin(), account, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFile("K-"), "Template", 3, null);
        Listing mug = listing(team, account, "K-S2");

        assertThat(listings.changeOwner(team.client().getId(), mug.id(), team.member().accountId(), mug.version(), team.admin().accountId())).isTrue();
        assertThat(listings.changeOwner(team.client().getId(), mug.id(), team.otherMember().accountId(), mug.version(), team.admin().accountId())).isFalse();
        assertThat(listing(team, account, "K-S2").ownerAccountId()).isEqualTo(team.member().accountId());
        assertThat(listings.softDelete(team.client().getId(), mug.id(), mug.version(), team.admin().accountId())).isFalse();
    }

    // ------------------------------------------------------------------ who may change what

    @Test
    void aMemberImportsIntoTheirOwnListingsAndCannotTouchSomeoneElses() throws IOException {
        Team team = team();
        SellerAccount account = account(team, Marketplace.AMAZON, "Shop L");
        byte[] file = WorkspaceFixtures.flatFile("L-");
        run(team.admin(), account, TemplateKind.AMAZON_BULK, file, "Template", 3, team.otherMember().accountId());
        Listing before = listing(team, account, "L-S2");
        List<List<String>> edited = List.of(List.of("L-S2", "MUG", "Hijacked title", "", "", "", "", "", "", "", "", "", "", "", "", ""));
        byte[] hijack = WorkspaceFixtures.flatFile("L-", WorkspaceFixtures.FIELDS, edited);

        Result result = run(team.member(), account, TemplateKind.AMAZON_BULK, hijack, "Template", 3, null);

        assertThat(result.counts().errors()).isEqualTo(1);
        assertThat(result.counts().unchanged()).isZero();
        Listing after = listing(team, account, "L-S2");
        assertThat(after.title()).isEqualTo(before.title());
        assertThat(after.version()).isEqualTo(before.version());
        // the member's own new listing is theirs
        List<List<String>> fresh = List.of(List.of("L-NEW", "MUG", "Mine", "", "", "", "", "", "", "", "", "", "", "", "", ""));
        Result created = run(team.member(), account, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFile("L-", WorkspaceFixtures.FIELDS, fresh), "Template", 3, null);
        assertThat(created.counts().created()).isEqualTo(1);
        assertThat(listing(team, account, "L-NEW").ownerAccountId()).isEqualTo(team.member().accountId());
        // and every member reads the whole client's listings
        assertThat(listings.list(team.client().getId(), ListingFilter.none(), 0).total()).isEqualTo(5);
    }

    // ------------------------------------------------------------------ Amazon Custom

    @Test
    void customizeRowsJoinTheirListingBySkuAndOnesWithoutAListingAreKept() throws IOException {
        Team team = team();
        SellerAccount account = account(team, Marketplace.AMAZON, "Shop M");
        run(team.admin(), account, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFile("M-"), "Template", 3, null);
        byte[] custom = WorkspaceFixtures.customizeFile(List.of(List.of("M-P1-S", "Front", "Name"), List.of("M-GHOST", "Front", "Number")));

        Result first = run(team.admin(), account, TemplateKind.AMAZON_CUSTOM, custom, "Custom", 2, null);

        assertThat(first.counts().created()).isEqualTo(2);
        assertThat(first.customizationsUnlinked()).isEqualTo(1);
        var linked = listings.customization(team.client().getId(), account.id(), "M-P1-S").orElseThrow();
        assertThat(linked.listingId()).isEqualTo(listing(team, account, "M-P1-S").id());
        assertThat(linked.data()).containsEntry("surface_name", "Front");
        var ghost = listings.customization(team.client().getId(), account.id(), "M-GHOST").orElseThrow();
        assertThat(ghost.listingId()).isNull();
        assertThat(listings.unlinkedCustomizations(team.client().getId(), account.id())).isEqualTo(1);
        // a Custom template is its own kind and its own version
        assertThat(templates.current(team.client().getId(), account.id(), TemplateKind.AMAZON_CUSTOM)).isPresent();
        assertThat(templates.current(team.client().getId(), account.id(), TemplateKind.AMAZON_BULK)).isPresent();
        // the same file again changes nothing, and when the listing arrives the row is linked without another import
        Result again = run(team.admin(), account, TemplateKind.AMAZON_CUSTOM, custom, "Custom", 2, null);
        assertThat(again.counts().unchanged()).isEqualTo(2);
        run(team.admin(), account, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFile("M-", WorkspaceFixtures.FIELDS,
                List.of(List.of("M-GHOST", "MUG", "Arrived", "", "", "", "", "", "", "", "", "", "", "", "", ""))), "Template", 3, null);
        assertThat(listings.customization(team.client().getId(), account.id(), "M-GHOST").orElseThrow().listingId()).isNotNull();
        assertThat(listings.unlinkedCustomizations(team.client().getId(), account.id())).isZero();
    }

    // ------------------------------------------------------------------ Etsy

    @Test
    void anEtsyAccountUsesTheFixedFieldsWithoutAnyTemplateFile() {
        Team team = team();
        SellerAccount shop = account(team, Marketplace.ETSY, "Shop Etsy");
        Template builtin = templateService.ensureEtsy(team.client().getId(), shop.id());
        assertThat(templateService.ensureEtsy(team.client().getId(), shop.id()).id()).isEqualTo(builtin.id());
        assertThat(builtin.sourceFormat()).isEqualTo("BUILTIN");
        assertThat(builtin.unverified()).isTrue();
        assertThat(builtin.columns()).extracting(c -> c.key()).contains("title", "description", "tags", "materials", "personalization", "price", "quantity", "sku");
        byte[] csv = ("SKU,TITLE,TAG1,TAG2,PRICE,Notes\nET-1,A mug,mug,gift,19.99,internal\nET-2,A cup,cup,,5,\n").getBytes(StandardCharsets.UTF_8);

        Result result = run(team.admin(), shop, TemplateKind.ETSY_FIELDS, csv, "data", 1, team.member().accountId());

        assertThat(result.counts().created()).isEqualTo(2);
        Listing mug = listing(team, shop, "ET-1");
        assertThat(mug.templateId()).isEqualTo(builtin.id());
        assertThat(mug.fields().path("tags")).hasSize(2);
        assertThat(mug.data()).containsEntry("extra:Notes", "internal");
        assertThat(templates.current(team.client().getId(), shop.id(), TemplateKind.ETSY_FIELDS).orElseThrow().mapping().source())
                .containsEntry("TAG1", "tags").containsEntry("SKU", "sku");
        assertThat(count("workspace_template", team.client().getId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ tenant isolation

    @Test
    void anotherClientCannotReadOrChangeAnything() throws IOException {
        Team one = team();
        Team two = team();
        SellerAccount account = account(one, Marketplace.AMAZON, "Shop N");
        run(one.admin(), account, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFile("N-"), "Template", 3, null);
        Listing mug = listing(one, account, "N-S2");
        Template template = templates.current(one.client().getId(), account.id(), TemplateKind.AMAZON_BULK).orElseThrow();
        UUID two2 = two.client().getId();
        UUID stage = stages.put(one.client().getId(), one.admin().accountId(), "f.xlsx", new byte[] {1});

        assertThat(listings.find(two2, mug.id())).isEmpty();
        assertThat(listings.list(two2, ListingFilter.none(), 0).total()).isZero();
        assertThat(listings.existing(two2, account.id(), List.of("N-S2"))).isEmpty();
        assertThat(listings.history(two2, mug.id())).isEmpty();
        assertThat(listings.children(two2, account.id(), "N-P1")).isEmpty();
        assertThat(listings.changeOwner(two2, mug.id(), two.admin().accountId(), mug.version(), two.admin().accountId())).isFalse();
        assertThat(listings.softDelete(two2, mug.id(), mug.version(), two.admin().accountId())).isFalse();
        assertThat(accounts.find(two2, account.id())).isEmpty();
        assertThat(accounts.update(two2, account.id(), "Taken over", null, ExportFormat.TXT, true)).isFalse();
        assertThat(accounts.list(two2)).isEmpty();
        assertThat(templates.find(two2, template.id())).isEmpty();
        assertThat(templates.fileBytes(two2, template.id())).isEmpty();
        assertThat(templates.current(two2, account.id(), TemplateKind.AMAZON_BULK)).isEmpty();
        assertThat(templates.updateMapping(two2, template.id(), template.mapping())).isFalse();
        assertThat(stages.get(two2, two.admin().accountId(), stage)).isEmpty();
        assertThat(stages.get(one.client().getId(), one.member().accountId(), stage)).as("another person of the same client").isEmpty();
        assertThat(stages.get(one.client().getId(), one.admin().accountId(), stage)).isPresent();
        // an import cannot be pointed at another client's account
        SheetGrid grid = SheetReader.read(WorkspaceFixtures.flatFile("N-"), "Template", properties.limits(false));
        Params params = params(one.admin(), account, TemplateKind.AMAZON_BULK, grid, "Template", 3, null);
        assertThatThrownBy(() -> imports.plan(two.admin(), params, grid)).isInstanceOfSatisfying(WorkspaceException.class,
                e -> assertThat(e.code()).isEqualTo("account.unknown"));
        assertThatThrownBy(() -> templateService.createFromFile(two.admin(), account.id(), TemplateKind.AMAZON_BULK, grid, 3, 4,
                new SourceFile(SheetFormat.XLSX, "f", new byte[0]), params.targets(), Map.of())).isInstanceOf(WorkspaceException.class);
        assertThat(listing(one, account, "N-S2").title()).isEqualTo("Synthetic mug");
        // the schema itself refuses a row that points at another client's parent
        assertThatThrownBy(() -> jdbc.update("INSERT INTO workspace_template (id, client_id, account_id, kind, version, source_format, field_row, "
                + "first_data_row, header_rows, columns, header_hash) VALUES (?, ?, ?, 'AMAZON_BULK', 9, 'TSV', 1, 2, '[]'::jsonb, '[]'::jsonb, ?)",
                UUID.randomUUID(), two2, account.id(), "x".repeat(64))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ export and purge

    @Test
    void exportWritesATsvPerTemplateVersionWithTheFullJsonAndPurgeNeedsTwoSteps(@org.junit.jupiter.api.io.TempDir Path temp) throws IOException {
        Team one = team();
        Team two = team();
        SellerAccount account = account(one, Marketplace.AMAZON, "Shop O");
        SellerAccount other = account(two, Marketplace.AMAZON, "Shop O2");
        byte[] file = WorkspaceFixtures.asMacroEnabled(WorkspaceFixtures.flatFile("O-"));
        run(one.admin(), account, TemplateKind.AMAZON_BULK, file, "Template", 3, one.member().accountId());
        run(two.admin(), other, TemplateKind.AMAZON_BULK, WorkspaceFixtures.flatFile("P-"), "Template", 3, null);
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        WorkspaceDataCommands commands = new WorkspaceDataCommands(jdbc, clients, mapper, transaction, new java.io.PrintStream(captured));

        Path directory = commands.export(one.client(), temp.resolve("export").toString(), false);

        String tsv = Files.readString(directory.resolve("Shop_O__AMAZON_BULK__v1.tsv"), StandardCharsets.UTF_8);
        String[] lines = tsv.split("\n");
        assertThat(lines).hasSize(3 + 4);
        assertThat(lines[0]).isEqualTo(String.join("\t", WorkspaceFixtures.SETTINGS));
        assertThat(lines[2]).startsWith(String.join("\t", WorkspaceFixtures.FIELDS)).endsWith("_json");
        // the JSON of each row is the last cell; its quotes are doubled by the TSV quoting
        assertThat(tsv).contains("\"\"sku\"\":\"\"O-P1-S\"\"").contains("\"\"owner\"\":\"\"" + one.member().email() + "\"\"").doesNotContain("P-P1\t");
        assertThat(Files.readAllBytes(directory.resolve("templates").resolve("Shop_O__AMAZON_BULK__v1.xlsm"))).isEqualTo(file);
        assertThat(Files.readString(directory.resolve("manifest.json"))).contains("\"listing_rows\" : 4");
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("Exported 4 listings");
        assertThatThrownBy(() -> commands.export(one.client(), directory.toString(), false)).isInstanceOf(IllegalArgumentException.class);

        // purge: the first run only counts, a wrong code deletes nothing, the right code deletes this client's rows and nothing else
        commands.purge(one.client(), null);
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("workspace_listing: 4").contains("--confirm=");
        assertThat(count("workspace_listing", one.client().getId())).isEqualTo(4);
        assertThatThrownBy(() -> commands.purge(one.client(), "deadbeef")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("workspace_listing", one.client().getId())).isEqualTo(4);
        String code = WorkspaceDataCommands.confirmationCode(one.client().getId(), commands.counts(one.client().getId()));
        commands.purge(one.client(), code);
        for (String table : WorkspaceDataCommands.TABLES) {
            assertThat(count(table, one.client().getId())).as(table).isZero();
        }
        assertThat(count("workspace_listing", two.client().getId())).isEqualTo(4);
        assertThat(count("workspace_template", two.client().getId())).isEqualTo(1);
        assertThat(clients.findById(one.client().getId())).isPresent();
        assertThat(console.findByClientIdOrderByEmail(one.client().getId())).hasSize(3);
    }
}
