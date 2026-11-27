package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.Run;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Reason;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.RunStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The background audit end to end on a real database (ADR-061): a new catalog or a changed watchlist makes only the related listing a new
 * risk, an unchanged listing is not checked again, the position of a mark is right through punctuation, case and extra spaces, a run that was
 * interrupted is taken again and finishes without doubling anything, and one client's audit never reads or writes another's. Synthetic
 * catalogs and listings only.
 */
class WorkspaceAuditIntegrationTest extends AuditIntegrationBase {

    private static final String SHIRT = "  Cool   ZORVEX!!  Hoodie, soft ";

    @Test
    void aNewCatalogMakesOnlyTheListingThatTouchesItsNewMarkARiskAndThePositionIsRight() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop A");
        Template template = etsyTemplate(team, shop);
        String first = serve("a", "Qualmark");
        Listing hoodie = insert(team, shop, template, "H-1", fields(SHIRT, "A plain description of a hoodie."), team.memberAccount().getId());
        Listing mug = insert(team, shop, template, "M-1", fields("Plain ceramic mug", "A plain description of a mug."), team.memberAccount().getId());

        audit();
        // the first audit has nothing to compare with: nothing is a new risk (ADR-035)
        assertThat(stateOf(hoodie)).isNotEqualTo("NEW_RISK");
        assertThat(stateOf(mug)).isNotEqualTo("NEW_RISK");
        assertThat(findingsOf(hoodie)).noneMatch(f -> Boolean.TRUE.equals(f.get("is_new")));

        String second = serve("b", "Qualmark", "Zorvex");
        assertThat(second).isNotEqualTo(first);
        audit();

        assertThat(stateOf(hoodie)).isEqualTo("NEW_RISK");
        assertThat(stateOf(mug)).isNotEqualTo("NEW_RISK");
        List<Map<String, Object>> found = findingsOf(hoodie);
        Map<String, Object> mark = found.stream().filter(f -> "CATALOG".equals(f.get("source"))).findFirst().orElseThrow();
        assertThat(mark.get("is_new")).isEqualTo(true);
        assertThat(mark.get("field")).isEqualTo("title");
        // the span is in the stored text, which keeps the case, the punctuation and the extra spaces the seller typed
        String stored = listings.find(team.client().getId(), hoodie.id()).orElseThrow().fields().path("title").asText();
        assertThat(stored.substring((Integer) mark.get("span_start"), (Integer) mark.get("span_end"))).isEqualTo("ZORVEX");
        Run run = store.lastFinished(team.client().getId(), shop.id()).orElseThrow();
        assertThat(run.reason()).isEqualTo(Reason.CATALOG);
        assertThat(run.status()).isEqualTo(RunStatus.DONE);
        assertThat(run.catalogVersion()).isEqualTo(second);
        assertThat(run.newRiskListings()).isEqualTo(1);
        assertThat(run.total()).isEqualTo(2);
    }

    @Test
    void aChangedWatchlistMakesOnlyTheListingThatHasTheNewEntryARisk() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop W");
        Template template = etsyTemplate(team, shop);
        serve("w", "Qualmark");
        Listing otters = insert(team, shop, template, "O-1", fields("Brimwick Otters sticker", "A plain description."), null);
        Listing plain = insert(team, shop, template, "P-1", fields("Plain ceramic mug", "A plain description."), null);
        audit();
        assertThat(stateOf(otters)).isNotEqualTo("NEW_RISK");

        watch(team, "Brimwick Otters");
        audit();

        assertThat(stateOf(otters)).isEqualTo("NEW_RISK");
        assertThat(stateOf(plain)).isNotEqualTo("NEW_RISK");
        assertThat(findingsOf(otters)).anyMatch(f -> "WATCHLIST".equals(f.get("source")) && Boolean.TRUE.equals(f.get("is_new")));
        assertThat(store.lastFinished(team.client().getId(), shop.id()).orElseThrow().reason()).isEqualTo(Reason.WATCHLIST);
    }

    @Test
    void aListingWhoseTextAndAnalysisDidNotChangeIsNotCheckedAgain() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop S");
        Template template = etsyTemplate(team, shop);
        serve("s", "Qualmark");
        Listing mug = insert(team, shop, template, "M-1", fields("Plain ceramic mug", "A plain description."), null);
        audit();
        var before = store.audit(team.client().getId(), mug.id()).orElseThrow();
        long checks = jdbc.queryForObject("SELECT COUNT(*) FROM listing_check WHERE client_id = ?", Long.class, team.client().getId());

        audit.request(team.client().getId(), shop.id(), Reason.MANUAL, null, team.adminAccount().getId());
        assertThat(worker.drain()).isEqualTo(1);

        Run run = store.lastFinished(team.client().getId(), shop.id()).orElseThrow();
        assertThat(run.reason()).isEqualTo(Reason.MANUAL);
        assertThat(run.skipped()).isEqualTo(1);
        assertThat(run.done()).isZero();
        assertThat(store.audit(team.client().getId(), mug.id()).orElseThrow().auditedAt()).isEqualTo(before.auditedAt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_check WHERE client_id = ?", Long.class, team.client().getId())).isEqualTo(checks);
    }

    @Test
    void aRulePositionIsFoundThroughExtraSpacesAndCase() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop R");
        Template template = etsyTemplate(team, shop);
        serve("r", "Qualmark");
        Listing mug = insert(team, shop, template, "M-1", fields("   Mug with  FREE   Shipping inside", "A plain description."), null);

        audit();

        Map<String, Object> promo = findingsOf(mug).stream().filter(f -> "title_promotional_terms".equals(f.get("rule_id"))).findFirst().orElseThrow();
        String stored = mug.fields().path("title").asText();
        assertThat(stored.substring((Integer) promo.get("span_start"), (Integer) promo.get("span_end"))).isEqualTo("FREE   Shipping");
        assertThat(promo.get("source")).isEqualTo("RULE");
    }

    @Test
    void aListingWhoseTrademarkPartDidNotRunIsNeverCalledClean() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop I");
        Template template = etsyTemplate(team, shop);
        // no catalog is served and the client has no watchlist
        Listing mug = insert(team, shop, template, "M-1", fields("Plain ceramic mug", "A plain description."), null);

        audit();

        assertThat(stateOf(mug)).isEqualTo("INCOMPLETE");
    }

    @Test
    void aRunThatWasInterruptedIsTakenAgainAndFinishesWithoutDoublingAnything() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop X");
        Template template = etsyTemplate(team, shop);
        serve("x", "Qualmark");
        Listing a = insert(team, shop, template, "A-1", fields("Plain ceramic mug one", "A plain description."), null);
        Listing b = insert(team, shop, template, "B-1", fields("Plain ceramic mug two", "A plain description."), null);
        Listing c = insert(team, shop, template, "C-1", fields("Plain ceramic mug three", "A plain description."), null);
        UUID runId = audit.request(team.client().getId(), shop.id(), Reason.MANUAL, null, null).orElseThrow();

        // a worker takes the run, audits one listing and dies: its lease is left to run out
        Run taken = store.claim(java.time.Duration.ofMinutes(2)).orElseThrow();
        assertThat(taken.id()).isEqualTo(runId);
        var env = envOf(team, shop, template);
        assertThat(auditor.audit(env, a, taken.id()).outcome()).isEqualTo(ListingAuditor.Outcome.AUDITED);
        assertThat(worker.drain()).isZero();
        assertThat(store.run(team.client().getId(), runId).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
        jdbc.update("UPDATE workspace_audit_run SET lease_until = now() - interval '1 minute' WHERE id = ?", runId);

        assertThat(worker.drain()).isEqualTo(1);

        Run finished = store.run(team.client().getId(), runId).orElseThrow();
        assertThat(finished.status()).isEqualTo(RunStatus.DONE);
        assertThat(finished.attempts()).isEqualTo(2);
        assertThat(finished.total()).isEqualTo(3);
        assertThat(finished.skipped()).isEqualTo(1);
        assertThat(finished.done()).isEqualTo(2);
        for (Listing listing : List.of(a, b, c)) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_listing_audit WHERE listing_id = ?", Long.class, listing.id())).isEqualTo(1);
        }
    }

    @Test
    void twoWorkersNeverTakeTheSameRunAndOneAccountHasOneRunAtATime() {
        Team team = team();
        SellerAccount one = etsyShop(team, "Shop 1");
        SellerAccount two = etsyShop(team, "Shop 2");
        etsyTemplate(team, one);
        etsyTemplate(team, two);
        UUID first = audit.request(team.client().getId(), one.id(), Reason.MANUAL, null, null).orElseThrow();
        UUID second = audit.request(team.client().getId(), two.id(), Reason.MANUAL, null, null).orElseThrow();

        Run a = store.claim(java.time.Duration.ofMinutes(2)).orElseThrow();
        Run b = store.claim(java.time.Duration.ofMinutes(2)).orElseThrow();

        assertThat(List.of(a.id(), b.id())).containsExactlyInAnyOrder(first, second);
        assertThat(store.claim(java.time.Duration.ofMinutes(2))).isEmpty();
        // a request for an account that already has a run working waits behind it
        UUID later = audit.request(team.client().getId(), one.id(), Reason.MANUAL, null, null).orElseThrow();
        assertThat(later).isNotEqualTo(first);
        assertThat(store.claim(java.time.Duration.ofMinutes(2))).isEmpty();
    }

    @Test
    void waitingRunsOfOneAccountAreMergedAndAWholeAccountWidensASmallOne() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop M");
        Template template = etsyTemplate(team, shop);
        Listing a = insert(team, shop, template, "A-1", fields("Plain ceramic mug one", "A plain description."), null);
        Listing b = insert(team, shop, template, "B-1", fields("Plain ceramic mug two", "A plain description."), null);

        UUID first = audit.request(team.client().getId(), shop.id(), Reason.IMPORT, List.of(a.id()), null).orElseThrow();
        UUID second = audit.request(team.client().getId(), shop.id(), Reason.IMPORT, List.of(b.id()), null).orElseThrow();
        assertThat(second).isEqualTo(first);
        assertThat(store.run(team.client().getId(), first).orElseThrow().listingIds()).containsExactlyInAnyOrder(a.id(), b.id());
        UUID whole = audit.request(team.client().getId(), shop.id(), Reason.MANUAL, null, null).orElseThrow();
        assertThat(whole).isEqualTo(first);
        assertThat(store.run(team.client().getId(), first).orElseThrow().listingIds()).isNull();
    }

    @Test
    void anImportOnlyAuditsTheListingsItWrote() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop N");
        Template template = etsyTemplate(team, shop);
        serve("n", "Qualmark");
        Listing old = insert(team, shop, template, "O-1", fields("Plain ceramic mug one", "A plain description."), null);
        Listing fresh = insert(team, shop, template, "F-1", fields("Plain ceramic mug two", "A plain description."), null);

        events.publishEvent(new com.thanhdatpb.listingguard.api.workspace.WorkspaceEvents.ListingsImported(team.client().getId(), shop.id(),
                List.of(fresh.id()), team.adminAccount().getId()));
        assertThat(store.hasOpenRun(team.client().getId(), shop.id())).isTrue();
        assertThat(worker.drain()).isEqualTo(1);

        assertThat(stateOf(fresh)).isNotEqualTo("NONE");
        assertThat(stateOf(old)).isEqualTo("NONE");
        assertThat(store.lastFinished(team.client().getId(), shop.id()).orElseThrow().reason()).isEqualTo(Reason.IMPORT);
    }

    @Test
    void aSavedListingIsAuditedAtOnceAndAClientNeverReachesAnotherClientsListing() {
        Team team = team();
        Team other = team();
        SellerAccount shop = etsyShop(team, "Shop Z");
        Template template = etsyTemplate(team, shop);
        serve("z", "Qualmark");
        Listing mug = insert(team, shop, template, "M-1", fields("Plain ceramic mug", "A plain description."), null);

        events.publishEvent(new com.thanhdatpb.listingguard.api.workspace.WorkspaceEvents.ListingSaved(team.client().getId(), mug.id()));

        assertThat(stateOf(mug)).isNotEqualTo("NONE");
        // the same listing id asked for by another client is not found, and nothing is queued for another client's account
        assertThat(audit.auditNow(other.client().getId(), mug.id())).isEmpty();
        assertThat(audit.request(other.client().getId(), shop.id(), Reason.MANUAL, null, null)).isEmpty();
        assertThat(store.audit(other.client().getId(), mug.id())).isEmpty();
        assertThat(store.findings(other.client().getId(), mug.id())).isEmpty();
        assertThat(store.liveListingIds(other.client().getId(), shop.id(), null)).isEmpty();
    }

    @Test
    void marksSeenStopCountingAsNewAndTheAuditDoesNotClearAnUnseenOneByItself() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop K");
        Template template = etsyTemplate(team, shop);
        serve("k", "Qualmark");
        Listing hoodie = insert(team, shop, template, "H-1", fields(SHIRT, "A plain description of a hoodie."), null);
        audit();
        serve("k2", "Qualmark", "Zorvex");
        audit();
        assertThat(stateOf(hoodie)).isEqualTo("NEW_RISK");

        // a later audit under another ruleset-like change (the watchlist) does not clear what nobody looked at
        watch(team, "Unrelated Brand");
        audit();
        assertThat(stateOf(hoodie)).isEqualTo("NEW_RISK");

        assertThat(store.acknowledge(team.client().getId(), hoodie.id())).isTrue();
        assertThat(stateOf(hoodie)).isNotEqualTo("NEW_RISK");
        assertThat(findingsOf(hoodie)).noneMatch(f -> Boolean.TRUE.equals(f.get("is_new")));
        assertThat(store.acknowledge(other().client().getId(), hoodie.id())).isFalse();
    }

    private Team other() {
        return team();
    }

    @org.springframework.beans.factory.annotation.Autowired
    ListingAuditor auditor;
    @org.springframework.beans.factory.annotation.Autowired
    AuditContexts contexts;
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationEventPublisher events;

    private ListingAuditor.Env envOf(Team team, SellerAccount shop, Template template) {
        return auditor.env(team.client().getId(), shop.id(), shop.marketplace(), template, contexts.of(team.client().getId(), shop.marketplace().name()),
                java.util.Collections::emptyMap);
    }
}
