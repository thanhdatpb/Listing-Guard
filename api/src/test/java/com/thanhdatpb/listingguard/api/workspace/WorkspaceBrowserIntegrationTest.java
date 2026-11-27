package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Starts the Console on a random port over the synthetic workspace of {@link WorkspaceDemoDataset} and runs {@code scripts/capture-workspace-screens.py}
 * (Playwright) against it: the import, the list, the detail with its highlights, the editor, the clone, the comparison with the original, the history,
 * the status gate, the conflict of a re-import and the audit page. Off unless {@code LG_RUN_WORKSPACE_BROWSER=1}. Set {@code LG_WORKSPACE_SCREENSHOTS}
 * to a folder to keep the screenshots, {@code LG_CHROMIUM_PATH} to use an installed Chromium and {@code LG_WORKSPACE_PYTHON} for the Python command.
 * The screens are fictional: no page here writes anything but the seeding, and every re-import stops at its preview.
 */
@EnabledIfEnvironmentVariable(named = "LG_RUN_WORKSPACE_BROWSER", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.default-locale=en",
        "listing-guard.console.cookie-secure=false",
        "listing-guard.console.base-url=http://localhost",
        "listing-guard.console.auth-mode=cloudflare-access",
        "listing-guard.console.access-team-domain=https://example.cloudflareaccess.com",
        "listing-guard.console.access-aud=workspace-test",
        "listing-guard.console.access-allowed-domains=example.test",
        "listing-guard.console.access-auto-create=false",
        "listing-guard.demo.enabled=true",
        "listing-guard.workspace.enabled=true",
        "listing-guard.workspace.audit.enabled=true",
        "listing-guard.workspace.audit.auto-start=false",
        "listing-guard.workspace.alerts.email.enabled=true",
        "spring.servlet.multipart.max-file-size=25MB",
        "spring.servlet.multipart.max-request-size=60MB"
})
@ActiveProfiles({"test", "dev"})
@Import(TestcontainersConfig.class)
class WorkspaceBrowserIntegrationTest {
    private static final String ASSERTION = "workspace-browser-fixture";

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

    @Test
    void capturesTheWorkspaceOfTheSyntheticDataset() throws Exception {
        var seeder = new WorkspaceDemoDataset.Seeder(clients, console, access, accounts, properties, imports, editor, cloner, status, listings,
                watchlists, importer, rulesets, worker, triggers, jdbc);
        WorkspaceDemoDataset.Seeded seeded = seeder.seed("workspace-screenshot-" + UUID.randomUUID().toString().substring(0, 8));
        when(verifier.verifyEmail(ASSERTION)).thenReturn(seeded.actors().get("admin").email());

        // what the pages must show comes from the database, not from the script: the audit is DONE and the listing the highlights are taken from is New risk
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_audit_run WHERE client_id = ? AND status <> 'DONE'", Integer.class, seeded.client())).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM workspace_listing_audit WHERE listing_id = ?", String.class, seeded.listing("A", "TOTE-S")))
                .isEqualTo("NEW_RISK");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_alert_log WHERE client_id = ?", Integer.class, seeded.client())).isPositive();

        Path fileA = Files.write(files.resolve("sample-paper-studio.xlsm"), seeded.files().get("A"));
        Path fileB = Files.write(files.resolve("sample-home-goods-again.xlsx"), seeded.files().get("B-again"));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("accountA", seeded.accounts().get("A"));
        manifest.put("accountB", seeded.accounts().get("B"));
        manifest.put("fileA", fileA.toString());
        manifest.put("fileB", fileB.toString());
        manifest.put("expectedA", seeded.expected().get("A"));
        manifest.put("expectedB", seeded.expected().get("B-again"));
        manifest.put("detail", seeded.listing("A", "TOTE-S"));
        manifest.put("editor", seeded.listing("A", "MUG-004"));
        manifest.put("compare", seeded.listing("A", "MUG-001-B"));
        manifest.put("history", seeded.listing("A", "MUG-002-V2"));
        manifest.put("cloneSku", "TOTE-P");
        manifest.put("cloneTarget", "Pillow Home");
        manifest.put("statusSkus", java.util.List.of("TOTE-N", "MUG-004", "PILLOW-SET", "ETSY-TOTE-1"));
        Path file = Files.writeString(files.resolve("manifest.json"), mapper.writeValueAsString(manifest));
        browser(file);
    }

    private void browser(Path manifest) throws Exception {
        Path script = Path.of("..", "scripts", "capture-workspace-screens.py").toAbsolutePath().normalize();
        String python = System.getenv().getOrDefault("LG_WORKSPACE_PYTHON",
                System.getProperty("os.name").startsWith("Windows") ? "python" : "python3");
        Path log = files.resolve("browser.log");
        ProcessBuilder command = new ProcessBuilder(python, script.toString(), manifest.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        command.environment().put("LG_WORKSPACE_URL", "http://localhost:" + port);
        command.environment().put("LG_WORKSPACE_TEST_ASSERTION", ASSERTION);
        String target = System.getenv("LG_WORKSPACE_SCREENSHOTS");
        if (target != null && !target.isBlank()) {
            command.environment().put("LG_WORKSPACE_SCREENSHOTS", script.getParent().getParent().resolve(target).normalize().toString());
        }
        Process child = command.start();
        if (!child.waitFor(420, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("Workspace Playwright timed out");
        }
        assertThat(child.exitValue()).as("Workspace Playwright%n%s", Files.readString(log)).isZero();
    }
}
