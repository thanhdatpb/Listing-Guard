package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTemplates;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditContexts.Context;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.Run;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Reason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * With {@code listing-guard.workspace.audit.clone-leftover} off, its default, the audit is what it was (ADR-039 §4): a clone whose size is new and whose
 * text is still the original's gets no finding of the pack, the analysis version of every listing is the one it always had, and an edited original does
 * not make its clone be audited again. The same scenario as the integration test with the switch on, so the two differ only by the switch.
 */
class CloneLeftoverDisabledAuditIntegrationTest extends CloneAuditBase {

    @Autowired
    WorkspaceTemplates templates;

    private static final Map<String, String> MUG = Map.of("item_sku", "A-1", "feed_product_type", "MUG", "item_name", "Funny Mug 11oz",
            "brand_name", "Nobrand", "bullet_point1", "Holds 11oz of coffee", "product_description", "A mug that holds 11oz.", "size_name", "11oz",
            "color_name", "White");

    @Test
    void aCloneWithANewSizeAndTheOldTextGetsNoFindingOfThePackAndKeepsTheOldAnalysisVersion() {
        Team team = team();
        serve("z", "Qualmark");
        SellerAccount shop = amazonShop(team, "Shop Z");
        importFile(team, shop, flatFile(FIRST, List.of(row(FIRST, MUG))));
        Listing original = bySku(team, shop, "A-1");
        Listing cloned = clone(team, original, shop, "-C");
        audit();
        Listing clone = save(team, cloned, Map.of("size_name", "15oz"));

        assertThat(cloneFindings(clone)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_listing_finding WHERE client_id = ? AND pack NOT IN ('listing-rules', 'trademark-risk')",
                Long.class, team.client().getId())).isZero();
        Template template = templates.find(team.client().getId(), original.templateId()).orElseThrow();
        Context context = contexts.of(team.client().getId(), "AMAZON");
        var scope = auditor.scopeFields(Marketplace.AMAZON, template);
        assertThat(context.cloneLeftover()).isNull();
        assertThat(context.key()).isEqualTo(AuditHasher.analysis(List.of("ctx1", context.marketplace(), context.rulesVersion(), context.trademarkRuleset(),
                String.valueOf(context.catalogVersion()), String.valueOf(context.watchlistVersion()))));
        assertThat(analysisVersionOf(original)).isEqualTo(context.analysisVersion(scope));
        assertThat(analysisVersionOf(clone)).isEqualTo(context.analysisVersion(scope));
    }

    @Test
    void anEditedOriginalDoesNotMakeItsCloneBeAuditedAgain() {
        Team team = team();
        serve("y", "Qualmark");
        SellerAccount shop = amazonShop(team, "Shop Y");
        importFile(team, shop, flatFile(FIRST, List.of(row(FIRST, MUG))));
        Listing original = bySku(team, shop, "A-1");
        Listing clone = save(team, clone(team, original, shop, "-C"), Map.of("size_name", "15oz"));
        audit();

        save(team, original, Map.of("color_name", "Blue"));
        audit.request(team.client().getId(), shop.id(), Reason.MANUAL, null, team.adminAccount().getId());
        worker.drain();

        Run run = store.lastFinished(team.client().getId(), shop.id()).orElseThrow();
        assertThat(run.done()).isZero();
        assertThat(run.skipped()).isEqualTo(2);
        assertThat(cloneFindings(clone)).isEmpty();
    }

}
