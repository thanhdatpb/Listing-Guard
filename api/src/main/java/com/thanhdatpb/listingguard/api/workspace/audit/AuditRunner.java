package com.thanhdatpb.listingguard.api.workspace.audit;

import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccounts;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTemplates;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditContexts.Context;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.Progress;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.Run;
import com.thanhdatpb.listingguard.api.console.reaudit.ReauditBaselines;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Works one run of the queue (ADR-061): audits the listings of one seller account one after the other, keeps the lease and the progress,
 * and ends the run with the context it used. Safe to run again after a crash: a listing whose stored audit is current is skipped, and what a
 * run found is counted from the stored results.
 */
@Component
@AuditEnabled
public class AuditRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditRunner.class);
    private static final int HEARTBEAT_EVERY = 20;
    private static final Duration HEARTBEAT_MAX_GAP = Duration.ofSeconds(10);

    private final AuditStore store;
    private final ListingAuditor auditor;
    private final AuditContexts contexts;
    private final WorkspaceAccounts accounts;
    private final WorkspaceTemplates templates;
    private final WorkspaceListings listings;
    private final ReauditBaselines baselines;
    private final AuditProperties properties;
    private final ObjectProvider<AlertService> alerts;

    public AuditRunner(AuditStore store, ListingAuditor auditor, AuditContexts contexts, WorkspaceAccounts accounts, WorkspaceTemplates templates,
                       WorkspaceListings listings, ReauditBaselines baselines, AuditProperties properties, ObjectProvider<AlertService> alerts) {
        this.store = store;
        this.auditor = auditor;
        this.contexts = contexts;
        this.accounts = accounts;
        this.templates = templates;
        this.listings = listings;
        this.baselines = baselines;
        this.properties = properties;
        this.alerts = alerts;
    }

    /** What ended a run early, as the code the page says in words. */
    static final String NOTE_QUOTA = "quota";
    static final String NOTE_PLAN = "plan";
    static final String NOTE_ACCOUNT = "account";

    /** Takes the oldest waiting run and works it; false when nothing was waiting. */
    public boolean runNext() {
        Optional<Run> claimed = store.claim(properties.lease());
        if (claimed.isEmpty()) {
            return false;
        }
        try {
            process(claimed.get());
        } catch (RuntimeException e) {
            // the run stays RUNNING with its lease: it is taken again when the lease runs out, up to the attempt limit
            log.warn("Audit run {} stopped by {}", claimed.get().id(), e.getClass().getSimpleName());
        }
        return true;
    }

    void process(Run run) {
        UUID clientId = run.clientId();
        Optional<SellerAccount> account = accounts.find(clientId, run.accountId());
        if (account.isEmpty() || !account.get().enabled()) {
            store.finish(clientId, run.id(), Progress.start(0), NOTE_ACCOUNT, null, null, null, null, null);
            return;
        }
        Context context = contexts.of(clientId, account.get().marketplace().name());
        List<UUID> ids = store.liveListingIds(clientId, run.accountId(), run.listingIds());
        Progress progress = Progress.start(ids.size());
        int done = 0;
        int skipped = 0;
        int failed = 0;
        String note = null;
        Map<UUID, Template> templateCache = new HashMap<>();
        Map<UUID, ListingAuditor.Env> envs = new HashMap<>();
        Supplier<Map<UUID, Instant>> entryTimes = memoize(() -> baselines.entryTimes(clientId));
        Instant lastBeat = Instant.now();
        int processed = 0;
        for (UUID id : ids) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            ListingAuditor.Result result = null;
            for (int attempt = 0; attempt < 2 && (result == null || result.outcome() == ListingAuditor.Outcome.STALE); attempt++) {
                Optional<Listing> listing = listings.find(clientId, id);
                if (listing.isEmpty()) {
                    result = new ListingAuditor.Result(ListingAuditor.Outcome.SKIPPED, null);
                    break;
                }
                Template template = templateCache.computeIfAbsent(listing.get().templateId(), t -> templates.find(clientId, t).orElse(null));
                if (template == null) {
                    result = new ListingAuditor.Result(ListingAuditor.Outcome.FAILED, null);
                    break;
                }
                ListingAuditor.Env env = envs.computeIfAbsent(template.id(),
                        t -> auditor.env(clientId, run.accountId(), account.get().marketplace(), template, context, entryTimes));
                result = auditor.audit(env, listing.get(), run.id());
            }
            switch (result.outcome()) {
                case AUDITED -> done++;
                case SKIPPED -> skipped++;
                case FAILED, STALE -> failed++;
                case QUOTA -> note = NOTE_QUOTA;
                case PLAN -> note = NOTE_PLAN;
            }
            if (note != null) {
                break;
            }
            processed++;
            progress = new Progress(ids.size(), done, skipped, failed, progress.newRisks(), progress.newRiskListings());
            if (processed % HEARTBEAT_EVERY == 0 || Duration.between(lastBeat, Instant.now()).compareTo(HEARTBEAT_MAX_GAP) > 0) {
                lastBeat = Instant.now();
                if (!store.heartbeat(clientId, run.id(), store.foundBy(clientId, run.id(), progress), properties.lease())) {
                    log.info("Audit run {} is no longer this worker's; stopping", run.id());
                    return;
                }
            }
        }
        Progress end = store.foundBy(clientId, run.id(), new Progress(ids.size(), done, skipped, failed, 0, 0));
        store.finish(clientId, run.id(), end, note, context.key(), context.catalogVersion(), context.watchlistVersion(),
                context.watchlistRevision(), context.rulesetVersions());
        store.prune(clientId, run.accountId(), properties.keepRuns());
        log.info("Audit run {} of account {} finished: {} audited, {} unchanged, {} failed, {} listings with a new risk{}", run.id(),
                run.accountId(), done, skipped, failed, end.newRiskListings(), note == null ? "" : ", stopped: " + note);
        AlertService alert = alerts.getIfAvailable();
        if (alert != null && end.newRiskListings() > 0) {
            try {
                alert.send(clientId, run.id());
            } catch (RuntimeException e) {
                // the audit is done and stored; the e-mail is tried again after the next run, because nothing was marked as told
                log.warn("The alert e-mail of run {} failed ({})", run.id(), e.getClass().getSimpleName());
            }
        }
    }

    private static <T> Supplier<T> memoize(Supplier<T> source) {
        Object[] cell = new Object[1];
        boolean[] ready = {false};
        return () -> {
            synchronized (cell) {
                if (!ready[0]) {
                    cell[0] = source.get();
                    ready[0] = true;
                }
                @SuppressWarnings("unchecked")
                T value = (T) cell[0];
                return value;
            }
        };
    }
}
