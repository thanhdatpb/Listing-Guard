package com.thanhdatpb.listingguard.api.image;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.image.ImageFinding;
import com.thanhdatpb.listingguard.image.ImageItemResult;
import com.thanhdatpb.listingguard.image.ImageItemResult.Completeness;
import com.thanhdatpb.listingguard.image.ImageMetadata;
import com.thanhdatpb.listingguard.image.ImageRole;
import com.thanhdatpb.listingguard.image.ImageSource;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads and writes image check results (ADR-008, ADR-010). Every read is scoped by the owning client, and every read of a
 * retained result excludes a row past {@code expires_at}: expiry is decided here, by the database clock, whatever the cleanup job
 * has or has not done yet.
 *
 * <p>What is stored is the result, never the images: the canonical input hash, one row per image with its SHA-256 and the metadata
 * the decoder read, and the findings with their bounded evidence. {@link #insert} must run inside a transaction (the caller's),
 * because for a complete result it first deletes the expired row it replaces and then inserts, and the two have to stand or fall
 * together. A result is read in one repeatable-read snapshot, so a cleanup that runs meanwhile cannot hand back half of it.
 */
@Component
public class ImageCheckStore {

    /** The partial unique index of the cache identity (V30). */
    static final String CACHE_INDEX = "uq_image_check_cache";

    private static final String UNIQUE_VIOLATION = "23505";

    /** What makes two analyses interchangeable (ADR-010): the cache identity of an image result. */
    public record Identity(UUID clientId, String marketplace, String packId, String inputHash, long rulesetVersionId,
                           String analysisVersion) {
    }

    private static final String HEADER = """
            SELECT c.id, c.marketplace_code, r.version AS ruleset_version, c.analysis_version, c.profile, c.completeness,
                   c.summary, c.failed_rule_ids, c.disclaimer, c.created_at, c.expires_at
            FROM image_check c
            JOIN ruleset_version r ON r.id = c.ruleset_version_id
            """;

    private static final String IDENTITY_FILTER = """
            c.client_id = ? AND c.marketplace_code = ? AND c.pack_id = ? AND c.input_hash = ?
              AND c.ruleset_version_id = ? AND c.analysis_version = ?""";

    private final JdbcTemplate jdbc;
    private final ImageRetentionProperties retention;
    private final TransactionTemplate snapshot;

    public ImageCheckStore(JdbcTemplate jdbc, ImageRetentionProperties retention, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.retention = retention;
        this.snapshot = new TransactionTemplate(transactions);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * The complete, unexpired result of this cache identity, for {@code cached: true}. An INCOMPLETE result is never returned:
     * it is stored so that GET can read it again, not so that it can answer another request.
     */
    public Optional<ImageCheckResult> findCached(Identity identity) {
        return read(HEADER + " WHERE " + IDENTITY_FILTER + " AND c.completeness = 'COMPLETE' AND c.expires_at > now()", true,
                identity.clientId(), identity.marketplace(), identity.packId(), identity.inputHash(),
                identity.rulesetVersionId(), identity.analysisVersion());
    }

    /** A retained result of this client, as stored ({@code cached} false). Empty also for another client's and an expired one. */
    public Optional<ImageCheckResult> find(UUID clientId, UUID id) {
        return read(HEADER + " WHERE c.id = ? AND c.client_id = ? AND c.expires_at > now()", false, id, clientId);
    }

    /**
     * Stores the result. For a complete result the expired row of the same cache identity, if cleanup has not removed it yet, is
     * deleted first (its items and findings go with it) so that the unique index does not keep the new result out. Two requests
     * replacing the same expired row both reach the insert; the second one fails with a unique violation on
     * {@link #CACHE_INDEX}, which {@link #isCacheIdentityViolation} recognizes.
     *
     * @param sourceStatusByRule the status of the ruleset source of each rule, kept with the finding it explains
     * @return the id of the new check
     */
    public UUID insert(Identity identity, String profile, String disclaimer, Map<String, String> sourceStatusByRule,
                       com.thanhdatpb.listingguard.image.ImageCheckResult result) {
        if (result.completeness() == Completeness.COMPLETE) {
            jdbc.update("DELETE FROM image_check c WHERE " + IDENTITY_FILTER + " AND c.expires_at <= now()",
                    identity.clientId(), identity.marketplace(), identity.packId(), identity.inputHash(),
                    identity.rulesetVersionId(), identity.analysisVersion());
        }
        UUID id = UUID.randomUUID();
        double retentionSeconds = retention.period().toMillis() / 1000.0;
        jdbc.update("""
                INSERT INTO image_check (id, client_id, marketplace_code, pack_id, input_hash, ruleset_version_id,
                    analysis_version, profile, completeness, summary, failed_rule_ids, disclaimer, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now() + make_interval(secs => ?))""",
                ps -> {
                    ps.setObject(1, id);
                    ps.setObject(2, identity.clientId());
                    ps.setString(3, identity.marketplace());
                    ps.setString(4, identity.packId());
                    ps.setString(5, identity.inputHash());
                    ps.setLong(6, identity.rulesetVersionId());
                    ps.setString(7, identity.analysisVersion());
                    ps.setString(8, profile);
                    ps.setString(9, result.completeness().name());
                    ps.setString(10, result.summary());
                    ps.setArray(11, ps.getConnection().createArrayOf("text", result.failedRules().toArray()));
                    ps.setString(12, disclaimer);
                    ps.setDouble(13, retentionSeconds);
                });
        List<FindingRow> rows = new ArrayList<>();
        for (ImageFinding finding : result.findings()) {
            rows.add(new FindingRow(null, finding, sourceStatusByRule.getOrDefault(finding.ruleId(), "unverified")));
        }
        for (ImageItemResult item : result.images()) {
            long itemId = insertItem(id, item);
            for (ImageFinding finding : item.findings()) {
                rows.add(new FindingRow(itemId, finding, sourceStatusByRule.getOrDefault(finding.ruleId(), "unverified")));
            }
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO image_check_finding (check_id, item_id, position, rule_id, severity, source_status, evidence, message)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int position) throws SQLException {
                    FindingRow row = rows.get(position);
                    ps.setObject(1, id);
                    ps.setObject(2, row.itemId());
                    ps.setInt(3, position);
                    ps.setString(4, row.finding().ruleId());
                    ps.setString(5, row.finding().severity().name());
                    ps.setString(6, row.sourceStatus());
                    ps.setString(7, row.finding().evidence());
                    ps.setString(8, row.finding().message());
                }

                @Override
                public int getBatchSize() {
                    return rows.size();
                }
            });
        }
        return id;
    }

    private long insertItem(UUID checkId, ImageItemResult item) {
        ImageMetadata metadata = item.metadata();
        return jdbc.execute((java.sql.Connection connection) -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO image_check_item (check_id, ordinal, image_hash, role, source, completeness, failed_rule_ids,
                        format, width, height, orientation, had_alpha, had_icc_profile)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")) {
                ps.setObject(1, checkId);
                ps.setInt(2, item.ordinal());
                ps.setString(3, item.imageHash());
                ps.setString(4, item.role().name());
                ps.setString(5, item.source().name());
                ps.setString(6, item.completeness().name());
                ps.setArray(7, connection.createArrayOf("text", item.failedRules().toArray()));
                ps.setString(8, metadata == null ? null : metadata.format());
                ps.setObject(9, metadata == null ? null : metadata.width(), java.sql.Types.INTEGER);
                ps.setObject(10, metadata == null ? null : metadata.height(), java.sql.Types.INTEGER);
                ps.setObject(11, metadata == null ? null : (short) metadata.orientation(), java.sql.Types.SMALLINT);
                ps.setObject(12, metadata == null ? null : metadata.hadAlpha(), java.sql.Types.BOOLEAN);
                ps.setObject(13, metadata == null ? null : metadata.hadIccProfile(), java.sql.Types.BOOLEAN);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private record FindingRow(Long itemId, ImageFinding finding, String sourceStatus) {
    }

    /**
     * Whether the failure is the unique violation of the cache identity: another request stored the same complete result first.
     * Any other integrity failure (a foreign key, a check constraint, another unique index) is not a cache hit and must be
     * reported as it is.
     */
    public static boolean isCacheIdentityViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains("\"" + CACHE_INDEX + "\"")) {
                return true;
            }
        }
        return false;
    }

    private Optional<ImageCheckResult> read(String headerSql, boolean cached, Object... args) {
        return snapshot.execute(status -> {
            List<Header> headers = jdbc.query(headerSql, (rs, row) -> header(rs), args);
            if (headers.isEmpty()) {
                return Optional.<ImageCheckResult>empty();
            }
            Header header = headers.get(0);
            Map<Long, List<ImageCheckResult.FindingView>> findingsByItem = new HashMap<>();
            List<ImageCheckResult.FindingView> packFindings = new ArrayList<>();
            jdbc.query("""
                    SELECT item_id, rule_id, severity, source_status, evidence, message
                    FROM image_check_finding WHERE check_id = ? ORDER BY position""", rs -> {
                ImageCheckResult.FindingView view = new ImageCheckResult.FindingView(rs.getString("rule_id"),
                        Severity.valueOf(rs.getString("severity")), rs.getString("source_status"), rs.getString("message"),
                        rs.getString("evidence"));
                long itemId = rs.getLong("item_id");
                if (rs.wasNull()) {
                    packFindings.add(view);
                } else {
                    findingsByItem.computeIfAbsent(itemId, key -> new ArrayList<>()).add(view);
                }
            }, header.id());
            List<ImageCheckResult.ImageView> images = jdbc.query("""
                    SELECT id, ordinal, image_hash, role, source, completeness, failed_rule_ids, format, width, height,
                           orientation, had_alpha, had_icc_profile
                    FROM image_check_item WHERE check_id = ? ORDER BY ordinal""", (rs, row) -> {
                long itemId = rs.getLong("id");
                ImageCheckResult.Metadata metadata = null;
                String format = rs.getString("format");
                if (format != null) {
                    metadata = new ImageCheckResult.Metadata(format, rs.getInt("width"), rs.getInt("height"),
                            rs.getInt("orientation"), rs.getBoolean("had_alpha"), rs.getBoolean("had_icc_profile"));
                }
                return new ImageCheckResult.ImageView(rs.getInt("ordinal"), rs.getString("image_hash").trim(),
                        ImageRole.valueOf(rs.getString("role")), ImageSource.valueOf(rs.getString("source")),
                        Completeness.valueOf(rs.getString("completeness")), strings(rs.getArray("failed_rule_ids")), metadata,
                        List.copyOf(findingsByItem.getOrDefault(itemId, List.of())));
            }, header.id());
            return Optional.of(new ImageCheckResult(header.id(), header.marketplace(), header.profile(),
                    header.rulesetVersion(), header.analysisVersion(), cached, header.completeness(), header.summary(),
                    header.failedRules(), header.disclaimer(), header.createdAt(), header.expiresAt(),
                    List.copyOf(packFindings), List.copyOf(images)));
        });
    }

    private record Header(UUID id, String marketplace, String profile, String rulesetVersion, String analysisVersion,
                          Completeness completeness, String summary, List<String> failedRules, String disclaimer,
                          java.time.Instant createdAt, java.time.Instant expiresAt) {
    }

    private static Header header(ResultSet rs) throws SQLException {
        return new Header(rs.getObject("id", UUID.class), rs.getString("marketplace_code"), rs.getString("profile"),
                rs.getString("ruleset_version"), rs.getString("analysis_version").trim(),
                Completeness.valueOf(rs.getString("completeness")), rs.getString("summary"),
                strings(rs.getArray("failed_rule_ids")), rs.getString("disclaimer"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    private static List<String> strings(Array array) throws SQLException {
        List<String> values = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            values.add((String) value);
        }
        return List.copyOf(values);
    }
}
