package com.thanhdatpb.listingguard.api.workspace.audit;

import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Level;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Reason;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.RunStatus;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Source;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.State;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The records of the background audit (ADR-061). */
public final class AuditModels {

    private AuditModels() {
    }

    /**
     * One audit of one seller account: a row of the queue and of the history. {@code listingIds} is null for every live listing of the
     * account. {@code contextKey} and the three versions are what the audit used; they are set when it finishes.
     */
    public record Run(UUID id, UUID clientId, UUID accountId, Reason reason, RunStatus status, List<UUID> listingIds, UUID requestedBy,
                      int total, int done, int skipped, int failed, int newRisks, int newRiskListings, String noteCode, int attempts,
                      String contextKey, String catalogVersion, String watchlistVersion, Long watchlistRevision, String rulesetVersions,
                      Instant createdAt, Instant startedAt, Instant finishedAt) {

        public int processed() {
            return done + skipped + failed;
        }
    }

    /** What a finished or running run adds up to; written by a heartbeat and by the end of the run. */
    public record Progress(int total, int done, int skipped, int failed, int newRisks, int newRiskListings) {

        public static Progress start(int total) {
            return new Progress(total, 0, 0, 0, 0, 0);
        }
    }

    /** One finding of a listing's latest audit. */
    public record StoredFinding(long id, int position, String pack, String ruleId, Level level, Source source, String field, Integer spanStart,
                                Integer spanEnd, String mark, String serial, String catalogVersion, UUID watchlistEntry, String tsdrUrl,
                                String message, String suggestion, boolean isNew) {

        public boolean hasSpan() {
            return spanStart != null && spanEnd != null;
        }

        /** What makes two audits of a listing speak of the same risk: its source, rule, field and mark or watchlist entry. */
        public String identity() {
            return source + "|" + ruleId + "|" + (field == null ? "" : field) + "|" + (watchlistEntry != null ? watchlistEntry.toString()
                    : mark == null ? "" : mark.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** A finding as an audit makes it, before it is stored. */
    public record NewFinding(String pack, String ruleId, Level level, Source source, String field, Integer spanStart, Integer spanEnd,
                             String mark, String serial, String catalogVersion, UUID watchlistEntry, String tsdrUrl, String message,
                             String suggestion, boolean isNew) {

        public NewFinding withNew(boolean flag) {
            return new NewFinding(pack, ruleId, level, source, field, spanStart, spanEnd, mark, serial, catalogVersion, watchlistEntry,
                    tsdrUrl, message, suggestion, flag);
        }

        public String identity() {
            return source + "|" + ruleId + "|" + (field == null ? "" : field) + "|" + (watchlistEntry != null ? watchlistEntry.toString()
                    : mark == null ? "" : mark.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** The latest audit of a listing, as stored. */
    public record StoredAudit(UUID listingId, UUID clientId, UUID accountId, UUID runId, State state, String inputHash, String analysisVersion,
                              String rulesAnalysis, String trademarkAnalysis, boolean trademarkRan, long listingVersion,
                              String catalogVersion, String watchlistVersion, int errorCount, int warningCount, int newCount,
                              Instant auditedAt, Instant alertedAt, Instant ackAt) {
    }

    /** What the list and the detail page show of a listing's audit. */
    public record AuditView(UUID listingId, State state, int errorCount, int warningCount, int newCount, Instant auditedAt,
                            boolean trademarkRan, String catalogVersion) {
    }

    /** The newest run of an account, for the progress and the history. */
    public record AccountRun(UUID accountId, String accountName, String marketplace, Run run) {
    }

    /** The states of a client's listings, counted. */
    public record StateCounts(long clean, long warning, long error, long newRisk, long incomplete, long unchecked) {

        public long total() {
            return clean + warning + error + newRisk + incomplete + unchecked;
        }
    }

    /** One row of a person's alert summary before it becomes text. */
    public record AlertRow(UUID listingId, String sku, UUID accountId, String accountName, UUID ownerId, String field, String mark, Level level,
                           Integer spanStart, Integer spanEnd) {
    }
}
