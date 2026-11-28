package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.workspace.ListingComparison;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTemplates;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.Run;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Reason;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The comparison of a clone with its original in the background audit (ADR-039 §4), switched on, against a real database: what the audit stores for a
 * clone whose variation attribute is new and whose text is still the original's, in the same account and across two templates, where the span is,
 * and when a clone is audited again. Every assertion reads the final state of the tables. Synthetic accounts, SKUs and files; not verified against a real
 * Amazon template.
 */
@TestPropertySource(properties = "listing-guard.workspace.audit.clone-leftover=true")
class CloneLeftoverAuditIntegrationTest extends CloneAuditBase {

    @Autowired
    WorkspaceTemplates templates;
    @Autowired
    ListingComparison comparison;

    private static final Map<String, String> MUG = Map.of("item_sku", "A-1", "feed_product_type", "MUG", "item_name", "Funny Mug 11oz",
            "brand_name", "Nobrand", "bullet_point1", "Holds 11oz of coffee", "product_description", "A mug that holds 11oz.", "size_name", "11oz",
            "color_name", "White");

    private Listing original(Team team, SellerAccount shop) {
        importFile(team, shop, flatFile(FIRST, List.of(row(FIRST, MUG))));
        return bySku(team, shop, "A-1");
    }

    @Test
    void aCloneWhoseSizeIsNewAndWhoseTextIsStillTheOriginalsGetsAWarningWithTheSpanOfTheOldValue() {
        Team team = team();
        serve("a", "Qualmark");
        SellerAccount shop = amazonShop(team, "Shop A");
        Listing original = original(team, shop);
        Listing clone = clone(team, original, shop, "-C");
        audit();
        // the same size: nothing to report yet
        assertThat(cloneFindings(clone)).isEmpty();

        Listing edited = save(team, clone, Map.of("size_name", "15oz"));

        List<Map<String, Object>> found = cloneFindings(edited);
        assertThat(found).extracting(f -> (String) f.get("field")).containsExactlyInAnyOrder("title", "bullet_points[0]", "description");
        assertThat(found).allSatisfy(f -> {
            assertThat(f.get("pack")).isEqualTo("clone-leftover");
            assertThat(f.get("level")).isEqualTo("warning");
            assertThat(f.get("source")).isEqualTo("RULE");
            assertThat((String) f.get("message")).contains("11oz").contains("15oz").contains("size_name");
            String stored = text(edited, "/" + ((String) f.get("field")).replace("[", "/").replace("]", "").replace("bullet_points", "bullet_points"));
            assertThat(stored.substring((Integer) f.get("span_start"), (Integer) f.get("span_end"))).isEqualTo("11oz");
        });
        assertThat(stateOf(edited)).isEqualTo("WARNING");
        assertThat(store.audit(team.client().getId(), edited.id()).orElseThrow().warningCount()).isGreaterThanOrEqualTo(3);
        // the original is no clone: nothing is compared, nothing is reported
        assertThat(cloneFindings(original)).isEmpty();
        assertThat(cloneFindings(bySku(team, shop, "A-1"))).isEmpty();
    }

    @Test
    void aCloneWhoseTextWasUpdatedToTheNewValueIsClean() {
        Team team = team();
        serve("b", "Qualmark");
        SellerAccount shop = amazonShop(team, "Shop B");
        Listing clone = clone(team, original(team, shop), shop, "-C");
        save(team, clone, Map.of("size_name", "15oz"));
        assertThat(cloneFindings(clone)).isNotEmpty();

        Listing fixed = save(team, clone, Map.of("size_name", "15oz", "item_name", "Funny Mug 15oz", "bullet_point1", "Holds 15oz of coffee",
                "product_description", "A mug that holds 15oz."));

        assertThat(cloneFindings(fixed)).isEmpty();
        assertThat(stateOf(fixed)).isEqualTo("CLEAN");
    }

    @Test
    void aTextThatNamesBothValuesOrNeitherIsLeftAlone() {
        Team team = team();
        serve("c", "Qualmark");
        SellerAccount shop = amazonShop(team, "Shop C");
        Listing clone = clone(team, original(team, shop), shop, "-C");

        Listing edited = save(team, clone, Map.of("size_name", "15oz", "item_name", "Funny Mug 15oz", "bullet_point1", "Available in 11oz or 15oz",
                "product_description", "A plain mug."));

        assertThat(cloneFindings(edited)).isEmpty();
    }

    @Test
    void aCloneInAnotherAccountWithAnotherTemplateIsComparedThroughTheSamePairsAsTheClonePage() {
        Team team = team();
        serve("d", "Qualmark");
        SellerAccount first = amazonShop(team, "Shop D1");
        SellerAccount second = amazonShop(team, "Shop D2");
        Listing original = original(team, first);
        importFile(team, second, flatFile(SECOND, List.of(row(SECOND, Map.of("item_sku", "T-SEED", "feed_product_type", "MUG", "item_name", "Seed")))));
        Template secondTemplate = templates.current(team.client().getId(), second.id(), TemplateKind.AMAZON_BULK).orElseThrow();
        Listing clone = clone(team, original, second, "-X");
        assertThat(clone.templateId()).isEqualTo(secondTemplate.id());
        audit();

        Listing edited = save(team, clone, Map.of("size_name", "15oz"));

        // the pairing is the one of the clone page: the size column of one template goes to the size column of the other
        ListingComparison.Alignment alignment = comparison.aligned(team.client().getId(), edited, secondTemplate).orElseThrow();
        assertThat(alignment.original().id()).isEqualTo(original.id());
        assertThat(alignment.pairs()).extracting(p -> p.sourceKey() + ">" + p.targetKey()).contains("size_name>size_name", "item_name>item_name");
        assertThat(cloneFindings(edited)).extracting(f -> (String) f.get("field")).containsExactlyInAnyOrder("title", "bullet_points[0]", "description");
        assertThat(stateOf(edited)).isEqualTo("WARNING");
    }

    @Test
    void anEtsyCloneWhoseCategoryIsAnotherKindOfProductButWhoseTitleStillNamesTheOldOneIsReported() {
        Team team = team();
        serve("e", "Qualmark");
        SellerAccount shop = etsyShop(team, "Shop E");
        var original = editor.create(member(team), shop.id(), form("E-1", "Funny Cat Mug", "Home > Mugs"));
        Listing clone = clone(team, original, shop, "-C");

        Listing edited = save(team, clone, Map.of("category", "Clothing > T-Shirts"));
        // the title and the first tag still name the mug
        assertThat(cloneFindings(edited)).extracting(f -> (String) f.get("field")).containsExactlyInAnyOrder("title", "tags[0]");
        Map<String, Object> inTitle = cloneFindings(edited).stream().filter(f -> "title".equals(f.get("field"))).findFirst().orElseThrow();
        assertThat(((String) inTitle.get("message"))).contains("Home > Mugs").contains("Clothing > T-Shirts");
        assertThat("Funny Cat Mug".substring((Integer) inTitle.get("span_start"), (Integer) inTitle.get("span_end"))).isEqualTo("Mug");

        // the title and the tag name the new kind of product: nothing left over
        Listing retitled = save(team, clone, Map.of("category", "Clothing > T-Shirts", "title", "Funny Cat T-Shirt", "tags", "t-shirt, gift"));
        assertThat(cloneFindings(retitled)).isEmpty();
        // a category the table does not know is not judged
        Listing unknown = save(team, clone, Map.of("category", "Home > Gadgets", "title", "Funny Cat Mug"));
        assertThat(cloneFindings(unknown)).isEmpty();
    }

    private Map<String, String> form(String sku, String title, String category) {
        Map<String, String> posted = new LinkedHashMap<>();
        posted.put("sku", sku);
        posted.put("title", title);
        posted.put("description", "A plain description.");
        posted.put("tags", "mug, ceramic, gift");
        posted.put("price", "19.99");
        posted.put("quantity", "5");
        posted.put("category", category);
        posted.put("who_made", "i_did");
        posted.put("when_made", "made_to_order");
        return posted;
    }

    @Test
    void aCloneWhoseOriginalIsDeletedIsAuditedAgainOnceAndTheFindingsGo() {
        Team team = team();
        serve("f", "Qualmark");
        SellerAccount shop = amazonShop(team, "Shop F");
        Listing original = original(team, shop);
        Listing clone = save(team, clone(team, original, shop, "-C"), Map.of("size_name", "15oz"));
        assertThat(cloneFindings(clone)).isNotEmpty();
        String before = analysisVersionOf(clone);

        jdbc.update("UPDATE workspace_listing SET deleted_at = now() WHERE id = ? AND client_id = ?", original.id(), team.client().getId());
        audit.request(team.client().getId(), shop.id(), Reason.MANUAL, null, team.adminAccount().getId());
        worker.drain();

        Run run = store.lastFinished(team.client().getId(), shop.id()).orElseThrow();
        assertThat(run.done()).isEqualTo(1);
        assertThat(cloneFindings(clone)).isEmpty();
        assertThat(analysisVersionOf(clone)).isNotEqualTo(before);
        // and once more: nothing to do
        audit.request(team.client().getId(), shop.id(), Reason.MANUAL, null, team.adminAccount().getId());
        worker.drain();
        assertThat(store.lastFinished(team.client().getId(), shop.id()).orElseThrow().done()).isZero();
    }

    @Test
    void aCloneIsAuditedAgainWhenItsOriginalChangesAndAListingThatIsNoCloneNeverIs() {
        Team team = team();
        serve("g", "Qualmark");
        SellerAccount shop = amazonShop(team, "Shop G");
        Listing original = original(team, shop);
        Listing cloned = clone(team, original, shop, "-C");
        audit();
        Listing clone = save(team, cloned, Map.of("size_name", "15oz"));
        audit.request(team.client().getId(), shop.id(), Reason.MANUAL, null, team.adminAccount().getId());
        worker.drain();
        Run quiet = store.lastFinished(team.client().getId(), shop.id()).orElseThrow();
        assertThat(quiet.done()).isZero();
        assertThat(quiet.skipped()).isEqualTo(2);

        // the original is edited: the audit hears the original only (ListingSaved), the clone is read again by the next run
        save(team, original, Map.of("color_name", "Blue"));
        audit.request(team.client().getId(), shop.id(), Reason.MANUAL, null, team.adminAccount().getId());
        worker.drain();

        Run run = store.lastFinished(team.client().getId(), shop.id()).orElseThrow();
        assertThat(run.done()).isEqualTo(1);
        assertThat(run.skipped()).isEqualTo(1);
        assertThat(cloneFindings(clone)).isNotEmpty();
    }

    @Test
    void theAnalysisVersionOfAListingThatIsNoCloneIsTheOneItAlwaysHadAndAClonesHoldsThePack() {
        Team team = team();
        serve("h", "Qualmark");
        SellerAccount shop = amazonShop(team, "Shop H");
        Listing original = original(team, shop);
        Listing clone = clone(team, original, shop, "-C");
        audit();
        Template template = templates.find(team.client().getId(), original.templateId()).orElseThrow();
        var context = contexts.of(team.client().getId(), "AMAZON");
        var scope = auditor.scopeFields(Marketplace.AMAZON, template);

        assertThat(context.cloneLeftover()).isNotNull().startsWith("clone-leftover-2026-12:");
        assertThat(analysisVersionOf(original)).isEqualTo(context.analysisVersion(scope));
        assertThat(analysisVersionOf(clone)).isNotEqualTo(context.analysisVersion(scope))
                .isEqualTo(context.cloneAnalysisVersion(scope, original.templateId() + ":" + original.contentHash()));
    }
}
