package com.thanhdatpb.listingguard.api.trademark.judge;

import com.thanhdatpb.listingguard.api.trademark.judge.JudgeEvidence.TsdrState;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrProperties;
import com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * The last TSDR status of a mark's source records for the evidence pack (ADR-051), read from the {@code tsdr_status} table that the
 * {@code tsdr-status} job fills. It never calls the USPTO. It answers only when the operator turned the status on
 * ({@code listing-guard.trademark.tsdr.finding-status.enabled}, ADR-009), the same switch that shows it in the API answer; otherwise
 * and for a mark with no recorded status it says nothing, and the evidence says {@code unknown}.
 *
 * <p>A mark can hold several source records. {@code LIVE} is reported when any record is live; {@code DEAD} only when every source
 * record has a recorded status and all are dead; anything else is {@code UNKNOWN}, which is not a status of the mark but a gap in what
 * is recorded.
 */
@Component
public class TsdrJudgeSource implements JudgeEvidenceGatherer.TsdrSource {

    private static final Logger log = LoggerFactory.getLogger(TsdrJudgeSource.class);
    private static final Pattern USPTO_SOURCE_ID = Pattern.compile("uspto:([0-9]{8})");

    private final TsdrProperties properties;
    private final TsdrStatusStore store;
    private final Clock clock;

    public TsdrJudgeSource(TsdrProperties properties, TsdrStatusStore store, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public Optional<TsdrState> latest(List<String> sourceIds) {
        if (!properties.findingStatus().enabled() || sourceIds.isEmpty()) {
            return Optional.empty();
        }
        Set<String> ids = new LinkedHashSet<>(sourceIds);
        Set<String> serials = new LinkedHashSet<>();
        for (String id : ids) {
            Matcher matcher = USPTO_SOURCE_ID.matcher(id);
            if (matcher.matches()) {
                serials.add(matcher.group(1));
            }
        }
        if (serials.isEmpty()) {
            return Optional.empty();
        }
        Map<String, TsdrStatusStore.Latest> latest;
        try {
            latest = store.latestOk(serials);
        } catch (DataAccessException e) {
            log.warn("The TSDR status for the judge's evidence could not be read; it is left out: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        Optional<TsdrStatusStore.Latest> live = latest.values().stream()
                .filter(status -> status.statusClass() == TsdrStatusStore.StatusClass.LIVE)
                .max(Comparator.comparing(TsdrStatusStore.Latest::checkedAt));
        if (live.isPresent()) {
            return Optional.of(state("LIVE", live.get()));
        }
        TsdrStatusStore.Latest newest = latest.values().stream().max(Comparator.comparing(TsdrStatusStore.Latest::checkedAt)).orElseThrow();
        boolean everyRecordKnown = latest.size() == ids.size() && new HashSet<>(latest.keySet()).equals(serials);
        boolean allDead = latest.values().stream().allMatch(status -> status.statusClass() == TsdrStatusStore.StatusClass.DEAD);
        return Optional.of(state(everyRecordKnown && allDead ? "DEAD" : "UNKNOWN", newest));
    }

    private TsdrState state(String statusClass, TsdrStatusStore.Latest status) {
        return new TsdrState(statusClass, Math.max(0, ChronoUnit.DAYS.between(status.checkedAt(), clock.instant())));
    }
}
