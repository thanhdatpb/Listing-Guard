package com.thanhdatpb.listingguard.api.workspace.audit;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.thanhdatpb.listingguard.api.console.ConsoleLocaleResolver;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.Progress;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Reason;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The pages of the background audit (ADR-061): the marked text of the detail page (a word, an icon, an underline and a tooltip, never only a
 * colour), the state column and its filter, the progress of a run (x of y, refreshed while it works), the history, the home card, who may do
 * what, and another client's 404, in both languages. Synthetic data only.
 */
class WorkspaceAuditConsoleIntegrationTest extends AuditIntegrationBase {

    @Autowired
    MockMvc mvc;

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private String page(ConsoleUser user, String path, String language) throws Exception {
        MockHttpServletRequestBuilder request = get(path).with(signedIn(user));
        if (language != null) {
            request = request.cookie(new Cookie(ConsoleLocaleResolver.COOKIE, language));
        }
        MvcResult result = perform(request);
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private record World(Team team, SellerAccount shop, Template template, Listing hoodie, Listing mug) {
    }

    /** A hoodie with a mark that the second catalog adds, and a mug that is clean; both already audited under the second catalog. */
    private World world() {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop A");
        Template template = etsyTemplate(team, shop);
        serve("a", "Qualmark");
        Listing hoodie = insert(team, shop, template, "H-1", fields("  Cool   ZORVEX!!  Hoodie, soft ", "A plain description of a hoodie."),
                team.memberAccount().getId());
        Listing mug = insert(team, shop, template, "M-1", fields("Plain ceramic mug", "A plain description of a mug."), team.memberAccount().getId());
        audit();
        serve("b", "Qualmark", "Zorvex");
        audit();
        return new World(team, shop, template, hoodie, mug);
    }

    @Test
    void theDetailPageMarksTheWordWithAnIconAnUnderlineAndATooltipAndKeepsTheSpaces() throws Exception {
        World w = world();

        String html = page(w.team().admin(), "/console/workspace/listings/" + w.hoodie().id(), null);

        assertThat(html).contains("id=\"audit-content\"").contains("New risk");
        // the piece around the word keeps the spaces and the punctuation the seller typed; the word is inside the mark
        assertThat(html).contains("<span>  Cool   </span>").contains("<span>!!  Hoodie, soft </span>");
        Pattern mark = Pattern.compile("<span class=\"lg-hl lg-hl--severe\"><mark class=\"lg-hl__mark\" tabindex=\"0\" aria-describedby=\"(lg-tip-\\d+)\">"
                + "<svg class=\"lg-icon lg-hl__icon\"[^>]*><use href=\"#lg-i-risk-severe\"/></svg><span>ZORVEX</span></mark>"
                + "<span class=\"lg-hl__tip\" role=\"tooltip\" id=\"(lg-tip-\\d+)\">");
        var matcher = mark.matcher(html);
        assertThat(matcher.find()).as("the marked word").isTrue();
        assertThat(matcher.group(1)).isEqualTo(matcher.group(2));
        // the tooltip says the level, the source and the mark; the table below says it too
        assertThat(html).contains("Zorvex").contains("Trademark catalog").contains("id=\"audit-table\"").contains("data-new=\"true\"");
        // the audit's parts carry no inline style and no colour of their own: the classes take them from the tokens
        String audit = html.substring(html.indexOf("id=\"audit-content\""), html.indexOf("id=\"audit-table\""));
        assertThat(audit).doesNotContain("style=").doesNotContain("<style").doesNotContainPattern("#[0-9a-fA-F]{3,8}\"");
    }

    @Test
    void theSamePageIsInVietnameseAndAnotherLanguageChangesNothingElse() throws Exception {
        World w = world();

        String vi = page(w.team().admin(), "/console/workspace/listings/" + w.hoodie().id(), "vi");

        assertThat(vi).contains("Rủi ro mới").contains("Nội dung đã rà").contains("Catalog nhãn hiệu").contains("<span>ZORVEX</span></mark>");
        assertThat(vi).doesNotContain("New risk").doesNotContain("Trademark catalog");
    }

    @Test
    void whatTheSellerTypedIsNeverMarkup() throws Exception {
        Team team = team();
        SellerAccount shop = etsyShop(team, "Shop H");
        Template template = etsyTemplate(team, shop);
        serve("h", "Qualmark");
        Listing hostile = insert(team, shop, template, "X-1", fields("<img src=x onerror=alert(1)> <b>bold</b> mug", "<script>alert(1)</script>"), null);
        audit();

        String html = page(team.admin(), "/console/workspace/listings/" + hostile.id(), null);

        assertThat(html).doesNotContain("<img src=x").doesNotContain("<script>alert(1)").doesNotContain("<b>bold</b>");
        assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;").contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void theListHasAStateColumnAndAFilterThatWorkAndAnUnknownFilterIsNoFilter() throws Exception {
        World w = world();

        String all = page(w.team().admin(), "/console/workspace", null);
        assertThat(all).contains("lg-chip--audit-new_risk").contains("New risk").contains("H-1").contains("M-1").contains("name=\"check\"");

        String risky = page(w.team().admin(), "/console/workspace?check=NEW_RISK", null);
        assertThat(risky).contains("H-1").doesNotContain("M-1");
        String clean = page(w.team().admin(), "/console/workspace?check=INCOMPLETE", null);
        assertThat(clean).doesNotContain("H-1");
        assertThat(page(w.team().admin(), "/console/workspace?check=bogus", null)).contains("H-1").contains("M-1");
        assertThat(page(w.team().admin(), "/console/workspace?check=NEW_RISK", "vi")).contains("Rủi ro mới").contains("Trạng thái rà");
    }

    @Test
    void theAuditPageShowsProgressHistoryAndTheCountsAndTheProgressFragmentKeepsAskingOnlyWhileARunWorks() throws Exception {
        World w = world();

        String done = page(w.team().admin(), "/console/workspace/audit", null);
        assertThat(done).contains("id=\"audit-progress\"").contains("aria-busy=\"false\"").doesNotContain("every 3s").contains("Shop A").contains("2/2")
                .contains("id=\"audit-history\"").contains("New catalog").contains("id=\"count-new\"");

        // a run that waits and one that works: the block asks again every few seconds and says x of y
        UUID runId = audit.request(w.team().client().getId(), w.shop().id(), Reason.MANUAL, null, w.team().adminAccount().getId()).orElseThrow();
        String waiting = page(w.team().admin(), "/console/workspace/audit/progress", null);
        assertThat(waiting).contains("hx-trigger=\"every 3s\"").contains("aria-busy=\"true\"").contains("Waiting");
        var taken = store.claim(java.time.Duration.ofMinutes(2)).orElseThrow();
        assertThat(taken.id()).isEqualTo(runId);
        store.heartbeat(w.team().client().getId(), runId, new Progress(10, 3, 0, 0, 0, 0), java.time.Duration.ofMinutes(2));
        String working = page(w.team().admin(), "/console/workspace/audit/progress", null);
        assertThat(working).contains("3/10").contains("value=\"30\"").contains("Running").contains("every 3s");
        store.finish(w.team().client().getId(), runId, new Progress(10, 10, 0, 0, 0, 0), null, "k", null, null, null, null);
        assertThat(page(w.team().admin(), "/console/workspace/audit/progress", null)).doesNotContain("every 3s").contains("aria-busy=\"false\"");
        assertThat(page(w.team().admin(), "/console/workspace/audit", "vi")).contains("Tiến độ theo tài khoản bán").contains("Lịch sử các lượt rà");
    }

    @Test
    void onlyAnAdminStartsAnAuditAndAnotherClientsAccountIsNotFound() throws Exception {
        World w = world();
        Team other = team();
        String path = "/console/workspace/audit/accounts/" + w.shop().id();

        assertThat(perform(post(path).with(signedIn(w.team().member())).with(csrf())).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(post(path).with(signedIn(other.admin())).with(csrf())).getResponse().getStatus()).isEqualTo(404);
        assertThat(store.hasOpenRun(w.team().client().getId(), w.shop().id())).isFalse();
        MvcResult started = perform(post(path).with(signedIn(w.team().admin())).with(csrf()));
        assertThat(started.getResponse().getStatus()).isEqualTo(302);
        assertThat(store.hasOpenRun(w.team().client().getId(), w.shop().id())).isTrue();
        // an htmx press answers with the progress block, which now asks again
        MvcResult htmx = perform(post(path).with(signedIn(w.team().admin())).with(csrf()).header("HX-Request", "true"));
        assertThat(htmx.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("id=\"audit-progress\"").contains("every 3s");
        // the other client's page does not list this client's account
        assertThat(page(other.admin(), "/console/workspace/audit", null)).doesNotContain("Shop A");
        assertThat(page(other.admin(), "/console/workspace/audit/progress", null)).doesNotContain("Shop A");
    }

    @Test
    void aListingOfAnotherClientIsNotFoundForTheDetailAndForTheMarkAsSeen() throws Exception {
        World w = world();
        Team other = team();

        assertThat(perform(get("/console/workspace/listings/" + w.hoodie().id()).with(signedIn(other.admin()))).getResponse().getStatus()).isEqualTo(404);
        assertThat(perform(post("/console/workspace/audit/listings/" + w.hoodie().id() + "/ack").with(signedIn(other.admin())).with(csrf()))
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(page(other.admin(), "/console/workspace?check=NEW_RISK", null)).doesNotContain("H-1");
    }

    @Test
    void theMemberWhoOwnsTheListingMarksItSeenAndAnotherMemberMayNot() throws Exception {
        World w = world();
        ConsoleUser stranger = new ConsoleUser("stranger-" + UUID.randomUUID() + "@example.test", w.team().client().getId());
        console.saveAndFlush(new com.thanhdatpb.listingguard.api.console.ConsoleAccount(stranger.email(), w.team().client().getId(),
                com.thanhdatpb.listingguard.api.console.ConsoleRole.MEMBER));
        String path = "/console/workspace/audit/listings/" + w.hoodie().id() + "/ack";

        assertThat(perform(post(path).with(signedIn(stranger)).with(csrf())).getResponse().getStatus()).isEqualTo(403);
        assertThat(stateOf(w.hoodie())).isEqualTo("NEW_RISK");
        assertThat(perform(post(path).with(signedIn(w.team().member())).with(csrf())).getResponse().getStatus()).isEqualTo(302);
        assertThat(stateOf(w.hoodie())).isNotEqualTo("NEW_RISK");
        assertThat(page(w.team().member(), "/console/workspace/listings/" + w.hoodie().id(), null)).doesNotContain("id=\"ack-form\"");
    }

    @Test
    void theHomeCardCountsTheWholeWorkspaceForAnAdminAndOnlyOwnListingsForAMember() throws Exception {
        World w = world();
        Team lonely = team();

        assertThat(page(w.team().admin(), "/console", null)).contains("id=\"audit-home-card\"").containsPattern("id=\"audit-home-count\">1<");
        assertThat(page(w.team().member(), "/console", null)).containsPattern("id=\"audit-home-count\">1<");
        // another member of the same client who owns nothing sees none; another client sees none
        ConsoleUser stranger = new ConsoleUser("s2-" + UUID.randomUUID() + "@example.test", w.team().client().getId());
        console.saveAndFlush(new com.thanhdatpb.listingguard.api.console.ConsoleAccount(stranger.email(), w.team().client().getId(),
                com.thanhdatpb.listingguard.api.console.ConsoleRole.MEMBER));
        assertThat(page(stranger, "/console", null)).contains("id=\"audit-home-none\"").doesNotContain("id=\"audit-home-count\"");
        assertThat(page(lonely.admin(), "/console", null)).contains("id=\"audit-home-none\"");
        assertThat(page(w.team().admin(), "/console", "vi")).contains("Listing có rủi ro mới");
    }

    @Test
    void theMenuHasTheAuditPageOnlyWhileTheAuditIsOn() throws Exception {
        World w = world();

        assertThat(page(w.team().admin(), "/console", null)).contains("/console/workspace/audit").contains("lg-i-audit");
    }
}
