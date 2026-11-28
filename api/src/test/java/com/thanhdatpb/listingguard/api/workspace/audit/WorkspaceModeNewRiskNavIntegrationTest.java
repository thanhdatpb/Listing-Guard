package com.thanhdatpb.listingguard.api.workspace.audit;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleLocaleResolver;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The number "New risks" next to the listing entry of the workspace menu (ADR-064 §2): the reader's own count (an ADMIN: the client's, a MEMBER:
 * the listings they are responsible for), in both languages, gone once the risk is marked as seen, and never another client's.
 */
@TestPropertySource(properties = "listing-guard.console.mode=workspace")
class WorkspaceModeNewRiskNavIntegrationTest extends AuditIntegrationBase {

    @Autowired
    MockMvc mvc;

    private String home(ConsoleUser user, String language) throws Exception {
        var result = mvc.perform(get("/console/workspace").with(signedIn(user)).cookie(new Cookie(ConsoleLocaleResolver.COOKIE, language))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private ConsoleUser otherMember(Team team) {
        ConsoleUser other = new ConsoleUser("other-" + UUID.randomUUID() + "@example.test", team.client().getId());
        console.saveAndFlush(new ConsoleAccount(other.email(), team.client().getId(), ConsoleRole.MEMBER));
        return other;
    }

    @Test
    void theMenuCountsTheNewRisksOfTheReaderAndTheMarkAsSeenTakesThemAway() throws Exception {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop Nav");
        Template template = etsyTemplate(team, shop);
        Listing risky = insert(team, shop, template, "N-1", fields("Zorvex hoodie soft", "A plain description."), team.memberAccount().getId());
        insert(team, shop, template, "N-2", fields("Plain ceramic mug", "A plain description."), team.memberAccount().getId());
        audit();
        serve("nav", "Zorvex");
        audit();
        ConsoleUser colleague = otherMember(team);

        for (ConsoleUser reader : java.util.List.of(team.admin(), team.member())) {
            assertThat(home(reader, "en")).contains("New risks: 1");
            assertThat(home(reader, "vi")).contains("Rủi ro mới: 1");
        }
        // a colleague who is responsible for nothing has none of their own
        assertThat(home(colleague, "en")).doesNotContain("New risks:");

        // another client never adds to the number
        Team stranger = team();
        assertThat(home(stranger.admin(), "en")).doesNotContain("New risks:");

        mvc.perform(post("/console/workspace/audit/listings/" + risky.id() + "/ack").with(signedIn(team.admin())).with(csrf())).andReturn();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_listing_audit WHERE listing_id = ? AND ack_at IS NOT NULL", Long.class, risky.id()))
                .isEqualTo(1L);
        assertThat(home(team.admin(), "en")).doesNotContain("New risks:");
        assertThat(home(team.member(), "vi")).doesNotContain("Rủi ro mới:");
    }
}
