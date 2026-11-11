package com.thanhdatpb.listingguard.api.image;

import com.thanhdatpb.listingguard.api.trademark.TrademarkRetentionCleanup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes image checks past their retention period, with their items and findings (ADR-008 "Expiry and cleanup", ADR-010
 * "Result storage and endpoint scope").
 *
 * <p>Correctness does not depend on this job: GET and the cache lookup already treat an expired row as absent, and the request
 * that replaces an expired result deletes it itself. The job only keeps expired client-derived evidence from piling up.
 *
 * <p><b>Advisory lock.</b> Every API instance may run the job, but only one may delete at a time. Each batch is one transaction
 * that first calls {@code pg_try_advisory_xact_lock(1279746628, 1380271182)}, the key pair that ADR-010 reserves for Listing
 * Guard retention cleanup: this job and the trademark, variation and personalization cleanups share it, so one batch of any of
 * them runs at a time. If another holds the lock the run is skipped, not waited for, and tried again at the next interval; the
 * lock is released when the transaction ends. The key constants are those of {@link TrademarkRetentionCleanup}, not a copy, and
 * no other job or query may take the pair.
 *
 * <p><b>Batches.</b> One transaction deletes at most {@code cleanup-batch-size} check rows, oldest expiry first, so a backlog
 * never holds a long transaction or a large lock set. Rows locked by a request that is replacing them at the same moment are
 * skipped ({@code FOR UPDATE SKIP LOCKED}); the next run takes them. Every delete is by the check row, which owns its items and
 * findings ({@code ON DELETE CASCADE}), and no client can reach this job.
 */
@Component
public class ImageRetentionCleanup {

    private static final Logger log = LoggerFactory.getLogger(ImageRetentionCleanup.class);

    /**
     * @param skipped       another instance held the lock when a batch was about to start; nothing more was done this run
     * @param deletedChecks check rows deleted by this run (their items and findings went with them)
     */
    public record Outcome(boolean skipped, int deletedChecks) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ImageRetentionProperties properties;

    public ImageRetentionCleanup(JdbcTemplate jdbc, TransactionTemplate transactions, ImageRetentionProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
    }

    /** Deletes expired checks batch by batch until none is left or another instance holds the lock. */
    public Outcome runOnce() {
        int batchSize = properties.cleanupBatchSize();
        int total = 0;
        while (true) {
            Integer deleted = runBatch();
            if (deleted == null) {
                log.debug("Image retention cleanup skipped: the cleanup lock is held elsewhere");
                return new Outcome(true, total);
            }
            total += deleted;
            if (deleted < batchSize) {
                if (total > 0) {
                    log.info("Image retention cleanup deleted {} expired image check(s)", total);
                }
                return new Outcome(false, total);
            }
        }
    }

    /** One batch in its own transaction: the rows deleted, or null when the advisory lock is held elsewhere. */
    Integer runBatch() {
        int batchSize = properties.cleanupBatchSize();
        return transactions.execute(status -> deleteBatch(batchSize));
    }

    private Integer deleteBatch(int batchSize) {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class,
                TrademarkRetentionCleanup.LOCK_KEY_1, TrademarkRetentionCleanup.LOCK_KEY_2);
        if (!Boolean.TRUE.equals(locked)) {
            return null;
        }
        return jdbc.update("""
                DELETE FROM image_check WHERE id IN (
                    SELECT id FROM image_check WHERE expires_at <= now()
                    ORDER BY expires_at, id LIMIT ? FOR UPDATE SKIP LOCKED)""", batchSize);
    }
}
