package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.check.CheckScope;
import com.thanhdatpb.listingguard.api.check.FixesProperties;
import com.thanhdatpb.listingguard.api.console.bulk.BulkProperties;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The "Sẵn sàng" page of the pilot (ADR-053): is this deployment, and this client, ready to be handed to a seller. Each item is
 * a state of something that already exists, read where it lives, so the page cannot differ from what the checks use: the served
 * trademark catalog (its version, whether it carries distinctiveness tiers, whether a word list was used), the trademark ruleset, the
 * client's enabled watchlist entries, the client's accounts by role and whether the sign-in settings let each of them in, the default
 * scope of the bulk check (ADR-052) and the flags of the features that the pilot template switches off.
 *
 * <p>Nothing is stored, counted against a plan or written; the only reads of data are counts and one sample row of the catalog, and
 * every statement about a client filters by that client. The catalog is the deployment's, not a client's, and is named only to an
 * administrator of the pilot, on a page that exists only while {@code listing-guard.console.pilot} is true. The sentences are messages
 * (ADR-028), in the language of the page.
 */
@Service
@ConditionalOnExpression("${listing-guard.console.enabled:false} && ${listing-guard.console.pilot:false}")
public class PilotReadiness {

    /** One line of the page: what it is about, whether it is in order, what was found and what to do. */
    public record Item(String key, boolean ok, String title, String detail, String hint) {
    }

    public record Section(String title, List<Item> items) {
    }

    public record View(List<Section> sections, int ok, int total) {

        public boolean ready() {
            return ok == total;
        }
    }

    private final TrademarkCatalogRuntime catalog;
    private final TrademarkProperties trademark;
    private final TrademarkRulesets rulesets;
    private final JdbcTemplate jdbc;
    private final ConsoleAccountRepository accounts;
    private final ConsoleProperties console;
    private final BulkProperties bulk;
    private final FixesProperties fixes;
    private final ConsolePilot pilot;
    private final Environment environment;
    private final ConsoleMessages messages;
    /** The word list question is answered once for each catalog version: the answer cannot change, and the sample is a table read. */
    private final Map<Long, Boolean> wordLists = new ConcurrentHashMap<>();

    @Autowired
    public PilotReadiness(TrademarkCatalogRuntime catalog, TrademarkProperties trademark, TrademarkRulesets rulesets,
                          JdbcTemplate jdbc, ConsoleAccountRepository accounts, ConsoleProperties console, BulkProperties bulk,
                          FixesProperties fixes, ConsolePilot pilot, Environment environment, ConsoleMessages messages) {
        this.catalog = catalog;
        this.trademark = trademark;
        this.rulesets = rulesets;
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.console = console;
        this.bulk = bulk;
        this.fixes = fixes;
        this.pilot = pilot;
        this.environment = environment;
        this.messages = messages;
    }

    public View view(UUID clientId) {
        List<Section> sections = new ArrayList<>();
        sections.add(new Section(messages.get("readiness.section.trademark"), trademarkItems(clientId)));
        sections.add(new Section(messages.get("readiness.section.people"), peopleItems(clientId)));
        sections.add(new Section(messages.get("readiness.section.settings"), settingsItems()));
        int total = sections.stream().mapToInt(section -> section.items().size()).sum();
        int ok = (int) sections.stream().flatMap(section -> section.items().stream()).filter(Item::ok).count();
        return new View(List.copyOf(sections), ok, total);
    }

    // ---- the trademark catalog and ruleset

    private List<Item> trademarkItems(UUID clientId) {
        TrademarkCatalogRuntime.Status status = catalog.demoStatus(clientId).orElseGet(catalog::status);
        boolean ready = status.state() == TrademarkCatalogRuntime.State.READY;
        boolean demo = trademark.demoCatalog() || TrademarkProperties.isDemo(status.detail());
        String version = ready ? status.detail() : null;
        CatalogRow row = version == null ? null : catalogRow(version);
        boolean tier = ready && row != null && row.tier();
        Boolean wordList = tier ? hasWordList(row.id()) : null;

        List<Item> items = new ArrayList<>();
        String catalogDetail = !ready ? messages.get("readiness.catalog.detail." + status.state().name().toLowerCase(Locale.ROOT))
                : demo ? messages.get("readiness.catalog.detail.demo", version) : messages.get("readiness.catalog.detail.ready", version);
        items.add(item("catalog", ready && !demo, catalogDetail));
        items.add(item("tier", tier, messages.get(tier ? "readiness.tier.detail.yes" : "readiness.tier.detail.no")));
        items.add(item("wordlist", Boolean.TRUE.equals(wordList), messages.get(
                wordList == null ? "readiness.wordlist.detail.unknown"
                        : wordList ? "readiness.wordlist.detail.yes" : "readiness.wordlist.detail.no")));
        var ruleset = rulesets.ruleset();
        boolean calibrated = ruleset.calibration() != null;
        items.add(item("ruleset", calibrated, messages.get(calibrated ? "readiness.ruleset.detail.calibrated"
                : "readiness.ruleset.detail.plain", ruleset.version())));
        return items;
    }

    private record CatalogRow(long id, boolean tier) {
    }

    private CatalogRow catalogRow(String version) {
        List<CatalogRow> rows = jdbc.query("SELECT id, format_version FROM trademark_catalog_version WHERE version = ?",
                (rs, index) -> new CatalogRow(rs.getLong(1), "jsonl-2".equals(rs.getString(2))), version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Whether the served catalog was converted with a word list (ADR-046): without one every entry carries the reason
     * {@code DICTIONARY_UNAVAILABLE}, so one entry that has signals answers for the version. No entry with signals means no answer, and
     * no answer is not "yes".
     */
    private boolean hasWordList(long catalogVersionId) {
        return wordLists.computeIfAbsent(catalogVersionId, id -> {
            List<String> reasons = sampleReasons(id);
            return !reasons.isEmpty() && reasons.get(0) != null && !"DICTIONARY_UNAVAILABLE".equals(reasons.get(0));
        });
    }

    /** The reason of at most one entry of the catalog version that carries signals; empty when no entry does. */
    List<String> sampleReasons(long catalogVersionId) {
        return jdbc.queryForList("""
                SELECT distinctiveness->>'distinctiveness_reason' FROM trademark_entry
                WHERE catalog_version_id = ? AND distinctiveness IS NOT NULL LIMIT 1""", String.class, catalogVersionId);
    }

    // ---- the client's own data

    private List<Item> peopleItems(UUID clientId) {
        Long watching = jdbc.queryForObject("SELECT count(*) FROM trademark_watchlist_entry WHERE client_id = ? AND enabled",
                Long.class, clientId);
        List<ConsoleAccount> list = accounts.findByClientIdOrderByEmail(clientId);
        long admins = list.stream().filter(account -> account.getRole() == ConsoleRole.ADMIN).count();
        long members = list.size() - admins;
        long blocked = list.stream().filter(account -> !allowed(account.getEmail())).count();

        List<Item> items = new ArrayList<>();
        items.add(item("watchlist", watching != null && watching > 0,
                messages.get("readiness.watchlist.detail", String.valueOf(watching == null ? 0 : watching))));
        String usersDetail = messages.get("readiness.users.detail", String.valueOf(admins), String.valueOf(members));
        if (blocked > 0) {
            usersDetail += " " + messages.get("readiness.users.blocked", String.valueOf(blocked));
        }
        items.add(item("users", admins > 0 && members > 0 && blocked == 0, usersDetail));
        return items;
    }

    /** The sign-in rule of {@code CloudflareAccessLoginService}: the e-mail is listed, or its domain is. */
    private boolean allowed(String email) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        Set<String> emails = console.allowedEmails();
        int at = normalized.lastIndexOf('@');
        return emails.contains(normalized) || at > 0 && console.allowedDomains().contains(normalized.substring(at + 1));
    }

    // ---- what the deployment is set to

    private List<Item> settingsItems() {
        List<Item> items = new ArrayList<>();
        CheckScope.Kind scope = bulk.scopeFor(pilot.enabled());
        items.add(item("scope", scope == CheckScope.Kind.CONTENT, messages.get(scope == CheckScope.Kind.CONTENT
                ? "readiness.scope.detail.content" : "readiness.scope.detail.full")));
        items.add(item("fixes", fixes.enabled(), onOff(fixes.enabled())));
        if (flag("listing-guard.phrase-screening.enabled")) {
            items.add(item("phrases", true, onOff(true)));
        }
        // the pilot template switches these off (ADR-047): "in order" is off
        items.add(offItem("ai.policy", flag("listing-guard.analyzer.enabled")));
        items.add(offItem("ai.llm", flag("listing-guard.llm.enabled")));
        items.add(offItem("ai.judge", flag("listing-guard.llm.judge.enabled")));
        items.add(offItem("ai.rewrite", flag("listing-guard.llm.rewrite.enabled")));
        items.add(offItem("ai.embedding", !"none".equalsIgnoreCase(
                environment.getProperty("listing-guard.embedding.provider", "none").strip())));
        return items;
    }

    private boolean flag(String name) {
        return environment.getProperty(name, Boolean.class, false);
    }

    private Item offItem(String key, boolean on) {
        return item(key, !on, onOff(on));
    }

    private String onOff(boolean on) {
        return messages.get(on ? "readiness.state.on" : "readiness.state.off");
    }

    private Item item(String key, boolean ok, String detail) {
        return new Item(key, ok, messages.get("readiness.item." + key), detail, messages.get("readiness.hint." + key));
    }
}
