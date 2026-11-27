package com.thanhdatpb.listingguard.api.workspace.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.trademark.AuditCatalogFixtures;
import com.thanhdatpb.listingguard.api.trademark.CatalogActivations;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogImporter;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import com.thanhdatpb.listingguard.api.workspace.TemplateService;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccounts;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ChangeKind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingSource;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The common setup of the integration tests of the workspace's background audit (ADR-061): the workspace, the audit and the e-mail alerts on, the
 * worker threads off (a test drains the queue itself), a catalog the runtime follows through the activation log, and a mailer that records what
 * would be sent. Every client, listing, mark and e-mail address is synthetic. Tests that extend this share one Spring context.
 */
@SpringBootTest(properties = {
        "listing-guard.workspace.enabled=true",
        "listing-guard.workspace.import-max-bytes=64KB",
        "listing-guard.workspace.max-template-bytes=64KB",
        "spring.servlet.multipart.max-file-size=25MB",
        "spring.servlet.multipart.max-request-size=60MB",
        "listing-guard.workspace.audit.enabled=true",
        "listing-guard.workspace.audit.auto-start=false",
        "listing-guard.workspace.alerts.email.enabled=true",
        "listing-guard.workspace.alerts.email.daily-cap=2",
        "listing-guard.workspace.alerts.email.excerpt-chars=12",
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test",
        "listing-guard.trademark.activation.follow=true",
        "listing-guard.trademark.activation.refresh-interval=1h"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
abstract class AuditIntegrationBase {

    protected record Team(Client client, ConsoleUser admin, ConsoleAccount adminAccount, ConsoleUser member, ConsoleAccount memberAccount) {
    }

    @Autowired
    protected ClientProvisioning provisioning;
    @Autowired
    protected ConsoleAccountRepository console;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ObjectMapper mapper;
    @Autowired
    protected WorkspaceAccounts accounts;
    @Autowired
    protected WorkspaceListings listings;
    @Autowired
    protected TemplateService templateService;
    @Autowired
    protected AuditStore store;
    @Autowired
    protected AuditWorker worker;
    @Autowired
    protected AuditTriggers triggers;
    @Autowired
    protected WorkspaceAudit audit;
    @Autowired
    protected WatchlistService watchlists;
    @Autowired
    protected TrademarkCatalogImporter importer;
    @Autowired
    protected TrademarkRulesets trademarkRulesets;
    @Autowired
    protected TrademarkCatalogRuntime runtime;
    @Autowired
    protected CatalogActivations activations;

    @MockitoBean
    protected AlertMailer mailer;
    @MockitoBean
    protected JavaMailSender mailSender;

    @BeforeEach
    void noCatalogYet() {
        // the activation log is global: each test starts with nothing served and makes its own catalog
        jdbc.execute("TRUNCATE trademark_catalog_activation");
        runtime.evaluate();
        // one test must not see another's queued runs
        jdbc.update("DELETE FROM workspace_audit_run WHERE status IN ('QUEUED', 'RUNNING')");
    }

    @AfterEach
    void leaveNothingServed() {
        jdbc.execute("TRUNCATE trademark_catalog_activation");
        runtime.evaluate();
    }

    protected Team team() {
        Client client = provisioning.createClient("wa" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        String id = UUID.randomUUID().toString();
        ConsoleUser admin = new ConsoleUser("admin-" + id + "@example.test", client.getId());
        ConsoleUser member = new ConsoleUser("member-" + id + "@example.test", client.getId());
        ConsoleAccount adminAccount = console.saveAndFlush(new ConsoleAccount(admin.email(), client.getId(), ConsoleRole.ADMIN));
        ConsoleAccount memberAccount = console.saveAndFlush(new ConsoleAccount(member.email(), client.getId(), ConsoleRole.MEMBER));
        return new Team(client, admin, adminAccount, member, memberAccount);
    }

    protected SellerAccount etsyShop(Team team, String name) {
        return accounts.create(team.client().getId(), Marketplace.ETSY, name, "Nobrand", ExportFormat.XLSM);
    }

    /** Imports a synthetic catalog, activates it in the log and makes the runtime serve it. Returns its version. */
    protected String serve(String label, String... marks) {
        String[] lines = new String[marks.length];
        for (int i = 0; i < marks.length; i++) {
            lines[i] = AuditCatalogFixtures.registered(marks[i], "S-" + label + "-" + i);
        }
        String version = AuditCatalogFixtures.importCatalog(importer, trademarkRulesets, label + "-" + UUID.randomUUID().toString().substring(0, 8), lines);
        activations.activate(version, CatalogActivations.Reason.MANUAL, null, "test");
        runtime.refreshActivation();
        return version;
    }

    /** A valid Etsy listing with this title, as the workspace stores its standard fields. */
    protected ObjectNode fields(String title, String description, String... tags) {
        ObjectNode fields = mapper.createObjectNode();
        fields.put("title", title);
        fields.put("description", description);
        var array = fields.putArray("tags");
        for (String tag : tags) {
            array.add(tag);
        }
        fields.put("price", "19.99");
        fields.put("quantity", 5);
        fields.put("category", "Mugs");
        fields.put("who_made", "i_did");
        fields.put("when_made", "made_to_order");
        return fields;
    }

    protected Listing insert(Team team, SellerAccount account, Template template, String sku, ObjectNode fields, UUID owner) {
        UUID id = UUID.randomUUID();
        Listing listing = new Listing(id, team.client().getId(), account.id(), template.id(), sku, null, ListingRole.STANDALONE,
                fields.path("title").asText(), new LinkedHashMap<>(Map.of("title", fields.path("title").asText())), fields, "0".repeat(64), owner,
                ListingStatus.DRAFT, ListingSource.IMPORT, null, 0, null, null, null);
        listings.insertAll(team.client().getId(), List.of(new WorkspaceListings.Write(id, listing, ChangeKind.IMPORT, team.adminAccount().getId(), 0)));
        return listings.find(team.client().getId(), id).orElseThrow();
    }

    protected Template etsyTemplate(Team team, SellerAccount account) {
        return templateService.ensureEtsy(team.client().getId(), account.id());
    }

    protected String stateOf(Listing listing) {
        return jdbc.query("SELECT state FROM workspace_listing_audit WHERE listing_id = ?", (rs, n) -> rs.getString(1), listing.id()).stream()
                .findFirst().orElse("NONE");
    }

    protected List<Map<String, Object>> findingsOf(Listing listing) {
        return jdbc.queryForList("SELECT rule_id, level, source, field, span_start, span_end, mark, serial, is_new FROM workspace_listing_finding "
                + "WHERE listing_id = ? ORDER BY position", listing.id());
    }

    /** Puts one mark on the client's watchlist (replacing the list). */
    protected void watch(Team team, String mark) {
        watchlists.replace(team.client().getId(), List.of(new EntryInput(mark, List.of(), List.of(), null, "synthetic", true)), "test", null);
    }

    /**
     * Looks for a change of context and works what was queued, the way the worker does. The database is shared by every test of the run, so
     * this also works the accounts of other tests; a test asserts on its own client's runs and listings, never on how many runs there were.
     */
    protected void audit() {
        triggers.checkDrift();
        worker.drain();
    }
}
