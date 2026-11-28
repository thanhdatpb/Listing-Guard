package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.CloudflareAccessJwtVerifier;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogImporter;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTriggers;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditWorker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Takes the menu of the Console in its mode, for an ADMIN and a MEMBER, in a real browser (ADR-064): the server starts on a random port over the
 * synthetic workspace of {@link WorkspaceDemoDataset} and {@code scripts/capture-workspace-screens.py} runs in its "menu" form. The classic and the
 * workspace console are the two subclasses; both are a pilot with the role home, so the "Manage" group is the ADMIN's. Off unless
 * {@code LG_RUN_WORKSPACE_BROWSER=1}; {@code LG_WORKSPACE_SCREENSHOTS} keeps the PNG files, {@code LG_CHROMIUM_PATH} names an installed Chromium.
 */
abstract class ConsoleMenuBrowserBase {

    private static final String ADMIN_ASSERTION = "menu-admin-fixture";
    private static final String MEMBER_ASSERTION = "menu-member-fixture";

    @LocalServerPort int port;
    @TempDir Path files;
    @MockitoBean CloudflareAccessJwtVerifier verifier;
    @Autowired ClientProvisioning clients;
    @Autowired ConsoleAccountRepository console;
    @Autowired WorkspaceAccess access;
    @Autowired WorkspaceAccounts accounts;
    @Autowired WorkspaceProperties properties;
    @Autowired ListingImportService imports;
    @Autowired ListingEditor editor;
    @Autowired ListingCloner cloner;
    @Autowired ListingStatusService status;
    @Autowired WorkspaceListings listings;
    @Autowired WatchlistService watchlists;
    @Autowired TrademarkCatalogImporter importer;
    @Autowired TrademarkRulesets rulesets;
    @Autowired AuditWorker worker;
    @Autowired AuditTriggers triggers;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    /** {@code classic} or {@code workspace}. */
    abstract String mode();

    @Test
    void capturesTheMenuOfTheConsoleMode() throws Exception {
        var seeder = new WorkspaceDemoDataset.Seeder(clients, console, access, accounts, properties, imports, editor, cloner, status, listings,
                watchlists, importer, rulesets, worker, triggers, jdbc);
        WorkspaceDemoDataset.Seeded seeded = seeder.seed("menu-" + mode() + "-" + UUID.randomUUID().toString().substring(0, 8));
        when(verifier.verifyEmail(ADMIN_ASSERTION)).thenReturn(seeded.actors().get("admin").email());
        when(verifier.verifyEmail(MEMBER_ASSERTION)).thenReturn(seeded.actors().get("avery").email());
        // the database says which console this is, the pages only show it
        assertThat(jdbc.queryForObject("SELECT role FROM account WHERE email = ?", String.class, seeded.actors().get("avery").email())).isEqualTo("MEMBER");

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("menu", true);
        manifest.put("mode", mode());
        Path file = Files.writeString(files.resolve("manifest.json"), mapper.writeValueAsString(manifest));
        Path script = Path.of("..", "scripts", "capture-workspace-screens.py").toAbsolutePath().normalize();
        String python = System.getenv().getOrDefault("LG_WORKSPACE_PYTHON", System.getProperty("os.name").startsWith("Windows") ? "python" : "python3");
        Path log = files.resolve("browser.log");
        ProcessBuilder command = new ProcessBuilder(python, script.toString(), file.toString()).redirectErrorStream(true).redirectOutput(log.toFile());
        command.environment().put("LG_WORKSPACE_URL", "http://localhost:" + port);
        command.environment().put("LG_WORKSPACE_TEST_ASSERTION", ADMIN_ASSERTION);
        command.environment().put("LG_WORKSPACE_MEMBER_ASSERTION", MEMBER_ASSERTION);
        String target = System.getenv("LG_WORKSPACE_SCREENSHOTS");
        if (target != null && !target.isBlank()) {
            command.environment().put("LG_WORKSPACE_SCREENSHOTS", script.getParent().getParent().resolve(target).normalize().toString());
        }
        Process child = command.start();
        if (!child.waitFor(300, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("Console menu Playwright timed out");
        }
        assertThat(child.exitValue()).as("Console menu Playwright%n%s", Files.readString(log)).isZero();
    }
}
