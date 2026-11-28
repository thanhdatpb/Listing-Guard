package com.thanhdatpb.listingguard.api.workspace.audit;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.thanhdatpb.listingguard.api.console.ConsoleLocaleResolver;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.ListingEditor;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceExports;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * "Needs to be exported again" on the pages (ADR-064 §4), in the classic console too: an Etsy listing that was posted and then edited keeps its
 * status, shows the word and a shape on its page and in the list in both languages, and the mark goes when the text is the posted text again.
 * The end state in the database is checked first; the pages only say what the database holds.
 */
class WorkspaceReexportConsoleIntegrationTest extends AuditIntegrationBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    ListingEditor editor;
    @Autowired
    ListingStatusService statusService;
    @Autowired
    WorkspaceExports exports;
    @Autowired
    WorkspaceAccess access;

    private String page(ConsoleUser user, String path, String language) throws Exception {
        var result = mvc.perform(get(path).with(signedIn(user)).cookie(new Cookie(ConsoleLocaleResolver.COOKIE, language))).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Map<String, String> form(String sku, String title) {
        Map<String, String> posted = new LinkedHashMap<>();
        posted.put("sku", sku);
        posted.put("title", title);
        posted.put("description", "A plain description.");
        posted.put("tags", "mug, ceramic, gift");
        posted.put("price", "19.99");
        posted.put("quantity", "5");
        posted.put("category", "Mugs");
        posted.put("who_made", "i_did");
        posted.put("when_made", "made_to_order");
        return posted;
    }

    @Test
    void anEditedPostedListingShowsTheMarkOnItsPageAndInTheListAndLosesItWhenTheTextIsBack() throws Exception {
        Team team = team();
        Actor member = access.actor(team.member()).orElseThrow();
        SellerAccount shop = etsyShop(team, "Shop Again");
        Listing l = editor.create(member, shop.id(), form("A-1", "Plain ceramic mug"));
        audit();
        assertThat(statusService.markReady(member, List.of(l.id()), false, null)).extracting(ListingStatusService.Outcome::result)
                .containsExactly(ListingStatusService.Result.MOVED);
        assertThat(exports.markPosted(member, l.id()).result()).isEqualTo(WorkspaceExports.PostResult.POSTED);
        String path = "/console/workspace/listings/" + l.id();
        assertThat(page(team.member(), path, "en")).doesNotContain("id=\"needs-reexport\"").doesNotContain("id=\"reexport-note\"");
        assertThat(page(team.member(), "/console/workspace", "en")).doesNotContain("id=\"reexport-" + l.id());

        Listing live = listings.find(team.client().getId(), l.id()).orElseThrow();
        Map<String, String> edited = new LinkedHashMap<>(live.data());
        edited.put("title", "Plain ceramic mug, new title");
        editor.save(member, live.id(), live.version(), edited);

        // the database first: the status is the status it was, the posted hash is not the content hash
        assertThat(jdbc.queryForObject("SELECT status FROM workspace_listing WHERE id = ?", String.class, l.id())).isEqualTo("LIVE");
        assertThat(jdbc.queryForObject("SELECT exported_hash <> content_hash FROM workspace_listing WHERE id = ?", Boolean.class, l.id())).isTrue();
        assertThat(page(team.member(), path, "en")).contains("id=\"needs-reexport\"", "Needs to be exported again", "id=\"reexport-note\"");
        assertThat(page(team.member(), path, "vi")).contains("id=\"needs-reexport\"", "Cần xuất lại");
        assertThat(page(team.member(), "/console/workspace", "vi")).contains("id=\"reexport-" + l.id() + "\"", "Cần xuất lại");
        assertThat(page(team.admin(), "/console/workspace", "en")).contains("id=\"reexport-" + l.id() + "\"");

        // the posted text is back: the listing is what it was when it went out
        Listing again = listings.find(team.client().getId(), l.id()).orElseThrow();
        Map<String, String> back = new LinkedHashMap<>(again.data());
        back.put("title", "Plain ceramic mug");
        editor.save(member, again.id(), again.version(), back);
        assertThat(jdbc.queryForObject("SELECT exported_hash = content_hash FROM workspace_listing WHERE id = ?", Boolean.class, l.id())).isTrue();
        assertThat(page(team.member(), path, "en")).doesNotContain("id=\"needs-reexport\"");
        assertThat(listings.find(team.client().getId(), l.id()).orElseThrow().status()).isEqualTo(ListingStatus.LIVE);
    }
}
