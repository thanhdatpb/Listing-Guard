package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.CloudflareAccessJwtVerifier;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.TemplateService.SourceFile;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTriggers;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditWorker;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import com.thanhdatpb.listingguard.workspace.sheets.SheetReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Starts the Console on a random port with synthetic workspace data and runs {@code scripts/test-console-workspace-editor.py} (Playwright) against it
 * (ADR-062). Off unless {@code LG_RUN_WORKSPACE_BROWSER=1}, like {@link WorkspaceBrowserIntegrationTest}; it needs Docker for the database and Python
 * Playwright with Chromium. Set {@code LG_WORKSPACE_SCREENSHOTS} to a folder to keep the screenshots. Not run in the session that wrote it.
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
        "listing-guard.workspace.enabled=true",
        "listing-guard.workspace.audit.enabled=true",
        "listing-guard.workspace.audit.auto-start=false",
        "spring.servlet.multipart.max-file-size=25MB",
        "spring.servlet.multipart.max-request-size=60MB"
})
@ActiveProfiles({"test", "dev"})
@Import(TestcontainersConfig.class)
class WorkspaceEditorBrowserIntegrationTest {
    private static final String ASSERTION = "workspace-editor-browser-fixture";
    private static final String EMAIL = "workspace-editor-" + UUID.randomUUID() + "@example.test";
    private static final String WATCH_MARK = "Vellumzoq";

    @LocalServerPort int port;
    @TempDir Path files;
    @MockitoBean CloudflareAccessJwtVerifier verifier;
    @Autowired ClientProvisioning clients;
    @Autowired ConsoleAccountRepository users;
    @Autowired WorkspaceAccess access;
    @Autowired WorkspaceAccounts accounts;
    @Autowired WorkspaceProperties properties;
    @Autowired ListingImportService imports;
    @Autowired WatchlistService watchlists;
    @Autowired AuditWorker worker;
    @Autowired AuditTriggers triggers;
    @Autowired JdbcTemplate jdbc;

    @Test
    void editsClonesComparesRestoresAndMovesSyntheticListingsInABrowser() throws Exception {
        UUID client = clients.createClient("workspace-editor-" + UUID.randomUUID().toString().substring(0, 8)).getId();
        users.saveAndFlush(new ConsoleAccount(EMAIL, client, ConsoleRole.ADMIN));
        when(verifier.verifyEmail(ASSERTION)).thenReturn(EMAIL);
        Actor admin = access.actor(new ConsoleUser(EMAIL, client)).orElseThrow();
        UUID amazon = accounts.create(client, Marketplace.AMAZON, "Sample Paper Studio", null, ExportFormat.XLSM).id();
        UUID etsy = accounts.create(client, Marketplace.ETSY, "Sample Etsy Shop", null, ExportFormat.XLSM).id();
        watchlists.replace(client, List.of(new EntryInput(WATCH_MARK, List.of(), List.of(), null, "Fictional editor fixture", true)), "test", null);

        byte[] file = WorkspaceFixtures.flatFile("B-");
        SheetGrid grid = SheetReader.read(file, "Template", properties.limits(false));
        var view = imports.mappingView(admin, amazon, TemplateKind.AMAZON_BULK, grid, 3);
        Map<Integer, String> targets = new LinkedHashMap<>();
        view.columns().forEach(c -> {
            if (!c.target().isEmpty()) {
                targets.put(c.index(), c.target());
            }
        });
        imports.commit(admin, new Params(amazon, TemplateKind.AMAZON_BULK, admin.accountId(), ListingStatus.DRAFT, "Template", 3, 4, targets, Map.of()),
                grid, new SourceFile(SheetReader.detect(file, properties.limits(false)), "synthetic.xlsx", file), null);
        UUID listing = jdbc.queryForObject("SELECT id FROM workspace_listing WHERE client_id = ? AND sku = 'B-S2'", UUID.class, client);
        triggers.checkDrift();
        worker.drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_listing_audit WHERE listing_id = ?", Integer.class, listing)).isOne();

        browser(listing, etsy);
    }

    private void browser(UUID listing, UUID etsy) throws Exception {
        Path script = Path.of("..", "scripts", "test-console-workspace-editor.py").toAbsolutePath().normalize();
        String python = System.getenv().getOrDefault("LG_WORKSPACE_PYTHON", System.getProperty("os.name").startsWith("Windows") ? "python" : "python3");
        Path log = files.resolve("editor.log");
        ProcessBuilder command = new ProcessBuilder(python, script.toString(), listing.toString(), etsy.toString()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        command.environment().put("LG_WORKSPACE_URL", "http://localhost:" + port);
        command.environment().put("LG_WORKSPACE_TEST_ASSERTION", ASSERTION);
        String target = System.getenv("LG_WORKSPACE_SCREENSHOTS");
        if (target != null && !target.isBlank()) {
            command.environment().put("LG_WORKSPACE_SCREENSHOTS", script.getParent().getParent().resolve(target).normalize().toString());
        }
        Process child = command.start();
        if (!child.waitFor(240, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("Workspace editor Playwright timed out");
        }
        assertThat(child.exitValue()).as("Workspace editor Playwright%n%s", Files.readString(log)).isZero();
    }
}
