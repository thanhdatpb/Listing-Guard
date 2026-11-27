package com.thanhdatpb.listingguard.api.workspace.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.check.CheckScope;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.check.ScopedCheckResult;
import com.thanhdatpb.listingguard.api.console.reaudit.ReauditBaselines;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import com.thanhdatpb.listingguard.api.workspace.EtsyFields;
import com.thanhdatpb.listingguard.api.workspace.StandardTargets;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditContexts.Context;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.NewFinding;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredAudit;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredFinding;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Level;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Source;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.State;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.TrademarkReason;
import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.RuleRun;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Audits one listing of the workspace and stores the result (ADR-061). It calls the services Kiểm hàng loạt calls, with the listing's own
 * client: {@link CheckService#checkDetailed(UUID, CheckRequest, CheckScope)} under the content scope of the listing's template (ADR-052) and
 * {@link TrademarkCheckService#check}. Quota, plan packs, cache identity and retention are theirs. A rule's position is read from
 * {@link CheckService#evaluate}, which stores and counts nothing.
 *
 * <p>A listing whose input hash and analysis version are those of its stored audit is left alone. A listing whose trademark part did not run
 * is never clean ({@link State#INCOMPLETE}). Nothing here logs a listing's text: only ids, counts and the kind of a failure.
 */
@Component
@AuditEnabled
public class ListingAuditor {

    private static final Logger log = LoggerFactory.getLogger(ListingAuditor.class);
    private static final String RULES_PACK = "listing-rules";
    private static final String TRADEMARK_PACK = "trademark-risk";

    public enum Outcome {
        /** the stored result already says what a new audit would */
        SKIPPED,
        AUDITED,
        /** the listing changed while it was audited; nothing was written */
        STALE,
        FAILED,
        /** the month's quota ran out: the run stops */
        QUOTA,
        /** the plan has no listing-rules pack: the run stops */
        PLAN
    }

    public record Result(Outcome outcome, StoredAudit audit) {
    }

    /** What one run knows about an account's listings before it starts on them; made once per account and template. */
    public static final class Env {
        final UUID clientId;
        final UUID accountId;
        final Marketplace marketplace;
        final Set<String> scopeFields;
        final Context context;
        final String analysisVersion;
        private final Supplier<Map<UUID, Instant>> entryTimes;

        Env(UUID clientId, UUID accountId, Marketplace marketplace, Set<String> scopeFields, Context context, Supplier<Map<UUID, Instant>> entryTimes) {
            this.clientId = clientId;
            this.accountId = accountId;
            this.marketplace = marketplace;
            this.scopeFields = Set.copyOf(scopeFields);
            this.context = context;
            this.analysisVersion = context.analysisVersion(scopeFields);
            this.entryTimes = entryTimes;
        }

        public Context context() {
            return context;
        }
    }

    private final CheckService checks;
    private final TrademarkCheckService trademarks;
    private final AuditStore store;
    private final ReauditBaselines baselines;
    private final StandardTargets targets;
    private final EtsyFields etsyFields;
    private final AuditProperties properties;
    private final TextNormalizer normalizer;
    private final Clock clock;

    public ListingAuditor(CheckService checks, TrademarkCheckService trademarks, AuditStore store, ReauditBaselines baselines,
                          StandardTargets targets, EtsyFields etsyFields, AuditProperties properties, TrademarkRulesets rulesets, Clock clock) {
        this.checks = checks;
        this.trademarks = trademarks;
        this.store = store;
        this.baselines = baselines;
        this.targets = targets;
        this.etsyFields = etsyFields;
        this.properties = properties;
        this.normalizer = new TextNormalizer(rulesets.ruleset().normalization());
        this.clock = clock;
    }

    /**
     * The listing fields a template has (ADR-052): the standard fields its confirmed mapping gives a column, or the fixed list of the Etsy
     * template. The SKU, the parent SKU, the variation theme and the attributes are not listing fields.
     */
    public Set<String> scopeFields(Marketplace marketplace, Template template) {
        Set<String> fields = new LinkedHashSet<>();
        if (template.kind() == TemplateKind.ETSY_FIELDS) {
            etsyFields.fields().stream().filter(f -> !f.equals("sku") && !f.equals("parent_sku")).forEach(fields::add);
            return fields;
        }
        for (String key : template.mapping().targets().values()) {
            targets.find(marketplace, key).ifPresent(target -> {
                switch (target.kind()) {
                    case TEXT, LIST, TEXT_OR_LIST, PRICE, INTEGER -> fields.add(target.key());
                    default -> {
                    }
                }
            });
        }
        return fields;
    }

    public Env env(UUID clientId, UUID accountId, Marketplace marketplace, Template template, Context context,
                   Supplier<Map<UUID, Instant>> entryTimes) {
        return new Env(clientId, accountId, marketplace, scopeFields(marketplace, template), context, entryTimes);
    }

    /** Audits {@code listing} (a live listing of {@code env}'s client and account) and stores the result, unless it is already up to date. */
    public Result audit(Env env, Listing listing, UUID runId) {
        if (!listing.clientId().equals(env.clientId) || !listing.accountId().equals(env.accountId)) {
            throw new IllegalArgumentException("The listing does not belong to the client and the account of this audit.");
        }
        JsonNode fields = listing.fields();
        String inputHash = AuditHasher.input(fields);
        Optional<StoredAudit> prior = store.audit(env.clientId, listing.id());
        // an audit stored before V60 has no trademark reason: it is made again once, so the gate knows why its trademark part did not run
        if (prior.isPresent() && prior.get().inputHash().equals(inputHash) && prior.get().analysisVersion().equals(env.analysisVersion)
                && prior.get().trademarkReason() != null) {
            return new Result(Outcome.SKIPPED, prior.get());
        }
        CheckRequest request = new CheckRequest(env.marketplace.name(), fields);
        Map<String, String> texts = FieldTexts.of(fields);
        List<NewFinding> found = new ArrayList<>();
        String rulesAnalysis;
        try {
            ScopedCheckResult scoped = checks.checkDetailed(env.clientId, request, CheckScope.content(env.scopeFields));
            CheckResult result = scoped.result();
            rulesAnalysis = result.analysisVersion();
            ruleFindings(env, request, result, texts, found);
        } catch (QuotaExceededException e) {
            return new Result(Outcome.QUOTA, null);
        } catch (PlanNotAllowedException e) {
            return new Result(Outcome.PLAN, null);
        } catch (RuntimeException e) {
            // the listing is client data: only its id and the kind of failure are logged
            log.warn("Audit of listing {}: the rule check failed ({})", listing.id(), e.getClass().getSimpleName());
            return new Result(Outcome.FAILED, null);
        }

        boolean trademarkRan = false;
        TrademarkReason trademarkReason = unavailableReason(env);
        String trademarkAnalysis = null;
        String catalogVersion = env.context.catalogVersion();
        String watchlistVersion = env.context.watchlistVersion();
        if (trademarks.available(env.clientId)) {
            try {
                TrademarkCheckResult result = trademarks.check(env.clientId, request);
                trademarkAnalysis = result.analysisVersion();
                trademarkRan = result.completeness() == Completeness.COMPLETE && result.catalogStatus() == TrademarkCheckResult.CatalogStatus.SERVED;
                trademarkReason = trademarkRan ? TrademarkReason.RAN
                        : result.catalogStatus() == TrademarkCheckResult.CatalogStatus.SERVED ? TrademarkReason.INCOMPLETE
                        : env.context.catalogAvailability() == Context.CatalogAvailability.UNAVAILABLE ? TrademarkReason.UNAVAILABLE
                        : TrademarkReason.WATCHLIST_ONLY;
                if (result.catalogStatus() == TrademarkCheckResult.CatalogStatus.SERVED) {
                    catalogVersion = result.catalogVersion();
                }
                if (result.watchlist() != null) {
                    watchlistVersion = result.watchlist().version();
                }
                trademarkFindings(result, texts, found);
            } catch (QuotaExceededException e) {
                return new Result(Outcome.QUOTA, null);
            } catch (PlanNotAllowedException e) {
                // the plan has no trademark pack: the client has no trademark set-up, so the part did not run and the gate lets it pass (ADR-063)
                trademarkReason = TrademarkReason.NO_PLAN;
                log.debug("Audit of listing {}: no trademark part ({})", listing.id(), e.getClass().getSimpleName());
            } catch (TrademarkUnavailableException e) {
                // this part did not run; the listing is not called clean for it
                trademarkReason = TrademarkReason.UNAVAILABLE;
                log.debug("Audit of listing {}: no trademark part ({})", listing.id(), e.getClass().getSimpleName());
            } catch (RuntimeException e) {
                log.warn("Audit of listing {}: the trademark check failed ({})", listing.id(), e.getClass().getSimpleName());
                return new Result(Outcome.FAILED, null);
            }
        }

        Instant now = clock.instant();
        List<NewFinding> findings = markNew(env, listing, prior.orElse(null), inputHash, found, catalogVersion, watchlistVersion);
        int errors = (int) findings.stream().filter(f -> f.level().weight() == 3).count();
        int warnings = (int) findings.stream().filter(f -> f.level().weight() == 2).count();
        int fresh = (int) findings.stream().filter(NewFinding::isNew).count();
        State state = fresh > 0 ? State.NEW_RISK : errors > 0 ? State.ERROR : warnings > 0 ? State.WARNING : !trademarkRan ? State.INCOMPLETE : State.CLEAN;
        boolean nothingNewlyNew = fresh == 0 || stillTheSameNewRisks(prior.orElse(null), inputHash, findings);
        StoredAudit audit = new StoredAudit(listing.id(), env.clientId, env.accountId, runId, state, inputHash, env.analysisVersion, rulesAnalysis,
                trademarkAnalysis, trademarkRan, listing.version(), catalogVersion, watchlistVersion, errors, warnings, fresh, now,
                nothingNewlyNew ? prior.map(StoredAudit::alertedAt).orElse(null) : null,
                fresh == 0 || nothingNewlyNew ? prior.map(StoredAudit::ackAt).orElse(null) : null, trademarkReason);
        List<NewFinding> kept = findings.stream().sorted(Comparator.comparingInt((NewFinding f) -> -f.level().weight())).limit(properties.maxFindings()).toList();
        boolean saved = store.save(audit, kept);
        return new Result(saved ? Outcome.AUDITED : Outcome.STALE, saved ? audit : null);
    }

    /**
     * Why the trademark part did not run when no check is made at all: {@code trademarks.available} is false, which means no catalog is served
     * and the client has no watchlist entry. A catalog that is being loaded or was refused may pass, so it holds the listing back; one that
     * nobody set up does not (ADR-063).
     */
    private static TrademarkReason unavailableReason(Env env) {
        return env.context.catalogAvailability() == Context.CatalogAvailability.UNAVAILABLE ? TrademarkReason.UNAVAILABLE
                : TrademarkReason.NOT_CONFIGURED;
    }

    // ------------------------------------------------------------------ the rules

    private void ruleFindings(Env env, CheckRequest request, CheckResult result, Map<String, String> texts, List<NewFinding> found) {
        List<CheckResult.FindingView> problems = result.findings().stream()
                .filter(f -> f.severity() == Severity.ERROR || f.severity() == Severity.WARNING).toList();
        List<Finding> positioned = new ArrayList<>();
        if (problems.stream().anyMatch(f -> f.value() != null && f.field() != null)) {
            // the check API does not carry a position; the rule engine run that stores nothing does
            RuleRun run = checks.evaluate(env.clientId, request);
            positioned.addAll(run.findings());
        }
        Set<Integer> used = new HashSet<>();
        for (CheckResult.FindingView problem : problems) {
            Integer start = null;
            Integer end = null;
            if (problem.value() != null && problem.field() != null) {
                for (int i = 0; i < positioned.size(); i++) {
                    Finding candidate = positioned.get(i);
                    if (!used.contains(i) && candidate.matchStart() != null && candidate.ruleId().equals(problem.ruleId())
                            && Objects.equals(candidate.field(), problem.field()) && Objects.equals(candidate.value(), problem.value())) {
                        used.add(i);
                        Optional<TextOffsets.Span> span = TextOffsets.fromRule(texts.get(problem.field()), candidate.matchStart(), candidate.matchEnd());
                        if (span.isPresent()) {
                            start = span.get().start();
                            end = span.get().end();
                        }
                        break;
                    }
                }
            }
            found.add(new NewFinding(RULES_PACK, problem.ruleId(), problem.severity() == Severity.ERROR ? Level.ERROR : Level.WARNING, Source.RULE,
                    problem.field(), start, end, null, null, null, null, null, problem.message(), problem.suggestion(), false));
        }
    }

    // ------------------------------------------------------------------ the trademark part

    private void trademarkFindings(TrademarkCheckResult result, Map<String, String> texts, List<NewFinding> found) {
        for (TrademarkCheckResult.FindingView finding : result.findings()) {
            if (finding.risk() != RiskLevel.HIGH && finding.risk() != RiskLevel.MEDIUM) {
                continue;
            }
            Integer start = null;
            Integer end = null;
            String text = finding.field() == null ? null : texts.get(finding.field());
            if (text != null) {
                Optional<TextOffsets.Span> span = TextOffsets.fromCanonical(text, finding.start(), finding.end());
                if (span.isPresent()) {
                    start = span.get().start();
                    end = span.get().end();
                }
            }
            Source source = Source.valueOf(finding.source().name());
            String mark = finding.watchlistEntry() != null ? finding.watchlistEntry().mark() : finding.mark();
            String serial = finding.watchlistEntry() != null ? finding.watchlistEntry().serialNumber() : serial(finding.sourceIds());
            String url = finding.sourceLinks().isEmpty() ? null : finding.sourceLinks().get(0).url();
            UUID entry = finding.watchlistEntry() == null ? null : finding.watchlistEntry().entryId();
            found.add(new NewFinding(TRADEMARK_PACK, finding.ruleId(), finding.risk() == RiskLevel.HIGH ? Level.HIGH : Level.MEDIUM, source,
                    finding.field(), start, end, mark, serial, source == Source.CATALOG ? result.catalogVersion() : null, entry, url,
                    finding.message(), finding.suggestion(), false));
        }
    }

    private static String serial(List<String> sourceIds) {
        for (String id : sourceIds) {
            int colon = id.indexOf(':');
            if (colon > 0 && id.startsWith("uspto:") && colon < id.length() - 1) {
                return id.substring(colon + 1);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ what is new (ADR-035)

    /**
     * A risk is new when the catalog mark or the watchlist entry behind it was not in what this listing's previous audit used, by the rules of
     * ADR-035 ({@link ReauditBaselines}). A listing never audited has nothing to compare with: everything is known. A new risk stays new while
     * the listing's text is unchanged and nobody has marked it seen, so the next audit does not clear it before anyone looked.
     */
    private List<NewFinding> markNew(Env env, Listing listing, StoredAudit prior, String inputHash, List<NewFinding> found, String catalogVersion,
                                     String watchlistVersion) {
        if (prior == null) {
            return found;
        }
        Set<String> marks = new LinkedHashSet<>();
        for (NewFinding f : found) {
            if (f.source() == Source.CATALOG && f.mark() != null) {
                marks.add(normalizer.normalizeMark(f.mark()));
            }
        }
        ReauditBaselines.Baseline baseline = baselines.since(env.clientId, prior.auditedAt(), prior.catalogVersion(), prior.watchlistVersion(),
                catalogVersion, watchlistVersion, marks, found.stream().anyMatch(f -> f.watchlistEntry() != null) ? env.entryTimes.get() : Map.of());
        Set<String> priorNew = new HashSet<>();
        if (prior.inputHash().equals(inputHash) && prior.ackAt() == null) {
            for (StoredFinding f : store.findings(env.clientId, listing.id())) {
                if (f.isNew()) {
                    priorNew.add(f.identity());
                }
            }
        }
        List<NewFinding> marked = new ArrayList<>(found.size());
        for (NewFinding f : found) {
            boolean fresh = false;
            if (f.source() == Source.CATALOG && f.mark() != null) {
                fresh = !baseline.catalogMarkKnown().test(normalizer.normalizeMark(f.mark()));
            } else if (f.source() == Source.WATCHLIST && f.watchlistEntry() != null) {
                fresh = !baseline.watchlistEntryKnown().test(f.watchlistEntry());
            }
            marked.add(f.withNew(fresh || priorNew.contains(f.identity())));
        }
        return marked;
    }

    /** True when every new risk of this audit was already new in the previous one, so the person already has the news about it. */
    private boolean stillTheSameNewRisks(StoredAudit prior, String inputHash, List<NewFinding> findings) {
        if (prior == null || prior.state() != State.NEW_RISK || !prior.inputHash().equals(inputHash)) {
            return false;
        }
        Set<String> before = new HashSet<>();
        for (StoredFinding f : store.findings(prior.clientId(), prior.listingId())) {
            if (f.isNew()) {
                before.add(f.identity());
            }
        }
        return findings.stream().filter(NewFinding::isNew).allMatch(f -> before.contains(f.identity()));
    }

    /** The findings of a listing by identity, newest audit; for a test and the page. */
    static Map<String, StoredFinding> byIdentity(List<StoredFinding> findings) {
        Map<String, StoredFinding> map = new LinkedHashMap<>();
        findings.forEach(f -> map.putIfAbsent(f.identity(), f));
        return map;
    }
}
