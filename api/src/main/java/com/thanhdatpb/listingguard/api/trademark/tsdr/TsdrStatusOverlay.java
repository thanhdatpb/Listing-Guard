package com.thanhdatpb.listingguard.api.trademark.tsdr;

import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult.TsdrStatusView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Adds the last known TSDR status to the findings of a trademark result when an operator asked for it
 * ({@code listing-guard.trademark.tsdr.finding-status.enabled}, off by default; ADR-009, "TSDR status check").
 *
 * <p>Off, {@link #apply} returns the result it was given, so the answer is what it was byte for byte. On, the status is looked
 * up when the answer is built and is <b>never stored with the result</b>: it changes no risk level, no cache identity, no
 * analysis fingerprint and nothing the retention keeps. A result read again later shows the then-latest status and the date it
 * was checked. A look-up that fails leaves the answer without the member: the check itself never depends on it.
 */
@Component
public class TsdrStatusOverlay {

    private static final Logger log = LoggerFactory.getLogger(TsdrStatusOverlay.class);
    private static final Pattern USPTO_SOURCE_ID = Pattern.compile("uspto:([0-9]{8})");

    private final TsdrProperties properties;
    private final TsdrStatusStore store;

    public TsdrStatusOverlay(TsdrProperties properties, TsdrStatusStore store) {
        this.properties = properties;
        this.store = store;
    }

    public boolean enabled() {
        return properties.findingStatus().enabled();
    }

    public TrademarkCheckResult apply(TrademarkCheckResult result) {
        if (!enabled()) {
            return result;
        }
        Set<String> serials = new LinkedHashSet<>();
        for (TrademarkCheckResult.FindingView finding : result.findings()) {
            for (String sourceId : finding.sourceIds()) {
                Matcher m = USPTO_SOURCE_ID.matcher(sourceId);
                if (m.matches()) {
                    serials.add(m.group(1));
                }
            }
        }
        Map<String, TsdrStatusStore.Latest> latest;
        try {
            latest = store.latestOk(serials);
        } catch (DataAccessException e) {
            log.warn("The TSDR status of the findings could not be read; the answer goes without it: {}",
                    e.getClass().getSimpleName());
            return result;
        }
        List<TrademarkCheckResult.FindingView> findings = new ArrayList<>();
        for (TrademarkCheckResult.FindingView finding : result.findings()) {
            List<TsdrStatusView> statuses = new ArrayList<>();
            for (String sourceId : finding.sourceIds()) {
                Matcher m = USPTO_SOURCE_ID.matcher(sourceId);
                TsdrStatusStore.Latest status = m.matches() ? latest.get(m.group(1)) : null;
                if (status != null) {
                    statuses.add(new TsdrStatusView(sourceId, status.statusCode(), status.statusDescription(),
                            status.statusClass().name(), status.statusDate(), status.checkedAt()));
                }
            }
            findings.add(finding.withTsdrStatuses(statuses));
        }
        return result.withFindings(findings);
    }
}
