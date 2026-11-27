package com.thanhdatpb.listingguard.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.CloudflareAccessJwtVerifier;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogImporter;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTriggers;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditWorker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Seeds the synthetic workspace of {@link WorkspaceDemoDataset} through the real services and reads what is left in the database: the audit runs are
 * DONE, every listing has an audit, and the five audit states, both statuses, the clones and the four owners are all there. It is the proof that the
 * screenshots are taken from a workspace that was not faked. Needs the database of {@link TestcontainersConfig} and nothing else (no browser).
 */
@SpringBootTest(properties = {
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
class WorkspaceDemoDatasetIntegrationTest {

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

    @Test
    void theSyntheticWorkspaceIsAuditedToTheEndAndShowsEveryState() throws Exception {
        WorkspaceDemoDataset.Seeder seeder = new WorkspaceDemoDataset.Seeder(clients, console, access, accounts, properties, imports, editor, cloner, status,
                listings, watchlists, importer, rulesets, worker, triggers, jdbc);

        WorkspaceDemoDataset.Seeded seeded = seeder.seed("workspace-demo-" + UUID.randomUUID().toString().substring(0, 8));

        UUID client = seeded.client();
        // every run of the background audit has ended in DONE, and nothing is waiting; the new watchlist word made one run for each of the three accounts
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_audit_run WHERE client_id = ?", Integer.class, client)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_audit_run WHERE client_id = ? AND status <> 'DONE'", Integer.class, client)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_audit_run WHERE client_id = ? AND reason = 'WATCHLIST' AND status = 'DONE'",
                Integer.class, client)).isEqualTo(3);

        // 23 imported listings and five clones, each with an audit that is up to date with its content
        assertThat(count("SELECT count(*) FROM workspace_listing WHERE client_id = ? AND deleted_at IS NULL", client)).isEqualTo(28);
        assertThat(count("SELECT count(*) FROM workspace_listing_audit WHERE client_id = ?", client)).isEqualTo(28);
        assertThat(count("SELECT count(*) FROM workspace_listing WHERE client_id = ? AND source = 'CLONE'", client)).isEqualTo(5);

        Map<String, String> states = states(client);
        System.out.println("WORKSPACE DEMO STATES " + states);
        Map<String, Long> perState = states.values().stream().collect(Collectors.groupingBy(s -> s, LinkedHashMap::new, Collectors.counting()));
        System.out.println("WORKSPACE DEMO COUNTS " + perState);
        assertThat(perState.keySet()).containsExactlyInAnyOrder("CLEAN", "WARNING", "ERROR", "NEW_RISK", "INCOMPLETE");

        assertThat(states).containsEntry("A/MUG-001", "CLEAN").containsEntry("ETSY/ETSY-MUG-1", "CLEAN").containsEntry("ETSY/ETSY-PILLOW-1", "CLEAN")
                .containsEntry("B/PILLOW-P", "CLEAN").containsEntry("A/MUG-001-B", "CLEAN").containsEntry("A/MUG-003", "WARNING").containsEntry("A/POSTER-A2", "WARNING")
                .containsEntry("A/MUG-004", "ERROR").containsEntry("ETSY/ETSY-TOTE-1", "ERROR").containsEntry("ETSY/ETSY-POSTER-1", "ERROR")
                .containsEntry("A/TOTE-S", "NEW_RISK").containsEntry("ETSY/ETSY-TOTE-2", "NEW_RISK")
                .containsEntry("B/PILLOW-SET", "INCOMPLETE").containsEntry("B/PILLOW-BOLSTER", "INCOMPLETE").containsEntry("B/PILLOW-OUTDOOR", "INCOMPLETE");

        // both statuses, four owners, three accounts
        assertThat(count("SELECT count(*) FROM workspace_listing WHERE client_id = ? AND status = 'READY_TO_EXPORT'", client)).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM workspace_listing WHERE client_id = ? AND status = 'DRAFT'", client)).isEqualTo(22);
        assertThat(count("SELECT count(DISTINCT owner_account_id) FROM workspace_listing WHERE client_id = ?", client)).isEqualTo(4);
        assertThat(seeded.accounts()).containsOnlyKeys("A", "B", "ETSY");

        // the clones: one keeps the title of the original, one went to the account with the other template
        UUID same = seeded.listing("A", "MUG-001-B");
        assertThat(listings.sameTitle(client, seeded.accounts().get("A"), same, listings.find(client, same).orElseThrow().title()))
                .extracting(WorkspaceListings.TitleMatch::sku).contains("MUG-001");
        assertThat(seeded.listing("B", "WALL-P")).isNotNull();
        assertThat(seeded.listing("B", "WALL-A3")).isNotNull();
        // the clone that was edited has two versions in its history
        assertThat(count("SELECT count(*) FROM workspace_listing_history WHERE listing_id = ?", seeded.listing("A", "MUG-002-V2"))).isEqualTo(2);

        // no listing text of the dataset is a real brand or team name: the words of the watchlist and of the catalog are the fictional ones
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_listing WHERE client_id = ? AND (title ~* '(nike|disney|nfl|marvel|adidas)')",
                Integer.class, client)).isZero();
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    private Map<String, String> states(UUID client) {
        Map<String, String> states = new LinkedHashMap<>();
        jdbc.query("""
                SELECT a.name_key, l.sku, au.state FROM workspace_listing l
                JOIN workspace_listing_audit au ON au.listing_id = l.id
                JOIN (SELECT id, CASE name WHEN 'Sample Paper Studio' THEN 'A' WHEN 'Sample Home Goods' THEN 'B' ELSE 'ETSY' END AS name_key
                        FROM workspace_account) a ON a.id = l.account_id
                WHERE l.client_id = ? AND l.deleted_at IS NULL ORDER BY a.name_key, l.sku""", rs -> {
            states.put(rs.getString(1) + "/" + rs.getString(2), rs.getString(3));
        }, client);
        return states;
    }
}
