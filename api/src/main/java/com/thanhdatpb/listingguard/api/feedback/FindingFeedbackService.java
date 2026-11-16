package com.thanhdatpb.listingguard.api.feedback;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Stores and reads verdicts on findings (ADR-034). One verdict per reviewer per finding: a second answer replaces the verdict and
 * the note of the first and keeps its {@code created_at}. Only a finding of the reviewer's own client can be answered
 * ({@link FeedbackFindings}); anything else is {@link FindingNotFoundException}. It needs no plan pack and charges nothing:
 * feedback is no analysis and stores no result (ADR-013). It changes no finding, result, rule or cache.
 *
 * <p>The note is the reviewer's own words; it is never logged, and only its author gets it back.
 */
@Service
public class FindingFeedbackService {

    /** The longest note, in characters (code points); equal to {@code ck_finding_verdict_note} of V37 (a test holds it). */
    public static final int MAX_NOTE_CHARS = 280;

    /** What a page needs to show one reviewer's own verdict on a finding. */
    public record Mine(FeedbackVerdict verdict, String note) {
    }

    private final FeedbackFindings findings;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public FindingFeedbackService(FeedbackFindings findings, JdbcTemplate jdbc, Clock clock) {
        this.findings = findings;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Stores a verdict from text, as a request carries it.
     *
     * @throws InvalidFeedbackException for an unknown pack or verdict, or a note over {@link #MAX_NOTE_CHARS}
     * @throws FindingNotFoundException when the client has no finding at that address
     */
    public StoredVerdict record(UUID clientId, Reviewer reviewer, String pack, UUID checkId, int position, String verdict,
                                String note) {
        if (pack == null || !FeedbackTarget.PACKS.contains(pack.trim())) {
            throw new InvalidFeedbackException(InvalidFeedbackException.Reason.PACK,
                    "pack must be one of " + String.join(", ", FeedbackTarget.PACKS) + ".");
        }
        FeedbackVerdict parsed = FeedbackVerdict.parse(verdict).orElseThrow(() -> new InvalidFeedbackException(
                InvalidFeedbackException.Reason.VERDICT, "verdict must be CORRECT, FALSE_POSITIVE or NOT_USEFUL."));
        if (position < 0) {
            throw new FindingNotFoundException();
        }
        return record(clientId, reviewer, new FeedbackTarget(pack.trim(), checkId, position), parsed, note);
    }

    public StoredVerdict record(UUID clientId, Reviewer reviewer, FeedbackTarget target, FeedbackVerdict verdict, String note) {
        String cleaned = cleanNote(note);
        FeedbackFindings.Resolved finding = findings.resolve(clientId, target).orElseThrow(FindingNotFoundException::new);
        Instant now = clock.instant();
        boolean account = reviewer.kind() == Reviewer.Kind.ACCOUNT;
        String conflict = account
                ? "(client_id, pack_id, check_id, finding_position, account_id) WHERE account_id IS NOT NULL"
                : "(client_id, pack_id, check_id, finding_position, api_key_id) WHERE api_key_id IS NOT NULL";
        // xmax = 0 only for a row this statement inserted: a PostgreSQL way to tell an insert from an update in one statement.
        return jdbc.queryForObject("""
                INSERT INTO finding_verdict (client_id, account_id, api_key_id, pack_id, check_id, finding_position, marketplace_code,
                    rule_id, source, finding_level, analysis_version, verdict, note, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT %s DO UPDATE SET verdict = EXCLUDED.verdict, note = EXCLUDED.note,
                    updated_at = GREATEST(finding_verdict.created_at, EXCLUDED.updated_at)
                RETURNING created_at, updated_at, (xmax = 0) AS inserted, marketplace_code, rule_id, source, finding_level,
                    analysis_version""".formatted(conflict),
                (rs, i) -> new StoredVerdict(target, rs.getString("marketplace_code"), rs.getString("rule_id"),
                        FeedbackSource.valueOf(rs.getString("source")), rs.getString("finding_level"),
                        rs.getString("analysis_version"), verdict, cleaned, rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(), rs.getBoolean("inserted")),
                clientId, account ? reviewer.id() : null, account ? null : reviewer.id(), target.pack(), target.checkId(),
                target.position(), finding.marketplace(), finding.ruleId(), finding.source().name(), finding.level(),
                finding.analysisVersion(), verdict.name(), cleaned, Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * The reviewer of an API request: the row of the key the request came with ({@code keyId} is its public id, never the
     * secret). The key was accepted by {@code ApiKeyFilter} for this client, so the row exists.
     */
    public Reviewer apiKeyReviewer(UUID clientId, String keyId) {
        UUID id = jdbc.queryForObject("SELECT id FROM api_key WHERE client_id = ? AND key_id = ?", UUID.class, clientId, keyId);
        return Reviewer.apiKey(id);
    }

    /**
     * The verdicts one account gave on these findings, by {@link FeedbackTarget#key()}: one query for a whole page. A finding the
     * account has not answered is not in the map.
     */
    public Map<String, Mine> mine(UUID clientId, UUID accountId, Collection<FeedbackTarget> targets) {
        Map<String, Mine> out = new HashMap<>();
        if (targets.isEmpty()) {
            return out;
        }
        Set<String> wanted = new LinkedHashSet<>();
        Set<UUID> checks = new LinkedHashSet<>();
        for (FeedbackTarget target : targets) {
            wanted.add(target.key());
            checks.add(target.checkId());
        }
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT pack_id, check_id, finding_position, verdict, note FROM finding_verdict
                    WHERE client_id = ? AND account_id = ? AND check_id = ANY (?)""");
            Array ids = connection.createArrayOf("uuid", checks.toArray());
            statement.setObject(1, clientId);
            statement.setObject(2, accountId);
            statement.setArray(3, ids);
            return statement;
        }, rs -> {
            FeedbackTarget target = new FeedbackTarget(rs.getString("pack_id"), rs.getObject("check_id", UUID.class),
                    rs.getInt("finding_position"));
            if (wanted.contains(target.key())) {
                out.put(target.key(), new Mine(FeedbackVerdict.valueOf(rs.getString("verdict")), rs.getString("note")));
            }
        });
        return out;
    }

    /**
     * The note as stored: line breaks made {@code \n}, outer blanks removed, null when nothing is left. A note over the limit is
     * refused, never cut (a cut note says something its author did not write).
     */
    static String cleanNote(String note) {
        if (note == null) {
            return null;
        }
        String text = note.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.isEmpty()) {
            return null;
        }
        int length = text.codePointCount(0, text.length());
        if (length > MAX_NOTE_CHARS) {
            throw new InvalidFeedbackException(InvalidFeedbackException.Reason.NOTE_TOO_LONG,
                    "note is " + length + " characters long; the limit is " + MAX_NOTE_CHARS + ".", length);
        }
        return text;
    }
}
