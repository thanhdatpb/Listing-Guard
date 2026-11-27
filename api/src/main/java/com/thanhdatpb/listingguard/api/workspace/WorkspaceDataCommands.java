package com.thanhdatpb.listingguard.api.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's commands over a client's workspace data (ADR-059): {@code workspace export --client=<id|name> [--out=<dir>]} and
 * {@code workspace purge --client=<id|name> [--confirm=<code>]}. They run in the one-shot {@code admin} job and need no HTTP endpoint. They
 * are not tied to {@code listing-guard.workspace.enabled}: data that was stored while the workspace was on must stay exportable and
 * removable after it is switched off. Every statement filters by {@code client_id}; the log names the client and counts, never a cell.
 *
 * <p>Export writes, in a new folder: one TSV per template version (the verbatim rows above the data, the field-name row with one extra
 * column {@code _json}, then a row per listing with the full JSON of the listing), the Amazon Custom rows the same way, the original
 * Excel file of every template that has one, and {@code manifest.json}. Values are written as stored: nothing is trimmed or escaped for a
 * spreadsheet, so open the files as data, not by double-click.
 *
 * <p>Purge is two steps. Without {@code --confirm} it only counts and prints a code that depends on those counts; with that code it
 * deletes, in one transaction, every {@code workspace_*} row of the client (listings, history, Amazon Custom rows, templates with their
 * files, staged uploads, seller accounts). Nothing outside those tables is touched. If anything changed meanwhile the code no longer fits
 * and the command refuses, so the operator sees the new counts first.
 */
@Component
@Profile("admin")
public class WorkspaceDataCommands {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceDataCommands.class);

    public static final String USAGE = """
              workspace export --client=<id|name> [--out=<dir>] [--include-deleted]   (ADR-059: TSV per template version with the full JSON, template files, manifest)
              workspace purge --client=<id|name> [--confirm=<code>]   (ADR-059: deletes every workspace row of the client; run once to see the counts and the code)
            """;

    /** The workspace tables in delete order (children first). */
    static final List<String> TABLES = List.of("workspace_alert_log", "workspace_alert_pref", "workspace_listing_finding",
            "workspace_listing_audit", "workspace_audit_run", "workspace_listing_history", "workspace_customization", "workspace_listing",
            "workspace_template", "workspace_import_stage", "workspace_account");

    private final JdbcTemplate jdbc;
    private final ClientRepository clients;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final PrintStream out;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkspaceDataCommands(JdbcTemplate jdbc, ClientRepository clients, ObjectMapper mapper, TransactionTemplate transaction) {
        this(jdbc, clients, mapper, transaction, System.out);
    }

    WorkspaceDataCommands(JdbcTemplate jdbc, ClientRepository clients, ObjectMapper mapper, TransactionTemplate transaction, PrintStream out) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.mapper = mapper;
        this.transaction = transaction;
        this.out = out;
    }

    public void execute(ApplicationArguments arguments) {
        List<String> args = arguments.getNonOptionArgs();
        if (args.size() < 2) {
            throw new IllegalArgumentException("Usage:\n" + USAGE);
        }
        Client client = client(option(arguments, "client"));
        switch (args.get(1)) {
            case "export" -> export(client, option(arguments, "out"), arguments.containsOption("include-deleted"));
            case "purge" -> purge(client, option(arguments, "confirm"));
            default -> throw new IllegalArgumentException("Unknown workspace command '" + args.get(1) + "'.\n" + USAGE);
        }
    }

    private static String option(ApplicationArguments arguments, String name) {
        List<String> values = arguments.getOptionValues(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private Client client(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("--client=<id|name> is required.");
        }
        try {
            return clients.findById(UUID.fromString(id.strip()))
                    .orElseThrow(() -> new IllegalArgumentException("No client has this id."));
        } catch (IllegalArgumentException notAnId) {
            if (id.strip().matches("[0-9a-fA-F-]{36}")) {
                throw notAnId;
            }
            return clients.findByName(id.strip()).orElseThrow(() -> new IllegalArgumentException("No client is named '" + id.strip() + "'."));
        }
    }

    // ------------------------------------------------------------------ purge

    Map<String, Long> counts(UUID clientId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLES) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE client_id = ?", Long.class, clientId));
        }
        return counts;
    }

    /** The code the operator must pass back: it depends on the client and on every count, so it stops fitting when the data changes. */
    static String confirmationCode(UUID clientId, Map<String, Long> counts) {
        return WorkspaceJson.sha256("workspace-purge:" + clientId + ":" + counts).substring(0, 8);
    }

    public void purge(Client client, String confirm) {
        Map<String, Long> counts = counts(client.getId());
        String code = confirmationCode(client.getId(), counts);
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (confirm == null) {
            out.println("Workspace data of client " + client.getId() + " (" + client.getName() + "):");
            counts.forEach((table, count) -> out.println("  " + table + ": " + count));
            out.println(total == 0 ? "Nothing to delete." : "Nothing was deleted. To delete all of it permanently, run again with --confirm=" + code);
            out.println("Export first if you may need it: workspace export --client=" + client.getId());
            return;
        }
        if (!confirm.equals(code)) {
            throw new IllegalArgumentException("The confirmation code does not fit the current data (it changes with every count). "
                    + "Run the command without --confirm to see the counts and the new code.");
        }
        transaction.executeWithoutResult(status -> {
            for (String table : TABLES) {
                jdbc.update("DELETE FROM " + table + " WHERE client_id = ?", client.getId());
            }
        });
        log.info("Workspace data of client {} purged: {}", client.getId(), counts);
        out.println("Deleted " + total + " rows of client " + client.getId() + ".");
    }

    // ------------------------------------------------------------------ export

    public Path export(Client client, String outDir, boolean includeDeleted) {
        Path directory;
        try {
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
            directory = Path.of(outDir == null || outDir.isBlank() ? "workspace-export-" + client.getName() + "-" + stamp : outDir);
            if (Files.exists(directory) && Files.list(directory).findAny().isPresent()) {
                throw new IllegalArgumentException("The output folder exists and is not empty: " + directory);
            }
            Files.createDirectories(directory.resolve("templates"));
            return write(client, directory, includeDeleted);
        } catch (IOException e) {
            throw new IllegalStateException("The export cannot be written: " + e.getMessage(), e);
        }
    }

    private Path write(Client client, Path directory, boolean includeDeleted) throws IOException {
        UUID clientId = client.getId();
        Map<UUID, String> emails = new LinkedHashMap<>();
        jdbc.query("SELECT id, email FROM account WHERE client_id = ?", rs -> {
            emails.put(rs.getObject("id", UUID.class), rs.getString("email"));
        }, clientId);
        record Tpl(UUID id, String account, String kind, int version, String sourceFormat, String sha, List<List<String>> headerRows,
                   List<String> headers, List<String> keys, int firstDataRow, boolean hasFile) {
        }
        List<Tpl> templates = jdbc.query("SELECT t.id, a.name AS account, t.kind, t.version, t.source_format, t.file_sha256, "
                + "t.header_rows::text AS header_rows, t.columns::text AS columns, t.first_data_row, (t.file_bytes IS NOT NULL) AS has_file "
                + "FROM workspace_template t JOIN workspace_account a ON a.id = t.account_id AND a.client_id = t.client_id "
                + "WHERE t.client_id = ? ORDER BY a.name, t.kind, t.version", (rs, n) -> {
            var columns = parse(rs.getString("columns"));
            List<String> headers = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            columns.forEach(c -> {
                headers.add(c.path("header").asText());
                keys.add(c.path("key").asText());
            });
            List<List<String>> headerRows = new ArrayList<>();
            parse(rs.getString("header_rows")).forEach(row -> {
                List<String> cells = new ArrayList<>();
                row.forEach(cell -> cells.add(cell.asText()));
                headerRows.add(cells);
            });
            return new Tpl(rs.getObject("id", UUID.class), rs.getString("account"), rs.getString("kind"), rs.getInt("version"),
                    rs.getString("source_format"), rs.getString("file_sha256"), headerRows, headers, keys, rs.getInt("first_data_row"),
                    rs.getBoolean("has_file"));
        }, clientId);

        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("client_id", clientId.toString());
        manifest.put("exported_at", Instant.now().toString());
        manifest.put("include_deleted", includeDeleted);
        var files = manifest.putArray("templates");
        long listingRows = 0;
        long customizationRows = 0;
        for (Tpl t : templates) {
            String base = safe(t.account()) + "__" + t.kind() + "__v" + t.version();
            ObjectNode entry = files.addObject();
            entry.put("account", t.account()).put("kind", t.kind()).put("version", t.version()).put("tsv", base + ".tsv");
            if (t.hasFile()) {
                String extension = "XLSM".equals(t.sourceFormat()) ? "xlsm" : "xlsx";
                byte[] bytes = jdbc.queryForObject("SELECT file_bytes FROM workspace_template WHERE id = ? AND client_id = ?", byte[].class,
                        t.id(), clientId);
                Files.write(directory.resolve("templates").resolve(base + "." + extension), bytes);
                entry.put("template_file", "templates/" + base + "." + extension).put("sha256", t.sha());
            }
            StringBuilder tsv = new StringBuilder();
            for (int r = 0; r < t.headerRows().size(); r++) {
                List<String> row = new ArrayList<>(t.headerRows().get(r));
                if (r == t.headerRows().size() - 1 && t.headerRows().size() >= 1 && isFieldRow(t.headerRows(), t.headers())) {
                    while (row.size() < t.headers().size()) {
                        row.add("");
                    }
                    row.add("_json");
                }
                appendRow(tsv, row);
            }
            if (t.headerRows().isEmpty() || !isFieldRow(t.headerRows(), t.headers())) {
                List<String> row = new ArrayList<>(t.headers());
                row.add("_json");
                appendRow(tsv, row);
            }
            if ("AMAZON_CUSTOM".equals(t.kind())) {
                customizationRows += rowsOfCustomization(clientId, t.id(), t.keys(), tsv);
            } else {
                listingRows += rowsOfListings(clientId, t.id(), t.keys(), emails, includeDeleted, tsv);
            }
            Files.writeString(directory.resolve(base + ".tsv"), tsv, StandardCharsets.UTF_8);
        }
        manifest.put("listing_rows", listingRows);
        manifest.put("customization_rows", customizationRows);
        Files.writeString(directory.resolve("manifest.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                StandardCharsets.UTF_8);
        log.info("Workspace export of client {}: {} listing rows, {} customization rows, {} template versions", clientId, listingRows,
                customizationRows, templates.size());
        out.println("Exported " + listingRows + " listings and " + customizationRows + " customization rows in " + templates.size()
                + " template versions to " + directory);
        return directory;
    }

    private static boolean isFieldRow(List<List<String>> headerRows, List<String> headers) {
        return !headerRows.isEmpty() && headerRows.get(headerRows.size() - 1).equals(trim(headers));
    }

    private static List<String> trim(List<String> cells) {
        int end = cells.size();
        while (end > 0 && cells.get(end - 1).isEmpty()) {
            end--;
        }
        return cells.subList(0, end);
    }

    private long rowsOfListings(UUID clientId, UUID templateId, List<String> keys, Map<UUID, String> emails, boolean includeDeleted,
                                StringBuilder tsv) {
        long[] count = {0};
        jdbc.query("SELECT l.id, l.sku, l.parent_sku, l.role, l.data::text AS data, l.fields::text AS fields, l.owner_account_id, l.status, "
                + "l.source, l.cloned_from, l.version, l.created_at, l.updated_at, l.deleted_at, a.state AS audit_state, "
                + "a.audited_at AS audit_at, a.catalog_version AS audit_catalog, a.watchlist_version AS audit_watchlist, "
                + "(SELECT COALESCE(json_agg(json_build_object('rule', f.rule_id, 'level', f.level, 'source', f.source, 'field', f.field, "
                + "'start', f.span_start, 'end', f.span_end, 'mark', f.mark, 'serial', f.serial, 'new', f.is_new) ORDER BY f.position)::text, '[]') "
                + "FROM workspace_listing_finding f WHERE f.listing_id = l.id AND f.client_id = l.client_id) AS audit_findings "
                + "FROM workspace_listing l LEFT JOIN workspace_listing_audit a ON a.listing_id = l.id AND a.client_id = l.client_id "
                + "WHERE l.client_id = ? AND l.template_id = ? "
                + (includeDeleted ? "" : "AND l.deleted_at IS NULL ") + "ORDER BY l.sku, l.id", rs -> {
            var data = parse(rs.getString("data"));
            List<String> row = new ArrayList<>();
            for (String key : keys) {
                row.add(data.path(key).asText(""));
            }
            ObjectNode json = mapper.createObjectNode();
            json.put("id", rs.getString("id")).put("sku", rs.getString("sku")).put("parent_sku", rs.getString("parent_sku"))
                    .put("role", rs.getString("role")).put("status", rs.getString("status")).put("source", rs.getString("source"))
                    .put("cloned_from", rs.getString("cloned_from")).put("version", rs.getLong("version"));
            UUID owner = rs.getObject("owner_account_id", UUID.class);
            json.put("owner", owner == null ? null : emails.getOrDefault(owner, owner.toString()));
            json.put("created_at", String.valueOf(rs.getObject("created_at", java.time.OffsetDateTime.class)))
                    .put("updated_at", String.valueOf(rs.getObject("updated_at", java.time.OffsetDateTime.class)))
                    .put("deleted_at", rs.getObject("deleted_at") == null ? null : String.valueOf(rs.getObject("deleted_at", java.time.OffsetDateTime.class)));
            json.set("data", data);
            json.set("fields", parse(rs.getString("fields")));
            if (rs.getString("audit_state") != null) {
                // the latest background audit (ADR-061); a listing that was never audited has no member, so an export of a workspace
                // that never turned the audit on is what it was
                ObjectNode audit = json.putObject("audit");
                audit.put("state", rs.getString("audit_state")).put("audited_at", String.valueOf(rs.getObject("audit_at", java.time.OffsetDateTime.class)))
                        .put("catalog_version", rs.getString("audit_catalog")).put("watchlist_version", rs.getString("audit_watchlist"));
                audit.set("findings", parse(rs.getString("audit_findings")));
            }
            row.add(json.toString());
            appendRow(tsv, row);
            count[0]++;
        }, clientId, templateId);
        return count[0];
    }

    private long rowsOfCustomization(UUID clientId, UUID templateId, List<String> keys, StringBuilder tsv) {
        long[] count = {0};
        jdbc.query("SELECT sku, data::text AS data, version FROM workspace_customization WHERE client_id = ? AND template_id = ? ORDER BY sku",
                rs -> {
            var data = parse(rs.getString("data"));
            List<String> row = new ArrayList<>();
            for (String key : keys) {
                row.add(data.path(key).asText(""));
            }
            ObjectNode json = mapper.createObjectNode();
            json.put("sku", rs.getString("sku")).put("version", rs.getLong("version"));
            json.set("data", data);
            row.add(json.toString());
            appendRow(tsv, row);
            count[0]++;
        }, clientId, templateId);
        return count[0];
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("A stored JSON document cannot be read.", e);
        }
    }

    /** One tab-separated row; a cell with a tab, a line break or a quote is quoted with doubled quotes, so the file reads back as it was. */
    static void appendRow(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append('\t');
            }
            String cell = cells.get(i) == null ? "" : cells.get(i);
            if (cell.indexOf('\t') >= 0 || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0 || cell.indexOf('"') >= 0) {
                out.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                out.append(cell);
            }
        }
        out.append('\n');
    }

    private static String safe(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9._-]+", "_");
        return clean.isEmpty() ? "account" : clean;
    }
}
