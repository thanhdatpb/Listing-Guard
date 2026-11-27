package com.thanhdatpb.listingguard.api.workspace.audit;

import com.thanhdatpb.listingguard.api.workspace.audit.AuditContexts.Context;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.Run;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditStore.AccountRef;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Reason;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * The triggers of the audit that are not an event of the workspace (ADR-061 §3): a change of the analysis context (a catalog that is served
 * now, a watchlist that changed, a ruleset that changed) and the periodic audit.
 *
 * <p>The context is compared, not announced. The catalog is activated by a job outside this process and the API learns of it when its runtime
 * reads the activation log again; the watchlist changes in the API; neither tells this class. Comparing the context of each account's last
 * finished run with the context now finds all of them, finds the ones that happened while the API was stopped, and cannot lose one. It costs
 * one watchlist query for each client and marketplace each time it runs.
 */
@Component
@AuditEnabled
public class AuditTriggers {

    private static final Logger log = LoggerFactory.getLogger(AuditTriggers.class);

    private final AuditStore store;
    private final AuditContexts contexts;
    private final AuditProperties properties;
    private final Clock clock;
    private volatile LocalDateTime nextFire;

    public AuditTriggers(AuditStore store, AuditContexts contexts, AuditProperties properties, Clock clock) {
        this.store = store;
        this.contexts = contexts;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Queues a run for every account whose last finished run used another context than the one now, and for every account that has listings
     * and no run at all. An account with a run waiting or working is left alone. Returns how many runs were queued.
     */
    public int checkDrift() {
        int queued = 0;
        Map<String, Context> cache = new HashMap<>();
        for (AccountRef account : store.accountsWithListings()) {
            if (store.hasOpenRun(account.clientId(), account.accountId())) {
                continue;
            }
            Context now = cache.computeIfAbsent(account.clientId() + "|" + account.marketplace(),
                    k -> contexts.of(account.clientId(), account.marketplace()));
            Optional<Run> last = store.lastFinished(account.clientId(), account.accountId());
            Reason reason = last.isEmpty() ? Reason.IMPORT : reasonOf(last.get(), now);
            if (reason != null) {
                store.enqueue(account.clientId(), account.accountId(), reason, null, null);
                queued++;
                log.info("Audit queued for account {} of client {}: {}", account.accountId(), account.clientId(), reason);
            }
        }
        return queued;
    }

    /** Why a finished run's context is not the one now: the catalog first, then the watchlist, then a ruleset; null when it is the same. */
    static Reason reasonOf(Run last, Context now) {
        if (now.key().equals(last.contextKey())) {
            return null;
        }
        if (!Objects.equals(last.catalogVersion(), now.catalogVersion())) {
            return Reason.CATALOG;
        }
        if (!Objects.equals(last.watchlistVersion(), now.watchlistVersion())) {
            return Reason.WATCHLIST;
        }
        if (last.contextKey() == null) {
            // a run that ended without recording its context (it failed at the start) is not a reason to run again by itself
            return null;
        }
        return Reason.RULESET;
    }

    /**
     * The periodic audit: queues a run for every account that has listings when the cron expression has come due since the last call.
     * With no expression nothing happens. Times are UTC. Returns how many runs were queued.
     */
    public int checkSchedule() {
        if (!properties.scheduled()) {
            return 0;
        }
        CronExpression cron = CronExpression.parse(properties.scheduleCron().strip());
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDateTime due = nextFire;
        if (due == null) {
            nextFire = cron.next(now);
            return 0;
        }
        if (now.isBefore(due)) {
            return 0;
        }
        nextFire = cron.next(now);
        int queued = 0;
        for (AccountRef account : store.accountsWithListings()) {
            if (!store.hasOpenRun(account.clientId(), account.accountId())) {
                store.enqueue(account.clientId(), account.accountId(), Reason.SCHEDULE, null, null);
                queued++;
            }
        }
        log.info("Periodic audit queued {} runs", queued);
        return queued;
    }

}
