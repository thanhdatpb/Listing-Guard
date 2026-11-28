package com.thanhdatpb.listingguard.api.workspace;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleLocaleResolver;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.workspace.ImportModels.Params;
import com.thanhdatpb.listingguard.api.workspace.TemplateService.SourceFile;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ExportFormat;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import com.thanhdatpb.listingguard.workspace.sheets.SheetGrid;
import com.thanhdatpb.listingguard.workspace.sheets.SheetReader;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The pages of the editor, the new listing, the clone, the history and the status (ADR-062), with the flag on and the background audit off:
 * what each page shows, what each form does, who may do what, another client's 404 and both languages. The files are synthetic and no
 * Amazon template was read to write them.
 */
@SpringBootTest(properties = {
        "listing-guard.workspace.enabled=true",
        // the gate is switched off here: these tests are about the editor, the clone and the status moves, not about what holds a listing back
        // (ADR-063 section 1: with the audit off and the gate on, every listing is unchecked and held back; see WorkspaceGateIntegrationTest)
        "listing-guard.workspace.block-on-error=false",
        "spring.servlet.multipart.max-file-size=25MB",
        "spring.servlet.multipart.max-request-size=60MB",
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleWorkspaceEditorIntegrationTest {

    private record Team(Client client, ConsoleUser admin, ConsoleUser member, ConsoleUser other) {
    }

    private static final Pattern STAGE = Pattern.compile("stage=([0-9a-f-]{36})");

    @Autowired
    MockMvc mvc;
    @Autowired
    ClientProvisioning provisioning;
    @Autowired
    ConsoleAccountRepository console;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    WorkspaceAccess access;
    @Autowired
    WorkspaceAccounts accounts;
    @Autowired
    WorkspaceListings listings;
    @Autowired
    ListingImportService imports;
    @Autowired
    ListingEditor editor;
    @Autowired
    WorkspaceProperties properties;

    @MockitoBean
    JavaMailSender mailSender;

    private Team team() {
        Client client = provisioning.createClient("wx" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        String id = UUID.randomUUID().toString();
        ConsoleUser admin = new ConsoleUser("admin-" + id + "@example.test", client.getId());
        ConsoleUser member = new ConsoleUser("member-" + id + "@example.test", client.getId());
        ConsoleUser other = new ConsoleUser("other-" + id + "@example.test", client.getId());
        console.saveAndFlush(new ConsoleAccount(admin.email(), client.getId(), ConsoleRole.ADMIN));
        console.saveAndFlush(new ConsoleAccount(member.email(), client.getId(), ConsoleRole.MEMBER));
        console.saveAndFlush(new ConsoleAccount(other.email(), client.getId(), ConsoleRole.MEMBER));
        return new Team(client, admin, member, other);
    }

    private Actor actor(ConsoleUser user) {
        return access.actor(user).orElseThrow();
    }

    /** An Amazon account with the synthetic family E-P1, E-P1-S, E-P1-M and E-S2, responsible person: the member. */
    private SellerAccount account(Team team, String prefix) throws IOException {
        SellerAccount account = accounts.create(team.client().getId(), Marketplace.AMAZON, "Shop " + prefix, null, ExportFormat.XLSM);
        byte[] bytes = WorkspaceFixtures.flatFile(prefix);
        SheetGrid grid = SheetReader.read(bytes, "Template", properties.limits(false));
        Params params = params(team, account, grid, Set.of());
        imports.commit(actor(team.admin()), params, grid, new SourceFile(SheetReader.detect(bytes, properties.limits(false)), "f", bytes), null);
        return account;
    }

    private Params params(Team team, SellerAccount account, SheetGrid grid, Set<Integer> overwrite) {
        var view = imports.mappingView(actor(team.admin()), account.id(), TemplateKind.AMAZON_BULK, grid, 3);
        Map<Integer, String> targets = new LinkedHashMap<>();
        view.columns().forEach(c -> {
            if (!c.target().isEmpty()) {
                targets.put(c.index(), c.target());
            }
        });
        return new Params(account.id(), TemplateKind.AMAZON_BULK, actor(team.member()).accountId(), ListingStatus.DRAFT, "Template", 3, 4, targets,
                Map.of(), overwrite, Set.of());
    }

    private Listing listing(Team team, SellerAccount account, String sku) {
        UUID id = jdbc.queryForObject("SELECT id FROM workspace_listing WHERE client_id = ? AND account_id = ? AND sku = ? AND deleted_at IS NULL",
                UUID.class, team.client().getId(), account.id(), sku);
        return listings.find(team.client().getId(), id).orElseThrow();
    }

    private static MockHttpServletRequestBuilder in(MockHttpServletRequestBuilder request, String language) {
        return language == null ? request : request.cookie(new Cookie(ConsoleLocaleResolver.COOKIE, language));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, int status) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(request.toString()).isEqualTo(status);
        return result;
    }

    private String page(MockHttpServletRequestBuilder request, int status) throws Exception {
        return perform(request, status).getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String edit(UUID id) {
        return "/console/workspace/listings/" + id + "/edit";
    }

    // ------------------------------------------------------------------ the editor

    @Test
    void theEditorShowsTheColumnsGroupedByFieldWithTheirNamesAndTheLimitsOfTheRuleset() throws Exception {
        Team team = team();
        SellerAccount account = account(team, "E-");
        Listing child = listing(team, account, "E-P1-S");

        String html = page(get(edit(child.id())).with(signedIn(team.admin())), 200);

        assertThat(html).contains("id=\"editor-form\"").contains("id=\"edit-save\"").contains("<script src=\"/console/editor.js\" defer>")
                .containsPattern("<fieldset class=\"lg-edit-group\" data-target=\"bullet_points\"")
                .containsPattern("<fieldset class=\"lg-edit-group\" data-target=\"title\"")
                .contains("name=\"v.bullet_point1\"").contains("name=\"v.bullet_point2\"").contains("name=\"v.generic_keywords\"")
                // a column no field was given is folded away and still there
                .contains("id=\"edit-passthrough\"").contains("name=\"v.x_custom_flag\"")
                // the column as the file wrote it, and the counters from the active ruleset
                .contains(">item_name</code>").contains("data-counter-for=").containsPattern("data-mode=\"chars\"")
                .containsPattern("data-limit=\"\\d+\"").contains("name=\"version\" value=\"" + child.version() + "\"");
        // the SKU is shown and cannot be changed
        assertThat(html).containsPattern("name=\"v.item_sku\"[^>]*readonly");
        // the result of the latest check is not here while the audit is off
        assertThat(html).doesNotContain("id=\"audit-content\"");
    }

    @Test
    void aSaveGoesThroughTheFormAndASecondOneWithNothingNewSaysSo() throws Exception {
        Team team = team();
        SellerAccount account = account(team, "E-");
        Listing child = listing(team, account, "E-P1-S");

        MvcResult saved = perform(post(edit(child.id())).with(signedIn(team.admin())).with(csrf()).param("version", Long.toString(child.version()))
                .param("v.item_name", "A new title from the form").param("v.bullet_point2", "Warmer"), 302);
        assertThat(saved.getResponse().getRedirectedUrl()).isEqualTo(edit(child.id()));
        assertThat(saved.getFlashMap().get("notice")).isNotNull();
        Listing after = listing(team, account, "E-P1-S");
        assertThat(after.title()).isEqualTo("A new title from the form");
        assertThat(after.fields().get("bullet_points")).extracting(n -> n.asText()).containsExactly("Soft", "Warmer");
        assertThat(after.version()).isEqualTo(child.version() + 1);

        MvcResult again = perform(post(edit(child.id())).with(signedIn(team.admin())).with(csrf()).param("version", Long.toString(after.version()))
                .param("v.item_name", "A new title from the form"), 302);
        assertThat(again.getFlashMap().get("notice")).isEqualTo("Nothing was changed, so no new version was written.");
        assertThat(listing(team, account, "E-P1-S").version()).isEqualTo(after.version());
    }

    @Test
    void aStaleSaveIsRefusedWithAnAccountOfWhatChangedAndAWayToReload() throws Exception {
        Team team = team();
        SellerAccount account = account(team, "E-");
        Listing opened = listing(team, account, "E-S2");
        Map<String, String> theirs = new LinkedHashMap<>(opened.data());
        theirs.put("product_description", "Someone else wrote this");
        editor.save(actor(team.admin()), opened.id(), opened.version(), theirs);

        String html = page(post(edit(opened.id())).with(signedIn(team.member())).with(csrf()).param("version", Long.toString(opened.version()))
                .param("v.item_name", "My title"), 409);

        assertThat(html).contains("id=\"edit-conflict\"").contains("id=\"edit-conflict-table\"").contains("Someone else wrote this")
                .contains("id=\"edit-reload\"").contains("My title").contains("Synthetic mug");
        assertThat(listing(team, account, "E-S2").title()).isEqualTo("Synthetic mug");
    }

    @Test
    void aMemberReadsAnotherMembersListingWithACloneButtonAndCannotSaveIt() throws Exception {
        Team team = team();
        SellerAccount account = account(team, "E-");
        Listing theirs = listing(team, account, "E-S2");
        jdbc.update("UPDATE workspace_listing SET owner_account_id = ? WHERE id = ?", actor(team.other()).accountId(), theirs.id());

        String html = page(get(edit(theirs.id())).with(signedIn(team.member())), 200);
        assertThat(html).contains("id=\"edit-readonly\"").contains("id=\"edit-clone-mine\"").doesNotContain("id=\"edit-save\"")
                .containsPattern("name=\"v.item_name\"[^>]*readonly");
        perform(post(edit(theirs.id())).with(signedIn(team.member())).with(csrf()).param("version", Long.toString(theirs.version()))
                .param("v.item_name", "Not allowed"), 403);
        assertThat(listing(team, account, "E-S2").title()).isEqualTo("Synthetic mug");

        // the clone page, then the clone, then the member is the one responsible
        String preview = page(post("/console/workspace/clone").with(signedIn(team.member())).with(csrf()).param("listing", theirs.id().toString())
                .param("suffix", "-MINE"), 200);
        assertThat(preview).contains("id=\"clone-items\"").contains("E-S2-MINE").doesNotContain("disabled=\"disabled\" id=\"clone-commit\"");
        MvcResult done = perform(post("/console/workspace/clone/commit").with(signedIn(team.member())).with(csrf()).param("listing", theirs.id().toString())
                .param("suffix", "-MINE").param("target", account.id().toString()), 302);
        Listing mine = listing(team, account, "E-S2-MINE");
        assertThat(done.getResponse().getRedirectedUrl()).isEqualTo(edit(mine.id()));
        assertThat(mine.ownerAccountId()).isEqualTo(actor(team.member()).accountId());
        assertThat(mine.clonedFrom()).isEqualTo(theirs.id());
    }

    @Test
    void theCloneRefusesATakenSkuAndWritesNothing() throws Exception {
        Team team = team();
        SellerAccount account = account(team, "E-");
        Listing single = listing(team, account, "E-S2");
        long before = listings.count(team.client().getId(), account.id());

        String html = page(post("/console/workspace/clone/preview").with(signedIn(team.member())).with(csrf()).param("listing", single.id().toString())
                .param("target", account.id().toString()), 200);
        assertThat(html).contains("data-ok=\"false\"").contains("This account already has a listing with this SKU.").containsPattern("id=\"clone-commit\"[^>]*disabled");

        String refused = page(post("/console/workspace/clone/commit").with(signedIn(team.member())).with(csrf()).param("listing", single.id().toString())
                .param("target", account.id().toString()), 422);
        assertThat(refused).contains("id=\"clone-form\"").contains("The clone has problems");
        assertThat(listings.count(team.client().getId(), account.id())).isEqualTo(before);
    }

    @Test
    void aFamilyCloneShowsTheChildrenAndTheNewParent() throws Exception {
        Team team = team();
        SellerAccount account = account(team, "E-");
        Listing parent = listing(team, account, "E-P1");

        String html = page(post("/console/workspace/clone/preview").with(signedIn(team.admin())).with(csrf()).param("listing", parent.id().toString())
                .param("target", account.id().toString()).param("family", "1").param("suffix", "-C").param("numberFrom", "1").param("numberWidth", "2"), 200);

        assertThat(html).contains("E-P1-C01").contains("E-P1-M-C02").contains("E-P1-S-C03").contains("3 listings to clone");
    }

    // ------------------------------------------------------------------ new

    @Test
    void aNewListingIsChosenByAccountFilledInAndCreatedAndASecondTimeIsRefusedWithWhatWasTyped() throws Exception {
        Team team = team();
        SellerAccount etsy = accounts.create(team.client().getId(), Marketplace.ETSY, "Etsy Shop", null, ExportFormat.XLSM);

        String choose = page(get("/console/workspace/new").with(signedIn(team.member())), 200);
        assertThat(choose).contains("id=\"workspace-new-accounts\"").contains("Etsy Shop").contains("/console/workspace/new?account=" + etsy.id());
        String form = page(get("/console/workspace/new").param("account", etsy.id().toString()).with(signedIn(team.member())), 200);
        assertThat(form).contains("name=\"v.sku\"").contains("name=\"v.title\"").contains("name=\"v.tags\"").contains("name=\"account\" value=\"" + etsy.id() + "\"")
                .doesNotContain("name=\"version\"").doesNotContain("readonly");

        MvcResult created = perform(post("/console/workspace/new").with(signedIn(team.member())).with(csrf()).param("account", etsy.id().toString())
                .param("v.sku", "ET-1").param("v.title", "A new mug").param("v.tags", "mug, gift"), 302);
        UUID id = jdbc.queryForObject("SELECT id FROM workspace_listing WHERE client_id = ? AND sku = 'ET-1'", UUID.class, team.client().getId());
        assertThat(created.getResponse().getRedirectedUrl()).isEqualTo(edit(id));
        assertThat(listings.find(team.client().getId(), id).orElseThrow().ownerAccountId()).isEqualTo(actor(team.member()).accountId());

        String duplicate = page(post("/console/workspace/new").with(signedIn(team.member())).with(csrf()).param("account", etsy.id().toString())
                .param("v.sku", "ET-1").param("v.title", "Typed again"), 422);
        assertThat(duplicate).contains("This account already has a listing with this SKU.").contains("value=\"Typed again\"");
    }

    // ------------------------------------------------------------------ history and status

    @Test
    void theHistoryPageComparesTwoVersionsAndRestoresOne() throws Exception {
        Team team = team();
        SellerAccount account = account(team, "E-");
        Listing v0 = listing(team, account, "E-S2");
        Map<String, String> changed = new LinkedHashMap<>(v0.data());
        changed.put("item_name", "Second title");
        editor.save(actor(team.admin()), v0.id(), v0.version(), changed);
        Listing v1 = listing(team, account, "E-S2");

        String history = page(get("/console/workspace/listings/" + v1.id() + "/history").param("from", Long.toString(v0.version()))
                .param("to", Long.toString(v1.version())).with(signedIn(team.admin())), 200);
        assertThat(history).contains("id=\"history-table\"").contains("id=\"history-changes-table\"").contains("Synthetic mug").contains("Second title")
                .contains("Restore");

        MvcResult restored = perform(post("/console/workspace/listings/" + v1.id() + "/restore").with(signedIn(team.admin())).with(csrf())
                .param("version", Long.toString(v1.version())).param("restore", Long.toString(v0.version())), 302);
        assertThat(restored.getResponse().getRedirectedUrl()).isEqualTo(edit(v1.id()));
        assertThat(listing(team, account, "E-S2").title()).isEqualTo("Synthetic mug");
        assertThat(listing(team, account, "E-S2").version()).isEqualTo(v1.version() + 1);
    }

    @Test
    void theStatusPageShowsWhatTheCheckSaysAndMovesTheDrafts() throws Exception {
        Team team = team();
        SellerAccount account = account(team, "E-");
        Listing single = listing(team, account, "E-S2");

        String check = page(post("/console/workspace/status").with(signedIn(team.member())).with(csrf()).param("listing", single.id().toString()), 200);
        assertThat(check).contains("id=\"status-table\"").contains("id=\"status-gate-off\"").contains("id=\"status-apply\"").doesNotContain("id=\"status-open\"");

        String done = page(post("/console/workspace/status").with(signedIn(team.member())).with(csrf()).param("listing", single.id().toString())
                .param("step", "apply"), 200);
        assertThat(done).contains("Moved to ready to export");
        assertThat(listing(team, account, "E-S2").status()).isEqualTo(ListingStatus.READY_TO_EXPORT);
    }

    // ------------------------------------------------------------------ the list, the detail and the others

    @Test
    void theListOffersASelectionAndTheDetailPageLinksToTheEditor() throws Exception {
        Team team = team();
        SellerAccount account = account(team, "E-");
        Listing single = listing(team, account, "E-S2");

        String list = page(get("/console/workspace").with(signedIn(team.member())), 200);
        assertThat(list).contains("id=\"workspace-select-form\"").contains("id=\"bulk-clone\"").contains("id=\"bulk-ready\"")
                .contains("name=\"listing\" value=\"" + single.id() + "\"").contains("id=\"workspace-new-open\"");
        String detail = page(get("/console/workspace/listings/" + single.id()).with(signedIn(team.admin())), 200);
        assertThat(detail).contains("id=\"detail-edit\"").contains("id=\"detail-clone\"").contains("id=\"detail-history\"")
                .doesNotContain("The editor comes in a later release");
    }

    @Test
    void anotherClientsListingsAreA404OnEveryNewPage() throws Exception {
        Team team = team();
        Team stranger = team();
        SellerAccount account = account(team, "E-");
        SellerAccount foreign = accounts.create(stranger.client().getId(), Marketplace.ETSY, "Foreign", null, ExportFormat.XLSM);
        Listing mine = listing(team, account, "E-S2");
        long version = mine.version();

        perform(get(edit(mine.id())).with(signedIn(stranger.admin())), 404);
        perform(get("/console/workspace/listings/" + mine.id() + "/history").with(signedIn(stranger.admin())), 404);
        perform(post(edit(mine.id())).with(signedIn(stranger.admin())).with(csrf()).param("version", "0").param("v.item_name", "x"), 404);
        perform(post("/console/workspace/listings/" + mine.id() + "/restore").with(signedIn(stranger.admin())).with(csrf()).param("version", "0")
                .param("restore", "0"), 404);
        perform(post("/console/workspace/listings/" + mine.id() + "/draft").with(signedIn(stranger.admin())).with(csrf()), 404);
        perform(post("/console/workspace/clone").with(signedIn(stranger.admin())).with(csrf()).param("listing", mine.id().toString()), 404);
        perform(get("/console/workspace/new").param("account", account.id().toString()).with(signedIn(stranger.admin())), 404);
        perform(post("/console/workspace/new").with(signedIn(stranger.admin())).with(csrf()).param("account", account.id().toString()).param("v.sku", "X"), 404);
        // the status page answers for the stranger's own listings only: the SKU of the other client is not in it
        assertThat(page(post("/console/workspace/status").with(signedIn(stranger.admin())).with(csrf()).param("listing", mine.id().toString()), 200))
                .doesNotContain("E-S2");
        assertThat(listing(team, account, "E-S2").version()).isEqualTo(version);
        // and the stranger's own account still works
        perform(get("/console/workspace/new").param("account", foreign.id().toString()).with(signedIn(stranger.admin())), 200);
    }

    @Test
    void thePagesAreInBothLanguagesAndNoTextIsMissing() throws Exception {
        Team team = team();
        SellerAccount account = account(team, "E-");
        SellerAccount etsy = accounts.create(team.client().getId(), Marketplace.ETSY, "Etsy Shop", null, ExportFormat.XLSM);
        Listing child = listing(team, account, "E-P1-S");
        for (String language : new String[] {null, "vi"}) {
            for (MockHttpServletRequestBuilder request : List.of(get(edit(child.id())), get("/console/workspace/new").param("account", etsy.id().toString()),
                    get("/console/workspace/new"), get("/console/workspace/listings/" + child.id() + "/history"),
                    post("/console/workspace/clone").with(csrf()).param("listing", child.id().toString()),
                    post("/console/workspace/status").with(csrf()).param("listing", child.id().toString()))) {
                String html = page(in(request.with(signedIn(team.admin())), language), 200);
                assertThat(html).as(request.toString() + " " + language).contains("<html lang=\"" + (language == null ? "en" : "vi") + "\"").doesNotContain("??")
                        .doesNotContain("workspace.");
            }
        }
        String vi = page(in(get(edit(child.id())).with(signedIn(team.admin())), "vi"), 200);
        assertThat(vi).contains("Sửa listing").contains("Nhân bản").contains("Lịch sử").contains("Chuyển Sẵn sàng xuất").contains("Cột");
        String en = page(get(edit(child.id())).with(signedIn(team.admin())), 200);
        assertThat(en).contains("Edit listing").contains("Clone").contains("History").contains("Mark ready to export");
    }

    // ------------------------------------------------------------------ importing again over a listing changed in the workspace

    private MvcResult upload(ConsoleUser user, UUID account, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/console/workspace/import/upload").file(new MockMultipartFile("file", "flat.xlsx", "application/octet-stream", bytes))
                .with(signedIn(user)).with(csrf()).param("account", account.toString()).param("kind", "AMAZON_BULK").param("mode", "import")
                .param("status", "DRAFT")).andReturn();
    }

    private static List<String[]> selectedTargets(String page) {
        List<String[]> fields = new java.util.ArrayList<>();
        Matcher select = Pattern.compile("<select[^>]*name=\"(col\\.\\d+)\"[^>]*>(.*?)</select>", Pattern.DOTALL).matcher(page);
        while (select.find()) {
            Matcher selected = Pattern.compile("<option value=\"([^\"]*)\" selected").matcher(select.group(2));
            fields.add(new String[] {select.group(1), selected.find() ? selected.group(1) : ""});
        }
        return fields;
    }

    @Test
    void thePreviewOfAnImportNamesTheRowsChangedInTheWorkspaceAndTheCommitKeepsThemUnlessTheyAreTicked() throws Exception {
        Team team = team();
        SellerAccount account = account(team, "E-");
        Listing edited = listing(team, account, "E-S2");
        Map<String, String> posted = new LinkedHashMap<>(edited.data());
        posted.put("item_name", "Changed in the workspace");
        editor.save(actor(team.admin()), edited.id(), edited.version(), posted);

        String stage;
        MvcResult up = upload(team.admin(), account.id(), WorkspaceFixtures.flatFile("E-"));
        Matcher matcher = STAGE.matcher(up.getResponse().getRedirectedUrl());
        assertThat(matcher.find()).isTrue();
        stage = matcher.group(1);
        String mapping = page(get("/console/workspace/import/mapping").with(signedIn(team.admin())).param("stage", stage).param("mode", "import")
                .param("account", account.id().toString()).param("kind", "AMAZON_BULK").param("sheet", "Template").param("fieldRow", "3")
                .param("firstDataRow", "4").param("skuCol", "0"), 200);
        MockHttpServletRequestBuilder preview = post("/console/workspace/import/preview").with(signedIn(team.admin())).with(csrf()).param("stage", stage)
                .param("mode", "import").param("account", account.id().toString()).param("kind", "AMAZON_BULK").param("status", "DRAFT")
                .param("sheet", "Template").param("fieldRow", "3").param("firstDataRow", "4").param("skuCol", "0");
        selectedTargets(mapping).forEach(field -> preview.param(field[0], field[1]));

        String html = page(preview, 200);

        assertThat(html).contains("id=\"preview-edited\"").contains("id=\"count-edited\"").contains("Changed in the workspace")
                .containsPattern("name=\"ow\\.\\d+\"").contains("id=\"count-updated\"");
        Matcher line = Pattern.compile("name=\"ow\\.(\\d+)\"").matcher(html);
        assertThat(line.find()).isTrue();

        MockHttpServletRequestBuilder commit = post("/console/workspace/import/commit").with(signedIn(team.admin())).with(csrf()).param("stage", stage)
                .param("mode", "import").param("account", account.id().toString()).param("kind", "AMAZON_BULK").param("status", "DRAFT")
                .param("sheet", "Template").param("fieldRow", "3").param("firstDataRow", "4").param("skuCol", "0");
        selectedTargets(mapping).forEach(field -> commit.param(field[0], field[1]));
        MvcResult kept = perform(commit, 302);
        assertThat(kept.getFlashMap().get("notice").toString()).contains("1 listings changed in the workspace were kept as they are.");
        assertThat(listing(team, account, "E-S2").title()).isEqualTo("Changed in the workspace");
    }

    // ------------------------------------------------------------------ the export (ADR-063)

    private List<UUID> readyIds(Team team, SellerAccount account) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM workspace_listing WHERE client_id = ? AND account_id = ? AND deleted_at IS NULL ORDER BY sku",
                UUID.class, team.client().getId(), account.id());
        statusService.markReady(actor(team.admin()), ids, false, null);
        return ids;
    }

    @Autowired
    ListingStatusService statusService;

    @Test
    void theExportPageConfirmsWithTheCountsAndThenDownloadsTheFileAndMovesTheListings() throws Exception {
        Team team = team();
        SellerAccount account = account(team, "X-");
        List<UUID> ids = readyIds(team, account);

        String form = page(get("/console/workspace/export").param("account", account.id().toString()).with(signedIn(team.admin())), 200);
        assertThat(form).contains("id=\"export-filter\"").contains("id=\"export-preview-open\"");

        var request = post("/console/workspace/export").with(signedIn(team.admin())).with(csrf());
        ids.forEach(id -> request.param("listing", id.toString()));
        String confirm = page(request, 200);
        assertThat(confirm).contains("id=\"export-summary\"").contains("id=\"export-groups\"").contains("id=\"export-go\"").contains("id=\"export-gate-off\"")
                .contains("id=\"export-audit-off\"");

        var go = post("/console/workspace/export").with(signedIn(team.admin())).with(csrf()).param("step", "export").param("medium", "EXCEL")
                .param("confirm", Integer.toString(ids.size()));
        ids.forEach(id -> go.param("listing", id.toString()));
        MvcResult file = perform(go, 200);
        assertThat(file.getResponse().getHeader("Content-Disposition")).contains("attachment").contains(".xlsx");
        assertThat(file.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(file.getResponse().getContentAsByteArray()).startsWith((byte) 'P', (byte) 'K');
        assertThat(listing(team, account, "X-S2").status()).isEqualTo(ListingStatus.EXPORTED);

        // a wrong count stops: nothing more is exported and the confirmation is shown with the reason
        var stale = post("/console/workspace/export").with(signedIn(team.admin())).with(csrf()).param("step", "export").param("confirm", "99");
        ids.forEach(id -> stale.param("listing", id.toString()));
        assertThat(page(stale, 422)).contains("id=\"export-error\"");
        // another client cannot name the account
        Team stranger = team();
        perform(get("/console/workspace/export").param("account", account.id().toString()).with(signedIn(stranger.admin())), 404);
        perform(post("/console/workspace/export").with(signedIn(stranger.admin())).with(csrf()).param("account", account.id().toString()), 404);
    }

    @Test
    void theTextToPasteIsAPageWithACopyButtonInBothLanguages() throws Exception {
        Team team = team();
        SellerAccount account = account(team, "Y-");
        List<UUID> ids = readyIds(team, account);
        for (String language : new String[] {"en", "vi"}) {
            var go = in(post("/console/workspace/export"), language).with(signedIn(team.admin())).with(csrf()).param("step", "export")
                    .param("medium", "CLIPBOARD").param("withHeader", "1").param("confirm", Integer.toString(ids.size()));
            ids.forEach(id -> go.param("listing", id.toString()));
            String html = page(go, 200);
            assertThat(html).contains("id=\"paste-text\"").contains("data-copy-from=\"paste-text\"").contains("/console/copy.js").contains("Y-P1");
            if ("en".equals(language)) {
                assertThat(html).contains("Copy the text");
                assertThat(listing(team, account, "Y-S2").status()).isEqualTo(ListingStatus.EXPORTED);
                ids.forEach(id -> jdbc.update("UPDATE workspace_listing SET status = 'READY_TO_EXPORT' WHERE id = ?", id));
            } else {
                assertThat(html).contains("Sao chép text");
            }
        }
    }

    @Test
    void anEtsyListingShowsTheCopyPanelInTheOrderOfTheFieldsAndPostedMovesItLive() throws Exception {
        Team team = team();
        SellerAccount shop = accounts.create(team.client().getId(), Marketplace.ETSY, "Etsy panel", null, ExportFormat.XLSM);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("sku", "ET-1");
        form.put("title", "Plain ceramic mug");
        form.put("description", "A plain description.");
        form.put("tags", "mug, ceramic, gift");
        form.put("price", "19.99");
        form.put("quantity", "5");
        form.put("category", "Mugs");
        form.put("who_made", "i_did");
        form.put("when_made", "made_to_order");
        Listing mug = editor.create(actor(team.member()), shop.id(), form);

        String html = page(get("/console/workspace/listings/" + mug.id()).with(signedIn(team.member())), 200);
        assertThat(html).contains("id=\"etsy-copy-table\"").contains("id=\"copy-title\"").contains("id=\"copy-tags\"").contains("mug, ceramic, gift")
                .contains("/console/copy.js").doesNotContain("id=\"release-posted\"");
        assertThat(html.indexOf("copy-title")).isLessThan(html.indexOf("copy-description")).isLessThan(html.indexOf("copy-tags"));
        assertThat(html.indexOf("copy-tags")).isLessThan(html.indexOf("copy-price"));
        assertThat(page(get(edit(mug.id())).with(signedIn(team.member())), 200)).contains("id=\"etsy-copy-table\"");

        statusService.markReady(actor(team.member()), List.of(mug.id()), false, null);
        assertThat(page(get("/console/workspace/listings/" + mug.id()).with(signedIn(team.member())), 200)).contains("id=\"release-posted\"");
        perform(post("/console/workspace/listings/" + mug.id() + "/posted").with(signedIn(team.other())).with(csrf()), 403);
        perform(post("/console/workspace/listings/" + mug.id() + "/posted").with(signedIn(team.member())).with(csrf()), 302);
        assertThat(listings.find(team.client().getId(), mug.id()).orElseThrow().status()).isEqualTo(ListingStatus.LIVE);
    }
}
