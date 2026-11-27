package com.thanhdatpb.listingguard.api.demo;

import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountImporter;
import com.thanhdatpb.listingguard.api.console.ConsoleProperties;
import com.thanhdatpb.listingguard.api.console.PilotMetrics;
import com.thanhdatpb.listingguard.api.console.bulk.BulkCheckService;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import com.thanhdatpb.listingguard.api.console.reaudit.ReauditService;
import com.thanhdatpb.listingguard.api.edit.ListingEditRequest;
import com.thanhdatpb.listingguard.api.edit.ListingEditService;
import com.thanhdatpb.listingguard.api.feedback.FeedbackVerdict;
import com.thanhdatpb.listingguard.api.feedback.FindingFeedbackService;
import com.thanhdatpb.listingguard.api.feedback.Reviewer;
import com.thanhdatpb.listingguard.api.history.CheckHistoryRecorder;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningProperties;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningRequest;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningService;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogImporter;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import com.thanhdatpb.listingguard.trademark.catalog.load.CatalogManifest;
import com.thanhdatpb.listingguard.trademark.catalog.load.NormalizationIdentity;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Operator-only seed and reset. The database marker, not the configured name, authorizes deletion. */
@Component
@ConditionalOnProperty(prefix = "listing-guard.demo", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ConsoleProperties.class)
public class DemoWorkspaceCommands {
    private static final String CATALOG = "demo-fictional-2026-11";
    private static final List<String> PURGE = List.of("finding_verdict", "check_history_event", "reaudit_run",
            "rewrite_suggestion_run", "personalization_screening", "image_check", "variation_check", "trademark_check",
            "listing_check", "trademark_watchlist", "client_usage", "api_key", "account");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EntityManager entities;
    private final ClientProvisioning clients;
    private final ConsoleAccountImporter accounts;
    private final ConsoleProperties console;
    private final TrademarkCatalogImporter catalogs;
    private final TrademarkRulesets rulesets;
    private final WatchlistService watchlists;
    private final ObjectProvider<BulkCheckService> bulk;
    private final ObjectProvider<ReauditService> reaudit;
    private final ListingEditService edit;
    private final PhraseScreeningProperties phraseProperties;
    private final ObjectProvider<PhraseScreeningService> phrases;
    private final FindingFeedbackService feedback;
    private final CheckHistoryRecorder history;
    private final PilotMetrics metrics;
    private final DemoClock clock;
    private final String name;
    private final Path catalogDirectory;
    private final int feedbackPercent;
    private final int falsePositivePercent;

    public DemoWorkspaceCommands(JdbcTemplate jdbc, TransactionTemplate transactions, EntityManager entities,
                                 ClientProvisioning clients, ConsoleAccountImporter accounts, ConsoleProperties console,
                                 TrademarkCatalogImporter catalogs, TrademarkRulesets rulesets, WatchlistService watchlists,
                                 ObjectProvider<BulkCheckService> bulk, ObjectProvider<ReauditService> reaudit,
                                 ListingEditService edit, PhraseScreeningProperties phraseProperties,
                                 ObjectProvider<PhraseScreeningService> phrases, FindingFeedbackService feedback,
                                 CheckHistoryRecorder history, PilotMetrics metrics, Clock clock,
                                 @Value("${listing-guard.demo.client-name:demo}") String name,
                                 @Value("${listing-guard.demo.catalog-dir:catalogs/demo}") String catalogDirectory,
                                 @Value("${listing-guard.demo.feedback-percent:35}") int feedbackPercent,
                                 @Value("${listing-guard.demo.false-positive-percent:25}") int falsePositivePercent) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.entities = entities;
        this.clients = clients;
        this.accounts = accounts;
        this.console = console;
        this.catalogs = catalogs;
        this.rulesets = rulesets;
        this.watchlists = watchlists;
        this.bulk = bulk;
        this.reaudit = reaudit;
        this.edit = edit;
        this.phraseProperties = phraseProperties;
        this.phrases = phrases;
        this.feedback = feedback;
        this.history = history;
        this.metrics = metrics;
        this.clock = (DemoClock) clock;
        this.name = name;
        this.catalogDirectory = Path.of(catalogDirectory);
        this.feedbackPercent = percentage(feedbackPercent);
        this.falsePositivePercent = percentage(falsePositivePercent);
    }

    public void execute(ApplicationArguments args) {
        List<String> command = args.getNonOptionArgs();
        if (command.equals(List.of("demo", "reset")) && args.getOptionNames().isEmpty()) {
            reset();
        } else if (command.equals(List.of("demo", "seed"))
                && List.of("weeks", "seed", "admin-email").containsAll(args.getOptionNames())) {
            seed(Integer.parseInt(option(args, "weeks", "6")), Long.parseLong(option(args, "seed", "42")),
                    option(args, "admin-email", "demo-admin@example.test"));
        } else {
            throw new IllegalArgumentException("Use demo seed [--weeks=6] [--seed=42] [--admin-email=<email>] or demo reset.");
        }
    }

    public UUID seed(int weeks, long randomSeed, String adminEmail) {
        if (weeks < 1 || weeks > 12 || !ClientProvisioning.isValidName(name)
                || adminEmail == null || !adminEmail.matches("[^\\s,\"]+@[^\\s,\"]+")) {
            throw new IllegalArgumentException("Use a valid demo client name, 1–12 weeks and a valid administrator email.");
        }
        String email = adminEmail.toLowerCase(Locale.ROOT);
        BulkCheckService bulkChecks = bulk.getIfAvailable();
        ReauditService reaudits = reaudit.getIfAvailable();
        if (bulkChecks == null || reaudits == null) {
            throw new IllegalArgumentException("Enable Console and periodic re-audit for the demo seed job.");
        }
        return transactions.execute(status -> {
            Workspace existing = lockedWorkspace();
            if (existing != null) {
                if (!Integer.valueOf(weeks).equals(existing.weeks()) || !Long.valueOf(randomSeed).equals(existing.seed())
                        || accountIds(existing.client(), email).isEmpty()) {
                    throw new IllegalArgumentException("The demo workspace already exists with different settings. Reset it explicitly.");
                }
                return existing.client();
            }
            var ruleset = rulesets.ruleset();
            var catalog = catalogs.importCatalog(CatalogManifest.load(catalogDirectory).entry(CATALOG),
                    catalogDirectory.resolve("raw"), new TextNormalizer(ruleset.normalization()), NormalizationIdentity.of(ruleset));
            UUID client = clients.createClient(name).getId();
            clients.setPlan(name, PlanCode.SELLER);
            entities.flush();
            jdbc.update("INSERT INTO demo_workspace (client_id, catalog_version_id, weeks, seed) VALUES (?, ?, ?, ?)",
                    client, catalog.catalogVersionId(), weeks, randomSeed);
            var imported = accounts.importTable("email,role,display_name,client\n" + email + ",ADMIN,Demo Admin," + name,
                    false, false);
            if (!imported.ok() || (console.cloudflareAccessEnabled() && !imported.notAllowed().isEmpty())) {
                throw new IllegalArgumentException("Cannot provision the demo administrator. Check the email, allowlist and tenant membership.");
            }
            entities.flush();
            UUID account = accountIds(client, email).getFirst();
            watchlists.replace(client, List.of(watch("Orbit Owls", List.of("Orbit Owl Club")),
                    watch("Golden State Warriors", List.of()), watch("Dallas Cowboys", List.of()), watch("Chicago Cubs", List.of())),
                    "console:" + account, null);
            populate(client, account, weeks, new Random(randomSeed), bulkChecks, reaudits);
            return client;
        });
    }

    public void reset() {
        transactions.executeWithoutResult(status -> {
            Workspace workspace = lockedWorkspace();
            if (workspace == null) {
                return;
            }
            UUID client = workspace.client();
            jdbc.update("DELETE FROM login_token WHERE email IN (SELECT email FROM account WHERE client_id = ?)", client);
            for (String table : PURGE) {
                jdbc.update("DELETE FROM " + table + " WHERE client_id = ?", client);
            }
            jdbc.update("DELETE FROM client WHERE id = ? AND EXISTS (SELECT 1 FROM demo_workspace WHERE client_id = ?)",
                    client, client);
        });
    }

    private Workspace lockedWorkspace() {
        jdbc.execute("SELECT pg_advisory_xact_lock(201801, 55)");
        Workspace result = jdbc.query("""
                SELECT c.id, d.weeks, d.seed FROM client c LEFT JOIN demo_workspace d ON d.client_id = c.id
                WHERE c.name = ? FOR UPDATE OF c""", (rs, index) -> new Workspace(rs.getObject(1, UUID.class),
                rs.getObject(2, Integer.class), rs.getObject(3, Long.class)), name).stream().findFirst().orElse(null);
        if (result != null && result.weeks() == null) {
            throw new IllegalArgumentException("The configured client is not a marked demo workspace.");
        }
        return result;
    }

    private List<UUID> accountIds(UUID client, String email) {
        return jdbc.query("SELECT id FROM account WHERE client_id = ? AND email = ?",
                (rs, index) -> rs.getObject(1, UUID.class), client, email);
    }

    private void populate(UUID client, UUID account, int weeks, Random random, BulkCheckService bulkChecks,
                          ReauditService reaudits) {
        List<DemoFixtures.Listing> listings = new ArrayList<>(DemoFixtures.listings("listings.csv"));
        List<DemoFixtures.Listing> edits = DemoFixtures.listings("edits.csv");
        List<DemoFixtures.Listing> audit = DemoFixtures.listings("reaudit.csv");
        Collections.shuffle(listings, random);
        Instant generatedAfter = jdbc.queryForObject("SELECT transaction_timestamp()", Timestamp.class).toInstant();
        Instant end = generatedAfter.minusMillis(1).truncatedTo(ChronoUnit.DAYS);
        int days = weeks * 7;
        for (int day = 0; day < days; day++) {
            int start = day * listings.size() / days;
            int finish = (day + 1) * listings.size() / days;
            int offset = day;
            Instant time = end.minus(days - 1L - day, ChronoUnit.DAYS);
            clock.at(time, () -> {
                for (EditorMarketplace marketplace : EditorMarketplace.values()) {
                    var inputs = listings.subList(start, finish).stream().filter(row -> row.marketplace() == marketplace)
                            .map(row -> BulkCheckService.Input.of(row.line(), row.sku(), null, null, Map.of(), row.input())).toList();
                    if (!inputs.isEmpty()) {
                        recordBulk(client, account, marketplace, bulkChecks.run(client, marketplace, inputs, true), random);
                    }
                }
                if (offset == days / 2) {
                    for (var row : edits) {
                        recordEdit(client, account, row);
                    }
                }
                if (offset == 0 || offset == days - 1) {
                    boolean latest = offset != 0;
                    reaudits.run(client, account, audit.getFirst().marketplace(), "demo-csv", "Internal demo",
                            audit.stream().map(row -> ReauditService.Input.of(row.line(), row.sku(),
                                    latest ? row.withTitle("Blue mountain print " + row.sku()).input() : row.input())).toList());
                }
                if (phraseProperties.enabled()) {
                    if (offset == 1) screenPhrases(client, account, "1");
                    if (offset == days / 2) screenPhrases(client, account, "2");
                    if (offset == days - 1) screenPhrases(client, account, "3");
                }
                entities.flush();
                backdate(client, time, generatedAfter);
                return null;
            });
        }
    }

    private void recordBulk(UUID client, UUID account, EditorMarketplace marketplace, BulkResult result, Random random) {
        metrics.record(client, marketplace.code(), result.scope(), result.rows().stream().filter(BulkResult.Row::checked)
                .map(row -> {
                    var trademark = row.detail().trademark();
                    return new PilotMetrics.Observation(trademark.kind() == BulkResult.Kind.RESULT ? trademark.level() : null, false);
                }).toList());
        for (var row : result.rows()) {
            if (row.detail() != null) {
                recordBulkHistory(client, account, marketplace.code(), row.sku(), row.detail().rules().items());
                recordBulkHistory(client, account, marketplace.code(), row.sku(), row.detail().trademark().items());
            }
        }
        for (var target : result.targets()) {
            if (random.nextInt(100) < feedbackPercent) {
                FeedbackVerdict verdict = random.nextInt(100) < falsePositivePercent
                        ? FeedbackVerdict.FALSE_POSITIVE : FeedbackVerdict.CORRECT;
                feedback.record(client, Reviewer.account(account), target, verdict, null);
            }
        }
    }

    private void recordBulkHistory(UUID client, UUID account, String marketplace, String sku, List<BulkResult.Item> items) {
        items.stream().map(BulkResult.Item::target).filter(java.util.Objects::nonNull).findFirst().ifPresent(target ->
                history.account(client, account, target.pack(), target.checkId(), marketplace, 1, sku));
    }

    private void recordEdit(UUID client, UUID account, DemoFixtures.Listing row) {
        String marketplace = row.marketplace().code();
        var result = edit.checkConsole(client, new ListingEditRequest(marketplace, row.input(),
                row.withTitle("Blue woodland print " + row.sku()).input(), null, null)).result();
        for (var side : List.of(result.before(), result.after())) {
            if (side.listingRules() != null) {
                history.account(client, account, "listing-rules", side.listingRules().checkId(), marketplace, 1, row.sku());
            }
            if (side.trademarkRisk() != null) {
                history.account(client, account, "trademark-risk", side.trademarkRisk().checkId(), marketplace, 1, row.sku());
            }
        }
        var trademark = result.after().trademarkRisk();
        if (result.after().listingRules() != null) {
            String risk = trademark != null && "COMPLETE".equals(trademark.completeness().name())
                    ? trademark.riskLevel().name() : null;
            metrics.record(client, marketplace, List.of(new PilotMetrics.Observation(risk, true)));
        }
    }

    private void screenPhrases(UUID client, UUID account, String group) {
        var inputs = DemoFixtures.rows("phrases.csv").stream().map(row -> row.cells()).filter(cells -> cells.get(0).equals(group))
                .map(cells -> new PhraseScreeningRequest.PhraseInput(cells.get(1), Long.valueOf(cells.get(2)))).toList();
        phrases.getObject().screenConsole(client, account, new PhraseScreeningRequest(inputs, "ETSY", null));
    }

    private void backdate(UUID client, Instant time, Instant generatedAfter) {
        jdbc.update("UPDATE listing_check SET created_at = ? WHERE client_id = ? AND created_at >= ?",
                Timestamp.from(time), client, Timestamp.from(generatedAfter));
        jdbc.update("UPDATE trademark_check SET created_at = ? WHERE client_id = ? AND created_at >= ?",
                Timestamp.from(time), client, Timestamp.from(generatedAfter));
    }

    private static EntryInput watch(String mark, List<String> aliases) {
        return new EntryInput(mark, aliases, List.of(25), null, "Sample data for an internal demonstration", true);
    }

    private static int percentage(int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("Demo feedback percentages must be between 0 and 100.");
        }
        return value;
    }

    private static String option(ApplicationArguments args, String name, String fallback) {
        if (!args.containsOption(name)) {
            return fallback;
        }
        List<String> values = args.getOptionValues(name);
        if (values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("Specify --" + name + " once with a value.");
        }
        return values.getFirst();
    }

    private record Workspace(UUID client, Integer weeks, Long seed) {}
}
