package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.CloudflareAccessJwtVerifier;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogImporter;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTriggers;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditWorker;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    private static final String EMAIL = "workspace-reviewer@example.test";
    private static final String SKU = "TOTE-001";
    private static final String WATCH_MARK = "Vellumzoq";
    private static final List<String> COLUMNS = List.of("item_sku", "item_name", "product_description", "bullet_point1", "standard_price", "quantity");

    @LocalServerPort int port;
    @TempDir Path files;
    @MockitoBean CloudflareAccessJwtVerifier verifier;
    @Autowired ClientProvisioning clients;
    @Autowired ConsoleAccountRepository users;
    @Autowired WorkspaceAccounts accounts;
    @Autowired TrademarkCatalogImporter importer;
    @Autowired TrademarkRulesets rulesets;
    @Autowired WatchlistService watchlists;
    @Autowired AuditWorker worker;
    @Autowired AuditTriggers triggers;
    @Autowired JdbcTemplate jdbc;

    @Test
    void capturesTheImportAndAuditOfSyntheticListings() throws Exception {
        UUID client = clients.createClient("workspace-screenshot-demo").getId();
        users.saveAndFlush(new ConsoleAccount(EMAIL, client, ConsoleRole.ADMIN));
        when(verifier.verifyEmail(ASSERTION)).thenReturn(EMAIL);
        UUID account = accounts.create(client, Marketplace.AMAZON, "Sample Paper Studio", null, ExportFormat.XLSM).id();

        Path demo = Path.of("..", "catalogs", "demo");
        var ruleset = rulesets.ruleset();
        var catalog = importer.importCatalog(CatalogManifest.load(demo).entry("demo-fictional-2026-11"),
                demo.resolve("raw"), new TextNormalizer(ruleset.normalization()), NormalizationIdentity.of(ruleset));
        jdbc.update("INSERT INTO demo_workspace (client_id, catalog_version_id, weeks, seed) VALUES (?, ?, ?, ?)",
                client, catalog.catalogVersionId(), 1, 42);

        EntryInput original = watchEntry("Flarbithene");
        watchlists.replace(client, List.of(original), "test", null);
        var risk = row(SKU, "Quonkleberry " + WATCH_MARK + " canvas tote", "Light canvas for daily errands.");
        var update = row("POSTER-002", "Botanical print", "A small paper print with leaf shapes.");
        var same = row("MUG-003", "Speckled ceramic mug", "A ceramic mug with a speckled glaze.");
        var first = workbook("spring-inventory.xlsx", List.of(risk, update, same));
        browser("seed", account, first, null);
        worker.drain();
        UUID listing = jdbc.queryForObject("SELECT id FROM workspace_listing WHERE client_id = ? AND sku = ?", UUID.class, client, SKU);
        assertThat(auditState(listing)).isNotEqualTo("NEW_RISK");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_listing_finding WHERE listing_id = ? AND source = 'CATALOG'",
                Integer.class, listing)).isPositive();

        watchlists.replace(client, List.of(original, watchEntry(WATCH_MARK)), "test", null);
        triggers.checkDrift();
        worker.drain();
        assertThat(auditState(listing)).isEqualTo("NEW_RISK");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_listing_finding WHERE listing_id = ? AND source = 'WATCHLIST' AND is_new",
                Integer.class, listing)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_audit_run WHERE client_id = ? AND reason = 'WATCHLIST' AND status = 'DONE'",
                Integer.class, client)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_alert_log WHERE client_id = ?", Integer.class, client))
                .isEqualTo(1);

        var second = workbook("summer-inventory.xlsx", List.of(risk,
                row("POSTER-002", "Botanical print", "A large paper print with leaf shapes."), same,
                row("CUSHION-004", "Striped cushion cover", "A cotton cover with wide stripes."),
                row("", "Desk notebook", "A paper notebook with ruled pages.")));
        browser("inspect", account, second, listing);
    }

    private String auditState(UUID listing) {
        return jdbc.queryForObject("SELECT state FROM workspace_listing_audit WHERE listing_id = ?", String.class, listing);
    }

    private static EntryInput watchEntry(String phrase) {
        return new EntryInput(phrase, List.of(), List.of(), null, "Fictional screenshot fixture", true);
    }

    private static List<String> row(String sku, String title, String description) {
        return List.of(sku, title, description, "Gift for a household", "19.99", "5");
    }

    private Path workbook(String name, List<List<String>> rows) throws Exception {
        return Files.write(files.resolve(name), WorkspaceFixtures.flatFile("", COLUMNS, rows));
    }

    private void browser(String phase, UUID account, Path workbook, UUID listing) throws Exception {
        Path script = Path.of("..", "scripts", "capture-workspace-screens.py").toAbsolutePath().normalize();
        String python = System.getenv().getOrDefault("LG_WORKSPACE_PYTHON",
                System.getProperty("os.name").startsWith("Windows") ? "python" : "python3");
        Path log = files.resolve(phase + ".log");
        ProcessBuilder command = new ProcessBuilder(python, script.toString(), phase, account.toString(), workbook.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        command.environment().put("LG_WORKSPACE_URL", "http://localhost:" + port);
        command.environment().put("LG_WORKSPACE_TEST_ASSERTION", ASSERTION);
        if (listing != null) {
            command.environment().put("LG_WORKSPACE_DETAIL", listing.toString());
        }
        String target = System.getenv("LG_WORKSPACE_SCREENSHOTS");
        if (target != null && !target.isBlank()) {
            command.environment().put("LG_WORKSPACE_SCREENSHOTS", script.getParent().getParent().resolve(target).normalize().toString());
        }
        Process child = command.start();
        if (!child.waitFor(180, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("Workspace Playwright timed out in " + phase);
        }
        assertThat(child.exitValue()).as("Workspace Playwright %s%n%s", phase, Files.readString(log)).isZero();
    }
}
