package com.thanhdatpb.listingguard.api.console.reaudit;

import com.thanhdatpb.listingguard.api.console.FindingCards;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.UUID;

/**
 * What the re-audit page shows after a run (ADR-035). It is built for the response; only the counts and hashes of {@link ReauditStore}
 * are kept. The findings carry the rules' own message, which can quote the listing, so they live in the response and nowhere else.
 *
 * @param account                 the label the seller typed, empty when none
 * @param catalogVersion          the catalog version of the run, null when none was served
 * @param watchlistRevision       the revision of the client's watchlist the run used, null when it used none
 * @param previousAt              when the run before it started, null for the first run of the scope
 * @param previousCatalogVersion  the catalog version the run before it used, null when it used none or there was no run
 * @param catalogVersionMissing   the previous catalog version is not stored any more, so every catalog risk is called new
 * @param newRisks                risks caused by a mark or an entry that the previous run did not have
 * @param knownRisks              every other risk
 * @param changes                 what changed in the listings since the previous run, null for the first run
 * @param skipped                 rows that were not judged and why
 * @param notes                   what holds for the whole run (a check that did not run, a quota that ran out)
 * @param csvHref                 a {@code data:} link to the list of what needs fixing
 */
public record ReauditResult(UUID runId, String marketplace, String account, String adapter, Instant startedAt,
                            String catalogVersion, Long watchlistRevision, Instant previousAt, String previousCatalogVersion,
                            boolean catalogVersionMissing, int rowsTotal, int rowsChecked, List<Risk> newRisks,
                            List<Risk> knownRisks, List<Risk> weakMatches, ReauditComparison.Changes changes, List<Skipped> skipped, List<String> notes,
                            String disclaimer, String csvHref) {

    public record Highlights(List<Risk> newPrimary, List<Risk> newMore, List<Risk> knownPrimary,
                             List<Risk> knownMore, Map<Integer, List<Risk>> weakByLine) { }

    public Highlights highlights() {
        List<Risk> ranked = new ArrayList<>(newRisks);
        ranked.addAll(knownRisks);
        ranked.sort(Comparator.comparingInt(risk -> FindingCards.rank(risk.level())));
        var counts = new HashMap<Integer, Integer>();
        var newPrimary = new ArrayList<Risk>();
        var newMore = new ArrayList<Risk>();
        var knownPrimary = new ArrayList<Risk>();
        var knownMore = new ArrayList<Risk>();
        for (Risk risk : ranked) {
            boolean primary = !"trademark-risk".equals(risk.pack())
                    || counts.merge(risk.line(), 1, Integer::sum) <= 3;
            if (risk.isNew()) {
                (primary ? newPrimary : newMore).add(risk);
            } else {
                (primary ? knownPrimary : knownMore).add(risk);
            }
        }
        Map<Integer, List<Risk>> weak = new TreeMap<>();
        for (Risk risk : weakMatches) {
            weak.computeIfAbsent(risk.line(), line -> new ArrayList<>()).add(risk);
        }
        return new Highlights(newPrimary, newMore, knownPrimary, knownMore, weak);
    }

    /** True when the scope had no earlier run: nothing was compared, so every risk is listed as known. */
    public boolean firstRun() {
        return previousAt == null;
    }

    /**
     * One risk that needs fixing: a listing-rules ERROR or WARNING, or a trademark finding of level MEDIUM or HIGH.
     *
     * @param pack      {@code listing-rules} or {@code trademark-risk}
     * @param mark      the catalog mark or watchlist entry, null for a finding that has none
     * @param level     {@code error}, {@code warning}, {@code high} or {@code medium}: the CSS and sort key
     * @param levelLabel what the page prints, in the language of the page
     * @param source    {@code RULE}, {@code CATALOG}, {@code CONTEXT} or {@code WATCHLIST}
     */
    public record Risk(int line, String sku, String pack, String ruleId, String source, String field, String mark, String level,
                       String levelLabel, String message, String suggestion, boolean isNew) {
    }

    /** A row that was not judged: its line, its SKU and why, in the language of the page. */
    public record Skipped(int line, String sku, String reason) {
    }
}
