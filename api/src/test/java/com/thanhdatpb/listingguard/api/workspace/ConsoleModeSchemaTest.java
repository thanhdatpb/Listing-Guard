package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** V61 stays additive and inside the workspace's tables (ADR-064 §6). Runs from the module directory. */
class ConsoleModeSchemaTest {

    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V61__workspace_console_mode.sql");

    @Test
    void theMigrationOnlyAddsColumnsToWorkspaceTables() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).doesNotContainIgnoringCase("DROP ").doesNotContainIgnoringCase("CREATE TABLE").doesNotContainIgnoringCase("RENAME");
        Matcher alters = Pattern.compile("ALTER TABLE (\\w+) (ADD COLUMN|ADD CONSTRAINT) (\\w+)").matcher(sql);
        List<String> altered = new ArrayList<>();
        while (alters.find()) {
            assertThat(alters.group(1)).startsWith("workspace_");
            altered.add(alters.group(1) + "." + alters.group(3));
        }
        assertThat(altered).containsExactly("workspace_listing.exported_hash", "workspace_export_log.uncatalogued_count",
                "workspace_export_log.ck_workspace_export_uncatalogued");
        // a column that other code reads gets a value that means "not known", never one that changes an earlier meaning
        assertThat(sql).contains("ADD COLUMN exported_hash CHAR(64);").contains("ADD COLUMN uncatalogued_count INTEGER NOT NULL DEFAULT 0;");
        // the only UPDATE is the backfill of listings that were exported, and it copies a hash, never a text
        Matcher updates = Pattern.compile("UPDATE (\\w+) SET ([^;]*);").matcher(sql);
        assertThat(updates.find()).isTrue();
        assertThat(updates.group(1)).isEqualTo("workspace_listing");
        assertThat(updates.group(2)).isEqualTo("exported_hash = content_hash WHERE exported_at IS NOT NULL");
        assertThat(updates.find()).isFalse();
    }
}
