package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.console.bulk.DelimitedText;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogImporter;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Counts;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.TemplateService.SourceFile;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTriggers;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditWorker;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import com.thanhdatpb.listingguard.workspace.sheets.SheetReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The synthetic workspace of the screenshots (docs/workspace.md, "Chụp lại ảnh") and of the W6 demo: three seller accounts, four people, 23 POD
 * listings (mugs, tote bags, posters, pillow covers) and a few clones, made through the real import, editor, cloner and status services, then
 * audited by the real background audit. <b>Nothing is faked</b>: a listing is Clean, Warning, Error, New risk or Not fully checked because its text
 * and the context of the audit make it so, and the test that runs this ({@code WorkspaceDemoDatasetIntegrationTest}) reads the final state from the
 * database.
 *
 * <p>Where the data lives: the people and the listings are the CSV files {@code demo/workspace/people.csv} and {@code listings.csv} in the main
 * resources, so that a demo seed command (W6) reads the same text as this test; the workbooks are built here with POI (the template of account A
 * is a macro-enabled {@code .xlsm} with a settings row, a label row and a data validation, the template of account B has other columns). No file
 * was read from a real Amazon template: <b>not verified against a real file</b>. Every name is invented, every e-mail ends in {@code @example.test},
 * and the watchlist and catalog words are the fictional ones of the DEMO catalog.
 */
final class WorkspaceDemoDataset {

    static final String FIRST_WATCH = "Flarbithene";
    static final String RISK_WATCH = "Vellumzoq";
    private static final String CATALOG_ENTRY = "demo-fictional-2026-11";

    /** The header (row 3) of the template of account A: a macro-enabled file with a column, {@code occasion}, that account B has no partner for. */
    static final List<String> A_FIELDS = List.of("item_sku", "feed_product_type", "item_name", "brand_name", "parentage", "parent_sku",
            "variation_theme", "bullet_point1", "bullet_point2", "bullet_point3", "generic_keywords", "product_description", "size_name",
            "color_name", "standard_price", "quantity", "occasion", "assignee");
    /** The template of account B: other order, other names, no {@code occasion} and no {@code variation_theme}, and a column {@code fabric_note} of its own. */
    static final List<String> B_FIELDS = List.of("item_sku", "item_name", "product_type", "brand_name", "bullet_point1", "bullet_point2",
            "bullet_point3", "generic_keywords", "parentage", "parent_sku", "size_name", "color_name", "product_description", "standard_price",
            "quantity", "fabric_note", "assignee");
    static final List<String> ETSY_COLUMNS = etsyColumns();
    private static final List<String> SETTINGS_A = List.of("TemplateType=SYNTHETIC-POD-A", "Version=0.0", "Settings=none");
    private static final List<String> SETTINGS_B = List.of("TemplateType=SYNTHETIC-POD-B", "Version=0.0", "Settings=none");
    private static final List<String> PRODUCT_TYPES = List.of("MUG", "TOTE", "POSTER", "PILLOW");

    private WorkspaceDemoDataset() {
    }

    // ------------------------------------------------------------------ the data files

    record Person(String key, String email, ConsoleRole role) {
    }

    static List<Person> people() {
        List<Map<String, String>> rows = table("people.csv");
        return rows.stream().map(r -> new Person(r.get("key"), r.get("email"), ConsoleRole.valueOf(r.get("role")))).toList();
    }

    /** Every listing of the file, as header name to value. */
    static List<Map<String, String>> listings() {
        return table("listings.csv");
    }

    static List<Map<String, String>> listings(String account, String wave) {
        return listings().stream().filter(r -> r.get("account").equals(account) && r.get("wave").equals(wave)).toList();
    }

    private static List<Map<String, String>> table(String file) {
        try {
            String text = new ClassPathResource("demo/workspace/" + file).getContentAsString(StandardCharsets.UTF_8);
            List<DelimitedText.Row> rows = DelimitedText.parse(text, ',').rows().stream().filter(r -> !r.blank()).toList();
            List<String> header = rows.get(0).cells();
            List<Map<String, String>> out = new ArrayList<>();
            for (DelimitedText.Row row : rows.subList(1, rows.size())) {
                Map<String, String> map = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    map.put(header.get(i), i < row.cells().size() ? row.cells().get(i) : "");
                }
                out.add(map);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the workspace demo file " + file, e);
        }
    }

    // ------------------------------------------------------------------ the files to import

    private static List<String> etsyColumns() {
        List<String> columns = new ArrayList<>(List.of("SKU", "Title", "Description"));
        for (int i = 1; i <= 14; i++) {
            columns.add("Tag" + i);
        }
        columns.addAll(List.of("Price", "Quantity", "Category", "Who made", "When made"));
        return columns;
    }

    /** The {@code .xlsm} of account A: {@code rows} are listings of account "A", {@code emails} maps a person key to an e-mail. */
    static byte[] fileA(List<Map<String, String>> rows, Map<String, String> emails) throws IOException {
        List<List<String>> data = rows.stream().map(r -> cells(A_FIELDS, r, emails, "Sample Studio")).toList();
        return WorkspaceFixtures.asMacroEnabled(workbook(SETTINGS_A, A_FIELDS, data, true));
    }

    /** The {@code .xlsx} of account B, with other columns. */
    static byte[] fileB(List<Map<String, String>> rows, Map<String, String> emails) throws IOException {
        List<List<String>> data = rows.stream().map(r -> cells(B_FIELDS, r, emails, "Sample Home")).toList();
        return workbook(SETTINGS_B, B_FIELDS, data, false);
    }

    /** The CSV of the Etsy shop: one row per listing, one column per tag. */
    static byte[] etsyCsv(List<Map<String, String>> rows) {
        StringBuilder text = new StringBuilder(String.join(",", ETSY_COLUMNS)).append('\n');
        for (Map<String, String> r : rows) {
            List<String> tags = r.get("tags").isEmpty() ? List.of() : List.of(r.get("tags").split(";"));
            List<String> cells = new ArrayList<>(List.of(r.get("sku"), r.get("title"), r.get("description")));
            for (int i = 0; i < 14; i++) {
                cells.add(i < tags.size() ? tags.get(i) : "");
            }
            cells.addAll(List.of(r.get("price"), r.get("quantity"), r.get("product"), "i_did", "made_to_order"));
            text.append(cells.stream().map(WorkspaceDemoDataset::csv).collect(Collectors.joining(","))).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csv(String cell) {
        return cell.contains(",") || cell.contains("\"") || cell.contains("\n") ? "\"" + cell.replace("\"", "\"\"") + "\"" : cell;
    }

    private static List<String> cells(List<String> fields, Map<String, String> r, Map<String, String> emails, String brand) {
        List<String> cells = new ArrayList<>();
        for (String field : fields) {
            cells.add(switch (field) {
                case "item_sku" -> r.get("sku");
                case "feed_product_type", "product_type" -> r.get("product");
                case "item_name" -> r.get("title");
                case "brand_name" -> brand;
                case "parentage" -> r.get("parentage");
                case "parent_sku" -> r.get("parent_sku");
                case "variation_theme" -> r.get("parentage").isEmpty() ? "" : r.get("product").equals("TOTE") ? "ColorName" : "SizeName";
                case "bullet_point1" -> r.get("bullet1");
                case "bullet_point2" -> r.get("bullet2");
                case "bullet_point3" -> r.get("bullet3");
                case "generic_keywords" -> r.get("search_terms");
                case "product_description" -> r.get("description");
                case "size_name" -> r.get("size");
                case "color_name" -> r.get("color");
                case "standard_price" -> r.get("price");
                case "quantity" -> r.get("quantity");
                case "occasion" -> r.get("occasion");
                case "fabric_note" -> r.get("product").equals("PILLOW") ? "Linen look polyester blend" : "";
                case "assignee" -> emails.getOrDefault(r.get("owner"), "");
                default -> "";
            });
        }
        return cells;
    }

    private static byte[] workbook(List<String> settings, List<String> fields, List<List<String>> data, boolean validation) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            write(book.createSheet("Instructions"), 0, List.of("Read me first: this file is synthetic and was not read from a real marketplace template."));
            Sheet template = book.createSheet("Template");
            write(template, 0, settings);
            write(template, 1, fields.stream().map(WorkspaceDemoDataset::label).toList());
            write(template, 2, fields);
            int row = 3;
            for (List<String> cells : data) {
                write(template, row++, cells);
            }
            Sheet values = book.createSheet("Valid Values");
            for (int i = 0; i < PRODUCT_TYPES.size(); i++) {
                write(values, i, List.of(PRODUCT_TYPES.get(i)));
            }
            if (validation) {
                DataValidationHelper helper = template.getDataValidationHelper();
                DataValidationConstraint list = helper.createFormulaListConstraint("'Valid Values'!$A$1:$A$" + PRODUCT_TYPES.size());
                DataValidation rule = helper.createValidation(list, new CellRangeAddressList(3, 999, fields.indexOf("feed_product_type"),
                        fields.indexOf("feed_product_type")));
                rule.setShowErrorBox(true);
                template.addValidationData(rule);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            return out.toByteArray();
        }
    }

    private static String label(String field) {
        String[] words = field.split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            label.append(label.isEmpty() ? "" : " ").append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return label.toString();
    }

    private static void write(Sheet sheet, int index, List<String> cells) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < cells.size(); i++) {
            if (!cells.get(i).isEmpty()) {
                row.createCell(i).setCellValue(cells.get(i));
            }
        }
    }

    // ------------------------------------------------------------------ what a seeding leaves behind

    /**
     * The ids a caller needs. {@code listing("A", "TOTE-S")} is the id of the listing with that SKU in that account (A and B are the two Amazon
     * accounts, ETSY the shop); {@code files} holds the files of the re-imports that the browser previews.
     */
    record Seeded(UUID client, Map<String, UUID> accounts, Map<String, UUID> listings, Map<String, Actor> actors, Map<String, byte[]> files,
                  Map<String, Counts> expected) {
        UUID listing(String account, String sku) {
            UUID id = listings.get(account + "/" + sku);
            if (id == null) {
                throw new IllegalArgumentException("No listing " + sku + " in account " + account);
            }
            return id;
        }
    }

    /** The services that make the dataset. A test autowires them and passes them here. */
    static final class Seeder {
        private final ClientProvisioning clients;
        private final ConsoleAccountRepository console;
        private final WorkspaceAccess access;
        private final WorkspaceAccounts accounts;
        private final WorkspaceProperties properties;
        private final ListingImportService imports;
        private final ListingEditor editor;
        private final ListingCloner cloner;
        private final ListingStatusService status;
        private final WorkspaceListings listings;
        private final WatchlistService watchlists;
        private final TrademarkCatalogImporter importer;
        private final TrademarkRulesets rulesets;
        private final AuditWorker worker;
        private final AuditTriggers triggers;
        private final JdbcTemplate jdbc;

        Seeder(ClientProvisioning clients, ConsoleAccountRepository console, WorkspaceAccess access, WorkspaceAccounts accounts,
               WorkspaceProperties properties, ListingImportService imports, ListingEditor editor, ListingCloner cloner,
               ListingStatusService status, WorkspaceListings listings, WatchlistService watchlists, TrademarkCatalogImporter importer,
               TrademarkRulesets rulesets, AuditWorker worker, AuditTriggers triggers, JdbcTemplate jdbc) {
            this.clients = clients;
            this.console = console;
            this.access = access;
            this.accounts = accounts;
            this.properties = properties;
            this.imports = imports;
            this.editor = editor;
            this.cloner = cloner;
            this.status = status;
            this.listings = listings;
            this.watchlists = watchlists;
            this.importer = importer;
            this.rulesets = rulesets;
            this.worker = worker;
            this.triggers = triggers;
            this.jdbc = jdbc;
        }

        private UUID client;
        private long catalogVersionId;
        private final Map<String, UUID> accountIds = new LinkedHashMap<>();
        private final Map<String, Actor> actors = new LinkedHashMap<>();

        /**
         * Seeds a new client in the order a real workspace would grow: a first import with the catalog served, a watchlist that gains a word
         * (the two listings that carry it become New risk), clones and edits, the move of the clean drafts to "ready to export", and last an import
         * made while the catalog was not served (those listings stay "not fully checked" until the next re-audit, which nothing starts).
         */
        Seeded seed(String clientName) throws Exception {
            client = clients.createClient(clientName).getId();
            Map<String, String> emails = new LinkedHashMap<>();
            for (Person person : people()) {
                String email = freeEmail(person.email());
                console.saveAndFlush(new ConsoleAccount(email, client, person.role()));
                actors.put(person.key(), access.actor(new ConsoleUser(email, client)).orElseThrow());
                emails.put(person.key(), email);
            }
            Actor admin = actors.get("admin");
            accountIds.put("A", accounts.create(client, Marketplace.AMAZON, "Paper Studio", "Sample Studio", ExportFormat.XLSM).id());
            accountIds.put("B", accounts.create(client, Marketplace.AMAZON, "Pillow Home", "Sample Home", ExportFormat.TXT).id());
            accountIds.put("ETSY", accounts.create(client, Marketplace.ETSY, "Etsy Shop", null, ExportFormat.XLSM).id());

            Path demo = Path.of("..", "catalogs", "demo");
            var ruleset = rulesets.ruleset();
            catalogVersionId = importer.importCatalog(CatalogManifest.load(demo).entry(CATALOG_ENTRY), demo.resolve("raw"),
                    new TextNormalizer(ruleset.normalization()), NormalizationIdentity.of(ruleset)).catalogVersionId();
            serveCatalog(true);
            EntryInput first = watch(FIRST_WATCH);
            watchlists.replace(client, List.of(first), "test", null);

            // 1. the first import of each account, then the audit of what was imported
            byte[] fileA = fileA(listings("A", "main"), emails);
            byte[] fileB = fileB(listings("B", "main"), emails);
            commit(admin, "A", TemplateKind.AMAZON_BULK, fileA, "Template", 3, admin.accountId());
            commit(admin, "B", TemplateKind.AMAZON_BULK, fileB, "Template", 3, admin.accountId());
            importEtsy(admin, listings("ETSY", "main"));
            worker.drain();

            // 2. the watchlist gains a word that two listings already carry: they are New risk
            watchlists.replace(client, List.of(first, watch(RISK_WATCH)), "test", null);
            triggers.checkDrift();
            worker.drain();

            // 3. clones through the real cloner, and edits made in the workspace
            Actor blake = actors.get("blake");
            Actor casey = actors.get("casey");
            cloner.clone(blake, new ListingCloner.Request(accountIds.get("A"), List.of(id("A", "MUG-001")), false, suffix("-B"), Map.of(), false));
            cloner.clone(blake, new ListingCloner.Request(accountIds.get("A"), List.of(id("A", "MUG-002")), false, suffix("-V2"), Map.of(), false));
            cloner.clone(casey, new ListingCloner.Request(accountIds.get("B"), List.of(id("A", "POSTER-P")), true,
                    new SkuPlan(SkuPlan.Mode.PATTERN, "", "", "POSTER", "WALL", null, 0, Map.of()), Map.of(), false));
            // the second clone gets a title of its own and a new second bullet, so its history has two versions
            edit(blake, "A", "MUG-002-V2", Map.of("item_name", "Speckled Glaze Ceramic Mug, Large Coffee Cup, 15 oz",
                    "bullet_point2", "Dishwasher safe and microwave safe"));
            // two listings are changed in the workspace after their import: a re-import of the file meets them
            edit(admin, "A", "TOTE-N", Map.of("bullet_point3", "Printed to order with fade resistant ink and a stitched label"));
            edit(admin, "A", "POSTER-A3", Map.of("product_description", "A botanical leaf illustration printed on heavyweight matte paper in A3 size."));
            worker.drain();

            // 4. the clean drafts are moved to "ready to export" (the gate checks each one)
            status.markReady(admin, List.of(id("A", "MUG-001"), id("A", "MUG-002"), id("A", "POSTER-P"), id("B", "PILLOW-P"),
                    id("ETSY", "ETSY-MUG-1"), id("ETSY", "ETSY-PILLOW-1")), false, null);

            // 5. an import made while no catalog is served: the trademark part cannot run completely, so these are never called clean
            serveCatalog(false);
            commit(admin, "B", TemplateKind.AMAZON_BULK, fileB(listings("B", "late"), emails), "Template", 3, admin.accountId());
            worker.drain();
            serveCatalog(true);

            // the file of a re-import into B: one changed row, one new row, one row without SKU
            List<Map<String, String>> again = new ArrayList<>();
            for (Map<String, String> r : listings("B", "main")) {
                Map<String, String> copy = new LinkedHashMap<>(r);
                if (r.get("sku").equals("PILLOW-T")) {
                    copy.put("description", "A pillow cover with a geometric pattern in teal. The fabric has a soft linen look.");
                }
                again.add(copy);
            }
            again.addAll(listings("B", "late"));
            Map<String, String> added = new LinkedHashMap<>(listings("B", "main").get(1));
            added.put("sku", "PILLOW-LUMBAR");
            added.put("title", "Lumbar Pillow Cover with Geometric Pattern, 12 x 20 Inch");
            added.put("parentage", "");
            added.put("parent_sku", "");
            again.add(added);
            Map<String, String> nameless = new LinkedHashMap<>(added);
            nameless.put("sku", "");
            nameless.put("title", "Pillow cover without a SKU");
            again.add(nameless);
            byte[] fileBAgain = fileB(again, emails);

            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("A", fileA);
            files.put("B-again", fileBAgain);
            Map<String, Counts> expected = new LinkedHashMap<>();
            expected.put("B-again", plan(admin, "B", fileBAgain));
            expected.put("A", plan(admin, "A", fileA));
            Map<String, UUID> found = new LinkedHashMap<>();
            jdbc.query("SELECT account_id, sku, id FROM workspace_listing WHERE client_id = ? AND deleted_at IS NULL", rs -> {
                UUID account = rs.getObject(1, UUID.class);
                String key = accountIds.entrySet().stream().filter(e -> e.getValue().equals(account)).findFirst().orElseThrow().getKey();
                found.put(key + "/" + rs.getString(2), rs.getObject(3, UUID.class));
            }, client);
            return new Seeded(client, Map.copyOf(accountIds), Map.copyOf(found), Map.copyOf(actors), files, expected);
        }

        /**
         * An e-mail address is unique in the whole database: the first seeding uses the address of people.csv, a second one in the same database
         * (two test classes of one run share it) gets a tag in the local part.
         */
        private String freeEmail(String email) {
            if (jdbc.queryForObject("SELECT count(*) FROM account WHERE lower(email) = lower(?)", Integer.class, email) == 0) {
                return email;
            }
            int at = email.indexOf('@');
            return email.substring(0, at) + "." + UUID.randomUUID().toString().substring(0, 6) + email.substring(at);
        }

        /** Serves the DEMO catalog to the client, or stops serving it. */
        void serveCatalog(boolean served) {
            jdbc.update("DELETE FROM demo_workspace WHERE client_id = ?", client);
            if (served) {
                jdbc.update("INSERT INTO demo_workspace (client_id, catalog_version_id, weeks, seed) VALUES (?, ?, ?, ?)", client, catalogVersionId, 1, 42);
            }
        }

        private static EntryInput watch(String phrase) {
            return new EntryInput(phrase, List.of(), List.of(), null, "Fictional workspace demo word", true);
        }

        private static SkuPlan suffix(String suffix) {
            return new SkuPlan(SkuPlan.Mode.PATTERN, "", suffix, "", "", null, 0, Map.of());
        }

        private UUID id(String account, String sku) {
            return jdbc.queryForObject("SELECT id FROM workspace_listing WHERE client_id = ? AND account_id = ? AND sku = ? AND deleted_at IS NULL", UUID.class,
                    client, accountIds.get(account), sku);
        }

        private void edit(Actor actor, String account, String sku, Map<String, String> changes) {
            UUID id = id(account, sku);
            Listing before = listings.find(client, id).orElseThrow();
            Map<String, String> posted = new LinkedHashMap<>(before.data());
            posted.putAll(changes);
            editor.save(actor, id, before.version(), posted);
        }

        private void importEtsy(Actor admin, List<Map<String, String>> rows) {
            // an Etsy sheet has no owner column: one import for each person
            Map<String, List<Map<String, String>>> byOwner = rows.stream().collect(Collectors.groupingBy(r -> r.get("owner"), LinkedHashMap::new,
                    Collectors.toList()));
            byOwner.forEach((owner, mine) -> commit(admin, "ETSY", TemplateKind.ETSY_FIELDS, etsyCsv(mine), "data", 1, actors.get(owner).accountId()));
        }

        private void commit(Actor actor, String account, TemplateKind kind, byte[] bytes, String sheet, int fieldRow, UUID owner) {
            SheetGrid grid = SheetReader.read(bytes, sheet, properties.limits(false));
            Params params = params(actor, account, kind, grid, sheet, fieldRow, owner);
            imports.commit(actor, params, grid, new SourceFile(SheetReader.detect(bytes, properties.limits(false)), "synthetic-" + account.toLowerCase(Locale.ROOT),
                    bytes), null);
        }

        private Counts plan(Actor actor, String account, byte[] bytes) {
            SheetGrid grid = SheetReader.read(bytes, "Template", properties.limits(false));
            return imports.plan(actor, params(actor, account, TemplateKind.AMAZON_BULK, grid, "Template", 3, actor.accountId()), grid).counts();
        }

        private Params params(Actor actor, String account, TemplateKind kind, SheetGrid grid, String sheet, int fieldRow, UUID owner) {
            var view = imports.mappingView(actor, accountIds.get(account), kind, grid, fieldRow);
            Map<Integer, String> targets = new LinkedHashMap<>();
            view.columns().forEach(c -> {
                if (!c.target().isEmpty()) {
                    targets.put(c.index(), c.target());
                }
            });
            return new Params(accountIds.get(account), kind, owner, ListingStatus.DRAFT, sheet, fieldRow, fieldRow + 1, targets, Map.of());
        }
    }
}
