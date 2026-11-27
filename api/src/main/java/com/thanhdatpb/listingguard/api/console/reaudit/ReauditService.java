package com.thanhdatpb.listingguard.api.console.reaudit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.PilotMetrics;
import com.thanhdatpb.listingguard.api.console.editor.EditorCost;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import com.thanhdatpb.listingguard.api.console.reaudit.ReauditBaselines.Baseline;
import com.thanhdatpb.listingguard.api.console.reaudit.ReauditResult.Risk;
import com.thanhdatpb.listingguard.api.console.reaudit.ReauditResult.Skipped;
import com.thanhdatpb.listingguard.api.console.reaudit.ReauditStore.Row;
import com.thanhdatpb.listingguard.api.console.reaudit.ReauditStore.Run;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.FindingSource;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The periodic re-audit of active listings (ADR-035): checks the rows of a file of the listings that are on sale with the listing rules
 * and the trademark check (the catalog and the client's watchlist), splits what it finds into new and known risks, compares the
 * rows with the previous run of the same scope, and stores the trace of the run (times, versions, hashes, counts; never text).
 *
 * <p>It calls the services the API, {@code batch-check} and the bulk check call, with the client of the signed-in account, so the cache
 * identity, the plan packs, the quota and the retention are theirs. A listing is checked again only if its result is not stored: a
 * listing that was not edited costs nothing again unless the catalog or the watchlist changed (their versions are part of the trademark
 * check's identity).
 *
 * <p>A risk is what needs fixing: a listing-rules ERROR or WARNING, or a trademark finding of level HIGH or MEDIUM. Information and LOW
 * findings are left to the editor and the bulk check.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ReauditService {

    private static final Logger log = LoggerFactory.getLogger(ReauditService.class);
    private static final int MAX_SKU_CHARS = 200;

    /**
     * One row of the file. {@code problem} is null for a row to check, and the reason for a row whose data could not be read.
     *
     * @param line the line of the file where the row starts (the header is line 1)
     */
    public record Input(int line, String sku, ObjectNode listing, String problem) {

        public static Input of(int line, String sku, ObjectNode listing) {
            return new Input(line, sku, listing, null);
        }

        public static Input unreadable(int line, String sku, String problem) {
            return new Input(line, sku, null, problem);
        }
    }

    /** What the home page says when the catalog changed since a scope was last audited. */
    public record Notice(String catalogVersion, List<ReauditStore.Stale> stale, int more) {
    }

    private static final int NOTICE_ITEMS = 5;

    /** What was found for one listing, before it is called new or known. */
    private record Found(String pack, String ruleId, String source, String field, String mark, String normalizedMark,
                         UUID watchlistEntry, String level, String message, String suggestion, boolean calibrated) {
    }

    /** One row after its checks. */
    private record Outcome(Input input, String sku, String hash, String status, String reason, List<Found> found, String trademarkRisk) {
    }

    /** What happens to the run as a whole while the rows are checked. */
    private static final class State {
        String quotaNote;
        String catalogVersion;
        Long watchlistRevision;
        String watchlistVersion;
        int trademarkSkipped;
        int trademarkIncomplete;
    }

    private final CheckService checks;
    private final TrademarkCheckService trademarks;
    private final TrademarkCatalogRuntime catalog;
    private final TextNormalizer normalizer;
    private final TrademarkRulesets rulesets;
    private final PlanService plans;
    private final ReauditStore store;
    private final ReauditBaselines baselines;
    private final ConsoleMessages messages;
    private final Clock clock;
    private final PilotMetrics pilotMetrics;

    public ReauditService(CheckService checks, TrademarkCheckService trademarks, TrademarkCatalogRuntime catalog,
                          TrademarkRulesets rulesets, PlanService plans, ReauditStore store, ReauditBaselines baselines,
                          ConsoleMessages messages, Clock clock, PilotMetrics pilotMetrics) {
        this.checks = checks;
        this.trademarks = trademarks;
        this.catalog = catalog;
        this.rulesets = rulesets;
        this.normalizer = new TextNormalizer(rulesets.ruleset().normalization());
        this.plans = plans;
        this.store = store;
        this.baselines = baselines;
        this.messages = messages;
        this.clock = clock;
        this.pilotMetrics = pilotMetrics;
    }

    /**
     * @param accountId    the console account that runs it, kept with the run
     * @param adapterId    the input adapter that read the file
     * @param account      the label of the seller account, already cleaned; empty when none
     * @throws PlanNotAllowedException if the plan has no {@code listing-rules} pack (only where plans are on, ADR-013)
     * @throws QuotaExceededException  if no unit of the month's quota is left (only where plans are on); nothing was checked
     */
    public ReauditResult run(UUID clientId, UUID accountId, EditorMarketplace marketplace, String adapterId, String account,
                             List<Input> inputs) {
        PlanService.Admission admission = plans.requirePack(clientId, Pack.LISTING_RULES);
        plans.requireQuota(admission);
        String trademarkNote = trademarkNote(clientId);
        Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Run previous = store.latest(clientId, marketplace.code(), account).orElse(null);

        State state = new State();
        List<Outcome> outcomes = new ArrayList<>();
        for (Input input : inputs) {
            outcomes.add(check(clientId, marketplace, input, trademarkNote != null, state));
        }

        Set<String> catalogMarks = new LinkedHashSet<>();
        for (Outcome outcome : outcomes) {
            for (Found found : outcome.found()) {
                if (found.normalizedMark() != null) {
                    catalogMarks.add(found.normalizedMark());
                }
            }
        }
        Baseline baseline = baselines.build(clientId, previous, state.catalogVersion, state.watchlistVersion, catalogMarks);

        List<Risk> newRisks = new ArrayList<>();
        List<Risk> knownRisks = new ArrayList<>();
        List<Risk> weakMatches = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        int checked = 0;
        for (int i = 0; i < outcomes.size(); i++) {
            Outcome outcome = outcomes.get(i);
            if (ReauditStore.CHECKED.equals(outcome.status())) {
                checked++;
            } else {
                skipped.add(new Skipped(outcome.input().line(), outcome.sku(), outcome.reason()));
            }
            int actionable = 0;
            for (Found found : outcome.found()) {
                boolean isNew = !found.calibrated() && isNew(baseline, found);
                Risk risk = new Risk(outcome.input().line(), outcome.sku(), found.pack(), found.ruleId(), found.source(),
                        found.field(), found.mark(), found.level(), messages.get("reaudit.level." + found.level()),
                        found.message(), found.suggestion(), isNew);
                if ("low".equals(found.level())) {
                    weakMatches.add(risk);
                } else {
                    (isNew ? newRisks : knownRisks).add(risk);
                    actionable++;
                }
            }
            rows.add(new Row(i + 1, outcome.sku(), ordinal(outcome, outcomes, i), outcome.hash(), outcome.status(),
                    actionable));
        }

        UUID runId = UUID.randomUUID();
        Run run = new Run(runId, clientId, accountId, marketplace.code(), account, adapterId, startedAt, state.catalogVersion,
                state.watchlistRevision, state.watchlistVersion, outcomes.size(), checked, newRisks.size() + knownRisks.size(),
                newRisks.size(), previous == null ? null : previous.id());
        ReauditComparison.Changes changes = previous == null ? null : ReauditComparison.compare(store.rows(previous.id()), rows);
        store.save(run, rows);
        pilotMetrics.record(clientId, marketplace.code(), outcomes.stream()
                .filter(outcome -> ReauditStore.CHECKED.equals(outcome.status()))
                .map(outcome -> new PilotMetrics.Observation(outcome.trademarkRisk(), false)).toList());

        List<String> notes = notes(trademarkNote, state, baseline);
        List<Risk> all = new ArrayList<>(newRisks);
        all.addAll(knownRisks);
        String csv = ReauditExport.csv(messages, account, all);
        return new ReauditResult(runId, marketplace.code(), account, adapterId, startedAt, state.catalogVersion,
                state.watchlistRevision, previous == null ? null : previous.startedAt(),
                previous == null ? null : previous.catalogVersion(), baseline.catalogVersionMissing(), outcomes.size(), checked,
                List.copyOf(newRisks), List.copyOf(knownRisks), List.copyOf(weakMatches), changes, List.copyOf(skipped), notes,
                rulesets.ruleset().disclaimer(), ReauditExport.href(csv));
    }

    /** The reminder for the home page: the scopes of the client last audited with another catalog than the one served now. */
    public Optional<Notice> notice(UUID clientId) {
        TrademarkCatalogRuntime.Status status = catalog.demoStatus(clientId).orElseGet(catalog::status);
        if (status.state() != TrademarkCatalogRuntime.State.READY) {
            return Optional.empty();
        }
        List<ReauditStore.Stale> stale = store.stale(clientId, status.detail());
        if (stale.isEmpty()) {
            return Optional.empty();
        }
        int shown = Math.min(stale.size(), NOTICE_ITEMS);
        return Optional.of(new Notice(status.detail(), List.copyOf(stale.subList(0, shown)), stale.size() - shown));
    }

    // ---- the rows

    private Outcome check(UUID clientId, EditorMarketplace marketplace, Input input, boolean trademarkOff, State state) {
        String sku = cleanSku(input.sku());
        if (input.problem() != null) {
            return new Outcome(input, sku, null, ReauditStore.UNREADABLE, messages.get("reaudit.reason.unreadable", input.problem()),
                    List.of(), null);
        }
        String hash = ReauditHasher.hash(input.listing());
        List<Found> found = new ArrayList<>();
        try {
            CheckResult result = checks.checkDetailed(clientId, new CheckRequest(marketplace.code(), input.listing()));
            for (CheckResult.FindingView finding : result.findings()) {
                String level = switch (finding.severity()) {
                    case ERROR -> "error";
                    case WARNING -> "warning";
                    default -> null;
                };
                if (level != null) {
                    found.add(new Found(Pack.LISTING_RULES.id(), finding.ruleId(), "RULE", finding.field(), null, null, null, level,
                            finding.message(), finding.suggestion(), false));
                }
            }
        } catch (QuotaExceededException e) {
            state.quotaNote = state.quotaNote == null ? quota(e) : state.quotaNote;
            return new Outcome(input, sku, hash, ReauditStore.NOT_CHECKED, quota(e), List.of(), null);
        } catch (PlanNotAllowedException e) {
            return new Outcome(input, sku, hash, ReauditStore.NOT_CHECKED, plan(e), List.of(), null);
        } catch (RuntimeException e) {
            // the listing is client data: only the kind of failure and the line are logged
            log.warn("Re-audit: the rule check of line {} failed ({})", input.line(), e.getClass().getSimpleName());
            return new Outcome(input, sku, hash, ReauditStore.NOT_CHECKED, messages.get("bulk.reason.failed"), List.of(), null);
        }
        String risk = trademarkOff ? null : trademark(clientId, marketplace, input, state, found);
        return new Outcome(input, sku, hash, ReauditStore.CHECKED, null, List.copyOf(found), risk);
    }

    private String trademark(UUID clientId, EditorMarketplace marketplace, Input input, State state, List<Found> found) {
        try {
            TrademarkCheckResult result = trademarks.check(clientId, new CheckRequest(marketplace.code(), input.listing()));
            if (result.catalogStatus() == TrademarkCheckResult.CatalogStatus.SERVED && state.catalogVersion == null) {
                state.catalogVersion = result.catalogVersion();
            }
            if (result.watchlist() != null && state.watchlistVersion == null) {
                state.watchlistRevision = result.watchlist().revision();
                state.watchlistVersion = result.watchlist().version();
            }
            if (result.completeness() != Completeness.COMPLETE) {
                state.trademarkIncomplete++;
            }
            for (TrademarkCheckResult.FindingView finding : result.findings()) {
                if (finding.risk() != RiskLevel.HIGH && finding.risk() != RiskLevel.MEDIUM && !finding.weakCalibrated()) {
                    continue;
                }
                String level = finding.risk().name().toLowerCase(Locale.ROOT);
                String normalized = finding.source() == FindingSource.CATALOG && finding.mark() != null
                        ? normalizer.normalizeMark(finding.mark()) : null;
                UUID entry = finding.watchlistEntry() == null ? null : finding.watchlistEntry().entryId();
                found.add(new Found(Pack.TRADEMARK_RISK.id(), finding.ruleId(), finding.source().name(), finding.field(),
                        finding.mark(), normalized, entry, level, messages.trademarkOwnerCount(finding.message(), finding.ownerCountSameMark()),
                        finding.suggestion(), finding.catalogCalibrated()));
            }
            return result.completeness() == Completeness.COMPLETE ? result.riskLevel().name() : null;
        } catch (TrademarkUnavailableException | PlanNotAllowedException e) {
            state.trademarkSkipped++;
        } catch (QuotaExceededException e) {
            state.quotaNote = state.quotaNote == null ? quota(e) : state.quotaNote;
            state.trademarkSkipped++;
        } catch (RuntimeException e) {
            log.warn("Re-audit: the trademark check of line {} failed ({})", input.line(), e.getClass().getSimpleName());
            state.trademarkSkipped++;
        }
        return null;
    }

    private static boolean isNew(Baseline baseline, Found found) {
        if (found.normalizedMark() != null) {
            return !baseline.catalogMarkKnown().test(found.normalizedMark());
        }
        if (found.watchlistEntry() != null) {
            return !baseline.watchlistEntryKnown().test(found.watchlistEntry());
        }
        return false;
    }

    /** 1 for the first row with this SKU in the file, 2 for the second; 0 for a row with no SKU. */
    private static int ordinal(Outcome outcome, List<Outcome> outcomes, int index) {
        if (outcome.sku() == null) {
            return 0;
        }
        int ordinal = 1;
        for (int i = 0; i < index; i++) {
            if (outcome.sku().equals(outcomes.get(i).sku())) {
                ordinal++;
            }
        }
        return ordinal;
    }

    private static String cleanSku(String sku) {
        if (sku == null || sku.isBlank()) {
            return null;
        }
        String clean = sku.strip();
        return clean.codePointCount(0, clean.length()) <= MAX_SKU_CHARS ? clean
                : clean.substring(0, clean.offsetByCodePoints(0, MAX_SKU_CHARS));
    }

    // ---- what is said about the run

    private List<String> notes(String trademarkNote, State state, Baseline baseline) {
        List<String> notes = new ArrayList<>();
        if (trademarkNote != null) {
            notes.add(messages.get("bulk.note.trademark.all", trademarkNote));
        } else if (state.trademarkSkipped > 0) {
            notes.add(messages.get("reaudit.note.trademark.skipped", state.trademarkSkipped));
        }
        if (state.trademarkIncomplete > 0) {
            notes.add(messages.get("reaudit.note.trademark.incomplete", state.trademarkIncomplete));
        }
        if (state.quotaNote != null) {
            notes.add(messages.get("bulk.note.quota", state.quotaNote));
        }
        if (baseline.catalogVersionMissing()) {
            notes.add(messages.get("reaudit.note.catalog.missing"));
        }
        return notes;
    }

    /** Why the trademark check will not run for any row, or null when it will. */
    private String trademarkNote(UUID clientId) {
        if (!trademarks.available(clientId)) {
            return messages.get("bulk.reason.trademark.notloaded");
        }
        try {
            plans.requirePack(clientId, Pack.TRADEMARK_RISK);
            return null;
        } catch (PlanNotAllowedException e) {
            return plan(e);
        }
    }

    String quota(QuotaExceededException e) {
        return messages.get("bulk.reason.quota", e.used(), e.quota(), EditorCost.resetDate(e.resetAt()));
    }

    private String plan(PlanNotAllowedException e) {
        return messages.get("bulk.reason.plan", e.plan(), packName(e.pack()));
    }

    String packName(Pack pack) {
        return switch (pack) {
            case LISTING_RULES -> messages.get("bulk.pack.listingrules");
            case TRADEMARK_RISK -> messages.get("bulk.pack.trademarkrisk");
            default -> messages.get("bulk.pack.other");
        };
    }
}
