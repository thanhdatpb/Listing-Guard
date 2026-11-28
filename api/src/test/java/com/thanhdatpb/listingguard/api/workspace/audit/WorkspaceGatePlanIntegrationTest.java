package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.workspace.ListingEditor;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService.Result;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * A client whose plan has no trademark pack (ADR-063 §1): the trademark part is refused by the plan, the audit stores {@code NO_PLAN}, and the
 * gate lets the listing go while saying that it was not checked for trademark. The plans are on here, so a new client is on the FREE plan,
 * which has the listing rules and not the trademark pack, even when a catalog is served.
 */
@SpringBootTest(properties = {
        "listing-guard.plans.enabled=true",
        "listing-guard.workspace.enabled=true",
        "listing-guard.workspace.import-max-bytes=64KB",
        "listing-guard.workspace.max-template-bytes=64KB",
        "spring.servlet.multipart.max-file-size=25MB",
        "spring.servlet.multipart.max-request-size=60MB",
        "listing-guard.workspace.audit.enabled=true",
        "listing-guard.workspace.audit.auto-start=false",
        "listing-guard.workspace.alerts.email.enabled=true",
        "listing-guard.workspace.alerts.email.daily-cap=2",
        "listing-guard.workspace.alerts.email.excerpt-chars=12",
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test",
        "listing-guard.trademark.activation.follow=true",
        "listing-guard.trademark.activation.refresh-interval=1h"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WorkspaceGatePlanIntegrationTest extends AuditIntegrationBase {

    @Autowired
    ListingEditor editor;
    @Autowired
    ListingStatusService statusService;
    @Autowired
    WorkspaceAccess access;

    @Test
    void aPlanWithoutTheTrademarkPackIsNotHeldBackAndTheListingIsSaidToBeUncheckedForTrademark() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop P");
        serve("p", "Qualmark");
        Map<String, String> posted = new LinkedHashMap<>();
        posted.put("sku", "P-1");
        posted.put("title", "Plain ceramic mug");
        posted.put("description", "A plain description.");
        posted.put("tags", "mug, ceramic, gift");
        posted.put("price", "19.99");
        posted.put("quantity", "5");
        posted.put("category", "Mugs");
        posted.put("who_made", "i_did");
        posted.put("when_made", "made_to_order");
        var member = access.actor(team.member()).orElseThrow();

        Listing l = editor.create(member, shop.id(), posted);

        assertThat(jdbc.queryForObject("SELECT trademark_reason FROM workspace_listing_audit WHERE listing_id = ?", String.class, l.id()))
                .isEqualTo("NO_PLAN");
        var verdict = statusService.verdicts(team.client().getId(), List.of(l.id())).get(l.id());
        assertThat(verdict.blocks()).isEmpty();
        assertThat(verdict.trademarkChecked()).isFalse();
        assertThat(statusService.markReady(member, List.of(l.id()), false, null)).singleElement()
                .satisfies(o -> assertThat(o.result()).isEqualTo(Result.MOVED));
        assertThat(listings.find(team.client().getId(), l.id()).orElseThrow().status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
    }
}
