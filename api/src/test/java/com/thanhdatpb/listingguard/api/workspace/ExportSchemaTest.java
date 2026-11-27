package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.OutputFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.TrademarkReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** V60 and the Java of the export say the same thing, and V60 stays additive and inside the workspace's tables (ADR-063). Runs from the module directory. */
class ExportSchemaTest {

    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V60__workspace_export.sql");

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    private static List<String> constraint(String sql, String name) {
        Matcher match = Pattern.compile("CONSTRAINT " + name + "\\s+CHECK \\((?:\\w+) IS NULL OR \\w+ IN \\(([^)]*)\\)|CONSTRAINT " + name
                + "\\s+CHECK \\((?:\\w+) IN \\(([^)]*)\\)").matcher(sql);
        assertThat(match.find()).as(name).isTrue();
        String list = match.group(1) != null ? match.group(1) : match.group(2);
        List<String> values = new ArrayList<>();
        Matcher value = Pattern.compile("'([A-Za-z0-9_]+)'").matcher(list);
        while (value.find()) {
            values.add(value.group(1));
        }
        return values;
    }

    @Test
    void everyCheckConstraintEqualsItsJavaEnum() throws IOException {
        String sql = sql();

        assertThat(constraint(sql, "ck_workspace_export_format")).containsExactlyElementsOf(Arrays.stream(OutputFormat.values()).map(Enum::name).toList());
        assertThat(constraint(sql, "ck_workspace_export_kind")).containsExactlyElementsOf(Arrays.stream(TemplateKind.values()).map(Enum::name).toList());
        assertThat(constraint(sql, "ck_workspace_listing_audit_tm_reason")).containsExactlyElementsOf(
                Arrays.stream(TrademarkReason.values()).map(Enum::name).toList());
    }

    @Test
    void theMigrationOnlyAddsAndTheNewTableBelongsToTheWorkspaceAndCarriesTheClient() throws IOException {
        String sql = sql();

        assertThat(sql).doesNotContainIgnoringCase("DROP ");
        // the only changes of existing tables are two new nullable columns
        Matcher alters = Pattern.compile("ALTER TABLE (\\w+) (ADD COLUMN|ADD CONSTRAINT)").matcher(sql);
        List<String> altered = new ArrayList<>();
        while (alters.find()) {
            altered.add(alters.group(1) + " " + alters.group(2));
        }
        assertThat(altered).containsExactly("workspace_listing_audit ADD COLUMN", "workspace_listing_audit ADD CONSTRAINT", "workspace_listing ADD COLUMN");
        assertThat(sql).contains("ADD COLUMN trademark_reason VARCHAR(16);").contains("ADD COLUMN exported_at TIMESTAMPTZ;");
        Matcher tables = Pattern.compile("CREATE TABLE (\\w+)").matcher(sql);
        List<String> created = new ArrayList<>();
        while (tables.find()) {
            created.add(tables.group(1));
        }
        assertThat(created).containsExactly("workspace_export_log");
        assertThat(sql).contains("REFERENCES workspace_account (id, client_id)").contains("REFERENCES workspace_template (id, client_id)");
        assertThat(WorkspaceDataCommandsAccess.tables()).containsAll(created);
        // the log is deleted before the template and the account it points to
        List<String> tablesInOrder = WorkspaceDataCommandsAccess.tables();
        assertThat(tablesInOrder.indexOf("workspace_export_log")).isLessThan(tablesInOrder.indexOf("workspace_template"))
                .isLessThan(tablesInOrder.indexOf("workspace_account"));
    }
}
