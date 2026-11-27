package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Level;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Reason;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.RunStatus;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Source;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.State;
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

/** V58 and the Java of the audit say the same thing, and V58 stays additive and inside the workspace's tables (ADR-061). Runs from the module directory. */
class AuditSchemaTest {

    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V58__workspace_background_audit.sql");

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    private static List<String> constraint(String sql, String name) {
        Matcher match = Pattern.compile("CONSTRAINT " + name + " CHECK \\((?:\\w+) IN \\(([^)]*)\\)").matcher(sql);
        assertThat(match.find()).as(name).isTrue();
        List<String> values = new ArrayList<>();
        Matcher value = Pattern.compile("'([A-Za-z0-9_]+)'").matcher(match.group(1));
        while (value.find()) {
            values.add(value.group(1));
        }
        return values;
    }

    @Test
    void everyCheckConstraintEqualsItsJavaEnum() throws IOException {
        String sql = sql();

        assertThat(constraint(sql, "ck_workspace_audit_run_reason")).containsExactlyElementsOf(Arrays.stream(Reason.values()).map(Enum::name).toList());
        assertThat(constraint(sql, "ck_workspace_audit_run_status")).containsExactlyElementsOf(Arrays.stream(RunStatus.values()).map(Enum::name).toList());
        assertThat(constraint(sql, "ck_workspace_listing_audit_state")).containsExactlyElementsOf(Arrays.stream(State.values()).map(Enum::name).toList());
        assertThat(constraint(sql, "ck_workspace_listing_finding_source")).containsExactlyElementsOf(Arrays.stream(Source.values()).map(Enum::name).toList());
        assertThat(constraint(sql, "ck_workspace_listing_finding_level")).containsExactlyInAnyOrderElementsOf(Arrays.stream(Level.values()).map(Level::code).toList());
    }

    @Test
    void theMigrationIsAdditiveAndEveryTableBelongsToTheWorkspaceAndCarriesTheClient() throws IOException {
        String sql = sql();

        assertThat(sql).doesNotContainIgnoringCase("ALTER TABLE").doesNotContainIgnoringCase("DROP ");
        Matcher tables = Pattern.compile("CREATE TABLE (\\w+)").matcher(sql);
        List<String> created = new ArrayList<>();
        while (tables.find()) {
            created.add(tables.group(1));
        }
        assertThat(created).isNotEmpty().allSatisfy(table -> assertThat(table).startsWith("workspace_"));
        created.forEach(table -> assertThat(sql.substring(sql.indexOf("CREATE TABLE " + table))).contains("client_id"));
        assertThat(sql).contains("REFERENCES workspace_account (id, client_id)").contains("REFERENCES workspace_listing (id, client_id)");
    }

    @Test
    void thePurgeAndTheExportKnowEveryTableOfTheMigration() throws IOException {
        Matcher tables = Pattern.compile("CREATE TABLE (\\w+)").matcher(sql());
        List<String> created = new ArrayList<>();
        while (tables.find()) {
            created.add(tables.group(1));
        }

        assertThat(com.thanhdatpb.listingguard.api.workspace.WorkspaceDataCommandsAccess.tables()).containsAll(created);
    }
}
