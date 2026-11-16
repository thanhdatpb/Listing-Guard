package com.thanhdatpb.listingguard.api.console.reaudit;

import com.thanhdatpb.listingguard.api.console.reaudit.ReauditStore.Run;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistStore;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Decides which trademark risks of a run are new (ADR-035): a risk is new when the catalog mark or the watchlist entry that caused it
 * was not in the catalog version or the watchlist the client's previous run of the same scope used. Everything else is known.
 *
 * <p>The rules, per source of a finding:
 * <ul>
 *   <li><b>Catalog mark.</b> Same catalog version as the previous run: known. The previous run used no catalog: new (the catalog is
 *       new to this scope). Otherwise the mark (in match form) is looked up in the previous version: absent is new, present is
 *       known. A previous version that no longer exists cannot be asked, so every catalog mark is new and the page says why: a risk
 *       is never called known on a guess.</li>
 *   <li><b>Watchlist entry.</b> Same watchlist version as the previous run (the hash of the enabled entries): known. Otherwise an
 *       entry added or changed after the previous run started is new, one not touched since is known. An entry that is not found
 *       (it was deleted after the check read it) is new.</li>
 *   <li><b>Anything else</b> (a rule about place or color, a listing rule): known. It does not come from a mark of a list that grows.</li>
 *   <li><b>No previous run:</b> everything is known: there is nothing to compare with, and the page says so.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ReauditBaselines {

    /** Which marks of a catalog version exist. */
    interface CatalogMarks {

        /**
         * @param normalized marks in match form
         * @return those of them the version holds; empty when the version does not exist (any more)
         */
        Optional<Set<String>> present(String catalogVersion, Collection<String> normalized);
    }

    /** When the watchlist entries of a client were last changed. */
    interface EntryTimes {

        Map<UUID, Instant> updatedAt(UUID clientId);
    }

    /**
     * What the previous run of a scope knew.
     *
     * @param first                 there is no previous run: everything is known, because nothing can be compared
     * @param catalogVersionMissing the previous run's catalog version no longer exists, so its marks cannot be read
     */
    record Baseline(boolean first, boolean catalogVersionMissing, Predicate<String> catalogMarkKnown,
                    Predicate<UUID> watchlistEntryKnown) {

        static Baseline noPreviousRun() {
            return new Baseline(true, false, mark -> true, entry -> true);
        }
    }

    private final CatalogMarks catalogMarks;
    private final EntryTimes entryTimes;

    @Autowired
    public ReauditBaselines(PreviousCatalogMarks catalogMarks, WatchlistStore watchlist) {
        this(catalogMarks, clientId -> watchlist.entries(clientId).stream()
                .collect(Collectors.toMap(WatchlistStore.StoredEntry::id, WatchlistStore.StoredEntry::updatedAt)));
    }

    ReauditBaselines(CatalogMarks catalogMarks, EntryTimes entryTimes) {
        this.catalogMarks = catalogMarks;
        this.entryTimes = entryTimes;
    }

    /**
     * @param previous         the previous run of the scope, or null
     * @param catalogVersion   the catalog version of this run, null when none was served
     * @param watchlistVersion the watchlist version of this run, null when none was used
     * @param catalogMarks     the marks (in match form) of this run's catalog findings
     */
    Baseline build(UUID clientId, Run previous, String catalogVersion, String watchlistVersion, Collection<String> catalogMarks) {
        if (previous == null) {
            return Baseline.noPreviousRun();
        }
        Predicate<String> catalogKnown;
        boolean missing = false;
        if (catalogVersion == null || Objects.equals(previous.catalogVersion(), catalogVersion)) {
            catalogKnown = mark -> true;
        } else if (previous.catalogVersion() == null) {
            // the catalog is new to this scope: nothing in it was seen before
            catalogKnown = mark -> false;
        } else if (catalogMarks.isEmpty()) {
            catalogKnown = mark -> true;
        } else {
            Optional<Set<String>> present = this.catalogMarks.present(previous.catalogVersion(), catalogMarks);
            missing = present.isEmpty();
            Set<String> known = present.orElse(Set.of());
            catalogKnown = known::contains;
        }
        Predicate<UUID> watchlistKnown;
        if (watchlistVersion == null || Objects.equals(previous.watchlistVersion(), watchlistVersion)) {
            watchlistKnown = entry -> true;
        } else {
            Map<UUID, Instant> updated = entryTimes.updatedAt(clientId);
            Instant since = previous.startedAt();
            watchlistKnown = entry -> {
                Instant when = updated.get(entry);
                return when != null && !when.isAfter(since);
            };
        }
        return new Baseline(false, missing, catalogKnown, watchlistKnown);
    }
}
