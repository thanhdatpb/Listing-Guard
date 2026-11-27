package com.thanhdatpb.listingguard.api.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Mapping;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.TemplateColumn;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The templates of the workspace's seller accounts, one row per version (ADR-059, V57). A version is the exact field-name row of a file:
 * its {@code header_hash}. The original file of an Excel template is kept byte for byte and read apart from the row, so a list of templates
 * never loads a megabyte. Every statement carries {@code client_id}.
 */
@Repository
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class WorkspaceTemplates {

    private static final String COLUMNS = "id, client_id, account_id, kind, version, source_format, source_file_name, file_sha256, "
            + "sheet_name, field_row, first_data_row, header_rows::text AS header_rows, columns::text AS columns, mapping::text AS mapping, "
            + "header_hash, unverified, created_at";

    private final JdbcTemplate jdbc;
    private final WorkspaceJson json;
    private final Clock clock;
    private final RowMapper<Template> mapper;

    public WorkspaceTemplates(JdbcTemplate jdbc, com.fasterxml.jackson.databind.ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.json = new WorkspaceJson(objectMapper);
        this.clock = clock;
        this.mapper = (rs, n) -> new Template(rs.getObject("id", UUID.class), rs.getObject("client_id", UUID.class),
                rs.getObject("account_id", UUID.class), TemplateKind.valueOf(rs.getString("kind")), rs.getInt("version"),
                rs.getString("source_format"), rs.getString("source_file_name"), rs.getString("file_sha256"), rs.getString("sheet_name"),
                rs.getInt("field_row"), rs.getInt("first_data_row"),
                json.read(rs.getString("header_rows"), new TypeReference<List<List<String>>>() { }),
                json.read(rs.getString("columns"), new TypeReference<List<TemplateColumn>>() { }),
                mapping(rs.getString("mapping")), rs.getString("header_hash"), rs.getBoolean("unverified"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private Mapping mapping(String text) {
        var tree = json.tree(text);
        return new Mapping(json.stringMap(tree.path("targets").isMissingNode() ? "{}" : tree.path("targets").toString()),
                json.stringMap(tree.path("separators").isMissingNode() ? "{}" : tree.path("separators").toString()),
                json.stringMap(tree.path("source").isMissingNode() ? "{}" : tree.path("source").toString()));
    }

    /** The newest version of an account's template of a kind. */
    public Optional<Template> current(UUID clientId, UUID accountId, TemplateKind kind) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workspace_template WHERE client_id = ? AND account_id = ? AND kind = ? "
                + "ORDER BY version DESC LIMIT 1", mapper, clientId, accountId, kind.name()).stream().findFirst();
    }

    public Optional<Template> find(UUID clientId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workspace_template WHERE id = ? AND client_id = ?", mapper, id, clientId)
                .stream().findFirst();
    }

    /** Every version of an account's templates, newest first within a kind. */
    public List<Template> list(UUID clientId, UUID accountId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workspace_template WHERE client_id = ? AND account_id = ? ORDER BY kind, version DESC",
                mapper, clientId, accountId);
    }

    /** The original file of an Excel template, or empty when the template has none. */
    public Optional<byte[]> fileBytes(UUID clientId, UUID id) {
        return jdbc.query("SELECT file_bytes FROM workspace_template WHERE id = ? AND client_id = ? AND file_bytes IS NOT NULL",
                (rs, n) -> rs.getBytes(1), id, clientId).stream().findFirst();
    }

    /**
     * Writes a new version of a template. The caller holds the account's lock (ADR-059), so the next version number cannot be taken twice.
     *
     * @param file the original bytes of an Excel file, or null
     */
    public Template insert(Template template, byte[] file) {
        int version = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) + 1 FROM workspace_template WHERE account_id = ? AND client_id = ? "
                + "AND kind = ?", Integer.class, template.accountId(), template.clientId(), template.kind().name());
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workspace_template (id, client_id, account_id, kind, version, source_format, source_file_name, file_bytes, "
                + "file_sha256, sheet_name, field_row, first_data_row, header_rows, columns, mapping, header_hash, unverified, created_by, "
                + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, NULL, ?)", id,
                template.clientId(), template.accountId(), template.kind().name(), version, template.sourceFormat(),
                template.sourceFileName(), file, template.fileSha256(), template.sheetName(), template.fieldRow(), template.firstDataRow(),
                json.write(template.headerRows()), json.write(template.columns()), json.write(template.mapping()), template.headerHash(),
                template.unverified(), Timestamp.from(clock.instant()));
        return find(template.clientId(), id).orElseThrow();
    }

    public boolean updateMapping(UUID clientId, UUID id, Mapping mapping) {
        return jdbc.update("UPDATE workspace_template SET mapping = ?::jsonb WHERE id = ? AND client_id = ?", json.write(mapping), id,
                clientId) == 1;
    }

    /**
     * The keys of the columns of a field-name row: the cell as written (trimmed for the key), {@code header~2} for a repeat, {@code col_C}
     * for a column without a name. {@code width} columns are always returned, so a data column beyond the field row keeps its place.
     */
    public static List<TemplateColumn> columnsOf(List<String> fieldRow, int width) {
        List<TemplateColumn> columns = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < width; i++) {
            String header = i < fieldRow.size() && fieldRow.get(i) != null ? fieldRow.get(i) : "";
            String base = header.strip().isEmpty() ? "col_" + letters(i) : header.strip();
            String key = base;
            for (int n = 2; !used.add(key); n++) {
                key = base + "~" + n;
            }
            columns.add(new TemplateColumn(i, key, header));
        }
        return columns;
    }

    public static String headerHash(List<TemplateColumn> columns) {
        return WorkspaceJson.rowsHash(columns.stream().map(TemplateColumn::header).toList());
    }

    /** The spreadsheet letters of a zero-based column: 0 is A, 26 is AA. */
    public static String letters(int index) {
        StringBuilder text = new StringBuilder();
        for (int n = index; n >= 0; n = n / 26 - 1) {
            text.insert(0, (char) ('A' + n % 26));
        }
        return text.toString();
    }
}
