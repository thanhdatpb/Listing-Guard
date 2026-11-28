package com.thanhdatpb.listingguard.api.workspace.audit;

import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * What decides an audit's result besides the listing's own text (ADR-061): the version of the ruleset of the marketplace, the version of the
 * trademark ruleset, the catalog being served, and the client's watchlist. When one of them changes, every listing that was audited with the
 * old one must be audited again; when none does, a listing whose text did not change is not. The context is read, never changed, and costs
 * one watchlist query.
 */
@Component
@AuditEnabled
public class AuditContexts {

    /**
     * @param catalogVersion   the catalog the client's checks are served from, null when none is served
     * @param watchlistVersion the hash of the client's enabled watchlist entries, null when it has none
     * @param watchlistRevision the revision of the list (0 when it has none)
     * @param catalogAvailability why the catalog is or is not served (ADR-063); it is not part of {@link #key()}
     * @param cloneLeftover   the version and hash of the pack {@code clone-leftover} (ADR-039) when the comparison of a clone with its original is on, otherwise
     *                        null. It changes {@link #key()} (so a run is queued when the switch is turned on) but not {@link #analysisVersion}: only a clone's own
     *                        analysis version holds it ({@link #cloneAnalysisVersion})
     */
    public record Context(String marketplace, String rulesVersion, String trademarkRuleset, String catalogVersion, String watchlistVersion,
                          long watchlistRevision, CatalogAvailability catalogAvailability, String cloneLeftover) {

        /** A context without the comparison of clones: what every context was before ADR-039. */
        public Context(String marketplace, String rulesVersion, String trademarkRuleset, String catalogVersion, String watchlistVersion,
                       long watchlistRevision, CatalogAvailability catalogAvailability) {
            this(marketplace, rulesVersion, trademarkRuleset, catalogVersion, watchlistVersion, watchlistRevision, catalogAvailability, null);
        }

        /** The catalog the client's checks are served from: ready, not set up (nothing is configured or activated), or not ready now. */
        public enum CatalogAvailability {
            READY, NOT_CONFIGURED, UNAVAILABLE
        }

        /** What a finished run records as the versions it used, in one cell. */
        public String rulesetVersions() {
            return "rules=" + rulesVersion + ";trademark=" + trademarkRuleset;
        }

        /** The key of the whole context: one value to compare with the one a run stored. Without the clone comparison it is what it always was. */
        public String key() {
            String base = baseKey();
            return cloneLeftover == null ? base : AuditHasher.analysis(List.of("ctx2", base, cloneLeftover));
        }

        private String baseKey() {
            return AuditHasher.analysis(List.of("ctx1", marketplace, rulesVersion, trademarkRuleset, String.valueOf(catalogVersion),
                    String.valueOf(watchlistVersion)));
        }

        /** The analysis version of a listing audited under this context and this scope (the fields its template has). It ignores the clone comparison. */
        public String analysisVersion(Set<String> scopeFields) {
            return AuditHasher.analysis(List.of("analysis1", baseKey(), String.join(",", new TreeSet<>(scopeFields))));
        }

        /**
         * The analysis version of a clone while the comparison with its original is on: the version of its listing and scope, the pack, and what of the
         * original decides the result ({@code originalKey}: its template and content hash, or {@code none} when the original is gone). A clone is audited
         * again when the pack, its original or the original's template changes, and a listing that is no clone never carries this.
         */
        public String cloneAnalysisVersion(Set<String> scopeFields, String originalKey) {
            return AuditHasher.analysis(List.of("analysis1+clone", analysisVersion(scopeFields), String.valueOf(cloneLeftover), originalKey));
        }
    }

    private final ActiveRulesets rulesets;
    private final TrademarkRulesets trademarkRulesets;
    private final TrademarkCatalogRuntime catalog;
    private final WatchlistService watchlists;
    private final AuditProperties properties;
    private final CloneLeftoverPack clonePack;

    public AuditContexts(ActiveRulesets rulesets, TrademarkRulesets trademarkRulesets, TrademarkCatalogRuntime catalog,
                         WatchlistService watchlists, AuditProperties properties, CloneLeftoverPack clonePack) {
        this.properties = properties;
        this.clonePack = clonePack;
        this.rulesets = rulesets;
        this.trademarkRulesets = trademarkRulesets;
        this.catalog = catalog;
        this.watchlists = watchlists;
    }

    public Context of(UUID clientId, String marketplace) {
        String code = marketplace.toUpperCase(java.util.Locale.ROOT);
        String rules = rulesets.find(RulesetConfig.DEFAULT_PACK_ID, code).map(active -> active.config().version()).orElse("none");
        TrademarkCatalogRuntime.Status status = catalog.demoStatus(clientId).orElseGet(catalog::status);
        String catalogVersion = status.state() == TrademarkCatalogRuntime.State.READY ? status.detail() : null;
        WatchlistService.Active watchlist = watchlists.active(clientId);
        String watchlistVersion = watchlist.watchlist().isEmpty() ? null : watchlist.watchlist().contentHash();
        Context.CatalogAvailability availability = switch (status.state()) {
            case READY -> Context.CatalogAvailability.READY;
            // REFUSED is a catalog that is set up and cannot be served; "not evaluated" is a catalog not known yet: both may pass
            case REFUSED -> Context.CatalogAvailability.UNAVAILABLE;
            case DISABLED -> TrademarkCatalogRuntime.NOT_EVALUATED.equals(status.detail()) ? Context.CatalogAvailability.UNAVAILABLE
                    : Context.CatalogAvailability.NOT_CONFIGURED;
        };
        return new Context(code, rules, trademarkRulesets.ruleset().version(), catalogVersion, watchlistVersion, watchlist.revision(),
                availability, properties.cloneLeftover() ? clonePack.identity() : null);
    }
}
