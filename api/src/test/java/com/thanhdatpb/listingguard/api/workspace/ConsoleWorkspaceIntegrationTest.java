package com.thanhdatpb.listingguard.api.workspace;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.withoutShell;
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
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
 * The workspace pages of the Console (ADR-059) with the flag on: the four steps of an import from the first upload to the list, the template
 * upload, the detail page, the filters, who may do what, another client's 404, the refusals of a bad file, and both languages. The files are
 * synthetic. Nothing here was checked against a real Amazon template.
 */
@SpringBootTest(properties = {
        "listing-guard.workspace.enabled=true",
        "listing-guard.workspace.import-max-bytes=64KB",
        "listing-guard.workspace.max-template-bytes=64KB",
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
class ConsoleWorkspaceIntegrationTest {

    private record Team(Client client, ConsoleUser admin, ConsoleUser member) {
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

    @MockitoBean
    JavaMailSender mailSender;

    private Team team() {
        Client client = provisioning.createClient("wc" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        String id = UUID.randomUUID().toString();
        ConsoleUser admin = new ConsoleUser("admin-" + id + "@example.test", client.getId());
        ConsoleUser member = new ConsoleUser("member-" + id + "@example.test", client.getId());
        console.saveAndFlush(new ConsoleAccount(admin.email(), client.getId(), ConsoleRole.ADMIN));
        console.saveAndFlush(new ConsoleAccount(member.email(), client.getId(), ConsoleRole.MEMBER));
        return new Team(client, admin, member);
    }

    private static MockHttpServletRequestBuilder in(MockHttpServletRequestBuilder request, String language) {
        return language == null ? request : request.cookie(new Cookie(ConsoleLocaleResolver.COOKIE, language));
    }

    private String html(MockHttpServletRequestBuilder request, int status) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, int status) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(request.toString()).isEqualTo(status);
        return result;
    }

    private UUID createAccount(Team team, String name, String marketplace) throws Exception {
        MvcResult result = perform(post("/console/workspace/accounts").with(signedIn(team.admin())).with(csrf()).param("name", name)
                .param("marketplace", marketplace).param("brand", "Nobrand").param("exportFormat", "XLSM"), 302);
        assertThat(flash(result)).containsKey("notice");
        return jdbc.queryForObject("SELECT id FROM workspace_account WHERE client_id = ? AND name = ?", UUID.class, team.client().getId(), name);
    }

    private MvcResult upload(ConsoleUser user, UUID account, String kind, String mode, String filename, byte[] bytes, String text) throws Exception {
        var request = multipart("/console/workspace/import/upload").with(signedIn(user)).with(csrf()).param("account", account.toString())
                .param("kind", kind).param("mode", mode).param("status", "LIVE");
        if (bytes != null) {
            request.file(new MockMultipartFile("file", filename, "application/octet-stream", bytes));
        }
        if (text != null) {
            request.param("text", text);
        }
        return mvc.perform(request).andReturn();
    }

    private static java.util.Map<String, Object> flash(MvcResult result) {
        return new java.util.HashMap<>(result.getFlashMap());
    }

    private static String stageOf(MvcResult upload) {
        String location = upload.getResponse().getRedirectedUrl();
        assertThat(location).as("redirect after upload").startsWith("/console/workspace/import/sheet?");
        Matcher matcher = STAGE.matcher(location);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /** The selected target of each column of the mapping page, as a form would send them back. */
    private static List<String[]> selectedTargets(String page) {
        List<String[]> fields = new ArrayList<>();
        Matcher select = Pattern.compile("<select[^>]*name=\"(col\\.\\d+)\"[^>]*>(.*?)</select>", Pattern.DOTALL).matcher(page);
        while (select.find()) {
            Matcher selected = Pattern.compile("<option value=\"([^\"]*)\" selected").matcher(select.group(2));
            fields.add(new String[] {select.group(1), selected.find() ? selected.group(1) : ""});
        }
        return fields;
    }

    private MockHttpServletRequestBuilder withFields(MockHttpServletRequestBuilder request, ConsoleUser user, String stage, UUID account,
                                                     String kind, String sheet, String page) {
        request.with(signedIn(user)).with(csrf()).param("stage", stage).param("mode", "import").param("account", account.toString())
                .param("kind", kind).param("status", "LIVE").param("sheet", sheet).param("fieldRow", "3").param("firstDataRow", "4").param("skuCol", "0");
        selectedTargets(page).forEach(field -> request.param(field[0], field[1]));
        return request;
    }

    /** The whole way of an import through the pages; returns the stage's mapping page for the callers that want to look at it. */
    private void importFile(Team team, ConsoleUser who, UUID account, byte[] file, String owner) throws Exception {
        MvcResult up = upload(who, account, "AMAZON_BULK", "import", "flat.xlsx", file, null);
        String stage = stageOf(up);
        String mapping = html(get("/console/workspace/import/mapping").with(signedIn(who)).param("stage", stage).param("mode", "import")
                .param("account", account.toString()).param("kind", "AMAZON_BULK").param("sheet", "Template").param("fieldRow", "3")
                .param("firstDataRow", "4").param("skuCol", "0"), 200);
        MockHttpServletRequestBuilder preview = withFields(post("/console/workspace/import/preview"), who, stage, account, "AMAZON_BULK", "Template", mapping);
        if (owner != null) {
            preview.param("owner", owner);
        }
        String page = html(preview, 200);
        assertThat(page).contains("id=\"count-created\"");
        MockHttpServletRequestBuilder commit = withFields(post("/console/workspace/import/commit"), who, stage, account, "AMAZON_BULK", "Template", mapping);
        if (owner != null) {
            commit.param("owner", owner);
        }
        MvcResult done = perform(commit, 302);
        assertThat(done.getResponse().getRedirectedUrl()).isEqualTo("/console/workspace");
        assertThat(flash(done)).containsKey("notice");
    }

    // ------------------------------------------------------------------ the way of an import

    @Test
    void anAdminSetsUpAnAccountAndImportsASheetThroughTheFourSteps() throws Exception {
        Team team = team();
        UUID account = createAccount(team, "Shop One", "AMAZON");
        byte[] file = WorkspaceFixtures.flatFile("W-");

        MvcResult up = upload(team.admin(), account, "AMAZON_BULK", "import", "flat.xlsx", file, null);
        String stage = stageOf(up);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_import_stage WHERE client_id = ?", Long.class, team.client().getId())).isEqualTo(1);

        // step 2: the sheet and the rows are guessed and say so
        String sheet = html(get("/console/workspace/import/sheet").with(signedIn(team.admin())).param("stage", stage).param("mode", "import")
                .param("account", account.toString()).param("kind", "AMAZON_BULK"), 200);
        assertThat(sheet).contains("<option value=\"Template\" selected").contains("<option value=\"Instructions\"").contains("Valid Values")
                .contains("id=\"fieldRow\"").containsPattern("name=\"fieldRow\" value=\"3\"").containsPattern("name=\"firstDataRow\" value=\"4\"")
                .contains("<option value=\"0\" selected").contains("Guessed");
        assertThat(sheet).doesNotContain("Seller SKU\"></code").contains("Seller SKU");

        // step 3: every column has a target, and a guess says so
        String mapping = html(get("/console/workspace/import/mapping").with(signedIn(team.admin())).param("stage", stage).param("mode", "import")
                .param("account", account.toString()).param("kind", "AMAZON_BULK").param("sheet", "Template").param("fieldRow", "3")
                .param("firstDataRow", "4").param("skuCol", "0"), 200);
        List<String[]> targets = selectedTargets(mapping);
        assertThat(targets).hasSize(16);
        assertThat(targets.get(0)[1]).isEqualTo("sku");
        assertThat(targets.get(2)[1]).isEqualTo("title");
        assertThat(targets.get(15)[1]).as("x_custom_flag is not guessed").isEmpty();
        assertThat(mapping).contains("Guessed");

        // step 4: the preview says what will happen and writes nothing
        String preview = html(withFields(post("/console/workspace/import/preview"), team.admin(), stage, account, "AMAZON_BULK", "Template", mapping), 200);
        assertThat(count(preview, "count-rows")).isEqualTo("4");
        assertThat(count(preview, "count-created")).isEqualTo("4");
        assertThat(count(preview, "count-updated")).isEqualTo("0");
        assertThat(count(preview, "count-errors")).isEqualTo("0");
        assertThat(preview).contains("The template is created from this file");
        assertThat(commitButton(preview)).doesNotContain("disabled");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_listing WHERE client_id = ?", Long.class, team.client().getId())).isZero();

        MvcResult done = perform(withFields(post("/console/workspace/import/commit"), team.admin(), stage, account, "AMAZON_BULK", "Template", mapping), 302);
        assertThat(done.getResponse().getRedirectedUrl()).isEqualTo("/console/workspace");
        assertThat(flash(done).get("notice")).isEqualTo("Imported: 4 new, 0 updated, 0 unchanged, 0 with errors (not written).");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_import_stage WHERE client_id = ?", Long.class, team.client().getId()))
                .as("the staged file is deleted once it is written").isZero();

        // the list: everyone reads it all, children sit under their parent, the check state is "not checked"
        String list = html(get("/console/workspace").with(signedIn(team.member())), 200);
        assertThat(list).contains("W-P1").contains("W-P1-S").contains("W-S2").contains("Not checked").contains("Live")
                .contains("<caption class=\"lg-sr-only\"").contains("1-4 of 4");
        assertThat(list.indexOf("W-P1\"")).isLessThan(list.indexOf("W-P1-M")).isLessThan(list.indexOf("W-P1-S"));
        assertThat(list).containsPattern("data-child=\"true\"").containsPattern("data-child=\"false\"");
        assertThat(html(get("/console/workspace").with(signedIn(team.member())).param("q", "s2"), 200)).contains("W-S2").doesNotContain("W-P1-M");
        assertThat(html(get("/console/workspace").with(signedIn(team.member())).param("status", "DRAFT"), 200)).contains("No listing matches");
        assertThat(html(get("/console/workspace").with(signedIn(team.member())).param("owner", "none"), 200)).contains("W-S2");
        assertThat(html(get("/console/workspace").with(signedIn(team.member())).param("owner", "me"), 200)).contains("No listing matches");

        // the same file again: nothing to write, and the button says so
        String stage2 = stageOf(upload(team.admin(), account, "AMAZON_BULK", "import", "flat.xlsx", file, null));
        String again = html(withFields(post("/console/workspace/import/preview"), team.admin(), stage2, account, "AMAZON_BULK", "Template", mapping), 200);
        assertThat(count(again, "count-unchanged")).isEqualTo("4");
        assertThat(count(again, "count-created")).isEqualTo("0");
        assertThat(commitButton(again)).contains("disabled");
        perform(post("/console/workspace/import/cancel").with(signedIn(team.admin())).with(csrf()).param("stage", stage2), 302);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_import_stage WHERE client_id = ?", Long.class, team.client().getId())).isZero();
    }

    private static String commitButton(String page) {
        Matcher matcher = Pattern.compile("<button[^>]*id=\"commit-button\"[^>]*>").matcher(page);
        assertThat(matcher.find()).as("the write button").isTrue();
        return matcher.group();
    }

    private static String count(String page, String id) {
        Matcher matcher = Pattern.compile("id=\"" + id + "\"[^>]*>(\\d+)<").matcher(page);
        assertThat(matcher.find()).as(id).isTrue();
        return matcher.group(1);
    }

    @Test
    void theDetailPageShowsTheFieldsInTheOrderOfTheTemplateAndMarksPassthrough() throws Exception {
        Team team = team();
        UUID account = createAccount(team, "Shop Two", "AMAZON");
        importFile(team, team.admin(), account, WorkspaceFixtures.flatFile("D-"), null);
        UUID child = jdbc.queryForObject("SELECT id FROM workspace_listing WHERE client_id = ? AND sku = 'D-P1-S'", UUID.class, team.client().getId());

        String detail = html(get("/console/workspace/listings/" + child).with(signedIn(team.member())), 200);

        assertThat(detail).contains("D-P1-S").contains("Synthetic shirt S").contains("Kept as it is").contains("Amazon bulk file, version 1")
                .contains("Not checked").contains("Child");
        assertThat(detail.indexOf("item_sku")).isLessThan(detail.indexOf("item_name")).isLessThan(detail.indexOf("bullet_point1"))
                .isLessThan(detail.indexOf("x_custom_flag"));
        assertThat(detail).contains("You can read this listing").doesNotContain("owner-form");
        String asAdmin = html(get("/console/workspace/listings/" + child).with(signedIn(team.admin())), 200);
        assertThat(asAdmin).contains("id=\"owner-form\"").contains("id=\"delete-form\"");
        String parent = html(get("/console/workspace/listings/" + jdbc.queryForObject(
                "SELECT id FROM workspace_listing WHERE client_id = ? AND sku = 'D-P1'", UUID.class, team.client().getId())).with(signedIn(team.member())), 200);
        assertThat(parent).contains("Variations of this listing").contains("D-P1-M").contains("D-P1-S");
    }

    @Test
    void aMemberImportsIntoTheTemplateTheAdminMadeButCannotChangeAnotherMembersListing() throws Exception {
        Team team = team();
        UUID account = createAccount(team, "Shop Three", "AMAZON");
        importFile(team, team.admin(), account, WorkspaceFixtures.flatFile("M-"), null);
        List<List<String>> edited = List.of(List.of("M-S2", "MUG", "Edited by a member", "", "", "", "", "", "", "", "", "", "", "", "", ""));
        byte[] file = WorkspaceFixtures.flatFile("M-", WorkspaceFixtures.FIELDS, edited);
        String stage = stageOf(upload(team.member(), account, "AMAZON_BULK", "import", "flat.xlsx", file, null));

        String mapping = html(get("/console/workspace/import/mapping").with(signedIn(team.member())).param("stage", stage).param("mode", "import")
                .param("account", account.toString()).param("kind", "AMAZON_BULK").param("sheet", "Template").param("fieldRow", "3")
                .param("firstDataRow", "4").param("skuCol", "0"), 200);
        assertThat(mapping).contains("belongs to the account template").contains("disabled");
        String preview = html(withFields(post("/console/workspace/import/preview"), team.member(), stage, account, "AMAZON_BULK", "Template", mapping), 200);

        assertThat(count(preview, "count-errors")).isEqualTo("1");
        assertThat(preview).contains("You are not responsible for this listing");
        assertThat(jdbc.queryForObject("SELECT title FROM workspace_listing WHERE client_id = ? AND sku = 'M-S2'", String.class, team.client().getId()))
                .isEqualTo("Synthetic mug");
    }

    // ------------------------------------------------------------------ templates

    @Test
    void anAdminUploadsATemplateAndDownloadsItBackByteForByte() throws Exception {
        Team team = team();
        UUID account = createAccount(team, "Shop Four", "AMAZON");
        byte[] file = WorkspaceFixtures.asMacroEnabled(WorkspaceFixtures.flatFile("T-", WorkspaceFixtures.FIELDS, List.of()));
        String stage = stageOf(upload(team.admin(), account, "AMAZON_BULK", "template", "template.xlsm", file, null));
        assertThat(html(get("/console/workspace/import/sheet").with(signedIn(team.admin())).param("stage", stage).param("mode", "template")
                .param("account", account.toString()).param("kind", "AMAZON_BULK"), 200)).contains("macro project").contains("XLSM");
        String mapping = html(get("/console/workspace/import/mapping").with(signedIn(team.admin())).param("stage", stage).param("mode", "template")
                .param("account", account.toString()).param("kind", "AMAZON_BULK").param("sheet", "Template").param("fieldRow", "3")
                .param("firstDataRow", "4").param("skuCol", "0"), 200);
        assertThat(mapping).contains("Save the template");

        MockHttpServletRequestBuilder save = post("/console/workspace/import/save-template").with(signedIn(team.admin())).with(csrf())
                .param("stage", stage).param("mode", "template").param("account", account.toString()).param("kind", "AMAZON_BULK")
                .param("sheet", "Template").param("fieldRow", "3").param("firstDataRow", "4").param("skuCol", "0");
        selectedTargets(mapping).forEach(field -> save.param(field[0], field[1]));
        MvcResult saved = perform(save, 302);
        assertThat(flash(saved).get("notice")).isEqualTo("The template was saved as version 1.");

        String accounts = html(get("/console/workspace/accounts").with(signedIn(team.member())), 200);
        assertThat(accounts).contains("Shop Four").contains("XLSM").contains("Amazon bulk file").contains("Current").doesNotContain("Download the original file")
                .contains("Only an administrator can add accounts");
        UUID template = jdbc.queryForObject("SELECT id FROM workspace_template WHERE client_id = ?", UUID.class, team.client().getId());
        MvcResult download = perform(get("/console/workspace/templates/" + template + "/file").with(signedIn(team.admin())), 200);
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(file);
        assertThat(download.getResponse().getHeader("Content-Disposition")).startsWith("attachment").contains("template-v1.xlsm");
        assertThat(download.getResponse().getHeader("Cache-Control")).contains("no-store");

        // the same header again makes no new version
        String stage2 = stageOf(upload(team.admin(), account, "AMAZON_BULK", "template", "template.xlsm", file, null));
        MockHttpServletRequestBuilder again = post("/console/workspace/import/save-template").with(signedIn(team.admin())).with(csrf())
                .param("stage", stage2).param("mode", "template").param("account", account.toString()).param("kind", "AMAZON_BULK")
                .param("sheet", "Template").param("fieldRow", "3").param("firstDataRow", "4").param("skuCol", "0");
        selectedTargets(mapping).forEach(field -> again.param(field[0], field[1]));
        assertThat(perform(again, 302).getFlashMap().get("notice")).isEqualTo("This file has the same field names as version 1. No new version was made.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_template WHERE client_id = ?", Long.class, team.client().getId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ who may do what, and other clients

    @Test
    void onlyAnAdminManagesAccountsTemplatesOwnersAndDeletion() throws Exception {
        Team team = team();
        UUID account = createAccount(team, "Shop Five", "AMAZON");
        importFile(team, team.admin(), account, WorkspaceFixtures.flatFile("P-"), null);
        UUID listing = jdbc.queryForObject("SELECT id FROM workspace_listing WHERE client_id = ? AND sku = 'P-S2'", UUID.class, team.client().getId());

        perform(post("/console/workspace/accounts").with(signedIn(team.member())).with(csrf()).param("name", "X").param("marketplace", "ETSY"), 403);
        perform(post("/console/workspace/accounts/" + account).with(signedIn(team.member())).with(csrf()).param("name", "Renamed"), 403);
        perform(get("/console/workspace/import").with(signedIn(team.member())).param("mode", "template"), 403);
        perform(post("/console/workspace/listings/" + listing + "/owner").with(signedIn(team.member())).with(csrf()).param("version", "0")
                .param("owner", team.member().email()), 403);
        perform(post("/console/workspace/listings/" + listing + "/delete").with(signedIn(team.member())).with(csrf()).param("version", "0"), 403);
        assertThat(jdbc.queryForObject("SELECT name FROM workspace_account WHERE id = ?", String.class, account)).isEqualTo("Shop Five");

        // an admin may; a stale form is refused with a message and changes nothing
        UUID memberId = jdbc.queryForObject("SELECT id FROM account WHERE email = ?", UUID.class, team.member().email());
        MvcResult owner = perform(post("/console/workspace/listings/" + listing + "/owner").with(signedIn(team.admin())).with(csrf()).param("version", "0")
                .param("owner", memberId.toString()), 302);
        assertThat(flash(owner).get("notice")).isEqualTo("The responsible person was changed.");
        MvcResult stale = perform(post("/console/workspace/listings/" + listing + "/delete").with(signedIn(team.admin())).with(csrf()).param("version", "0"), 302);
        assertThat(flash(stale).get("error")).isEqualTo("Someone changed this listing first. Open it again and repeat the change.");
        MvcResult deleted = perform(post("/console/workspace/listings/" + listing + "/delete").with(signedIn(team.admin())).with(csrf()).param("version", "1"), 302);
        assertThat(flash(deleted).get("notice")).isEqualTo("The listing was deleted.");
        perform(get("/console/workspace/listings/" + listing).with(signedIn(team.admin())), 404);
        // a duplicate account name is a message, not an error page
        MvcResult duplicate = perform(post("/console/workspace/accounts").with(signedIn(team.admin())).with(csrf()).param("name", "shop five")
                .param("marketplace", "AMAZON"), 302);
        assertThat(flash(duplicate).get("error")).isEqualTo("Another account already has this name.");
    }

    @Test
    void anotherClientSeesNothingOfAListingAnAccountOrATemplateAndEveryPathAnswers404() throws Exception {
        Team one = team();
        Team two = team();
        UUID account = createAccount(one, "Shop Six", "AMAZON");
        importFile(one, one.admin(), account, WorkspaceFixtures.flatFile("Q-"), null);
        UUID listing = jdbc.queryForObject("SELECT id FROM workspace_listing WHERE client_id = ? AND sku = 'Q-S2'", UUID.class, one.client().getId());
        UUID template = jdbc.queryForObject("SELECT id FROM workspace_template WHERE client_id = ?", UUID.class, one.client().getId());

        perform(get("/console/workspace/listings/" + listing).with(signedIn(two.admin())), 404);
        perform(get("/console/workspace/templates/" + template + "/file").with(signedIn(two.admin())), 404);
        perform(post("/console/workspace/listings/" + listing + "/owner").with(signedIn(two.admin())).with(csrf()).param("version", "0"), 404);
        perform(post("/console/workspace/listings/" + listing + "/delete").with(signedIn(two.admin())).with(csrf()).param("version", "0"), 404);
        perform(post("/console/workspace/accounts/" + account).with(signedIn(two.admin())).with(csrf()).param("name", "Taken"), 404);
        String list = html(get("/console/workspace").with(signedIn(two.admin())), 200);
        assertThat(list).doesNotContain("Q-S2").doesNotContain("Shop Six").contains("No seller account yet");
        assertThat(html(get("/console/workspace/accounts").with(signedIn(two.admin())), 200)).doesNotContain("Shop Six");
        // an upload against another client's account is a message and stages nothing
        MvcResult up = upload(two.admin(), account, "AMAZON_BULK", "import", "f.xlsx", WorkspaceFixtures.flatFile("Q-"), null);
        assertThat(flash(up).get("error")).isEqualTo("The seller account does not exist.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_import_stage WHERE client_id = ?", Long.class, two.client().getId())).isZero();
        // a stage belongs to the person who uploaded it
        String stage = stageOf(upload(one.admin(), account, "AMAZON_BULK", "import", "f.xlsx", WorkspaceFixtures.flatFile("Q-"), null));
        MvcResult other = perform(get("/console/workspace/import/sheet").with(signedIn(one.member())).param("stage", stage).param("mode", "import")
                .param("account", account.toString()).param("kind", "AMAZON_BULK"), 302);
        assertThat(flash(other).get("error")).isEqualTo("The uploaded file is no longer available. Upload it again.");
    }

    // ------------------------------------------------------------------ a bad file

    @Test
    void aBadFileIsRefusedWithAReasonAndNothingIsKept() throws Exception {
        Team team = team();
        UUID account = createAccount(team, "Shop Seven", "AMAZON");

        assertThat(flash(upload(team.admin(), account, "AMAZON_BULK", "import", "locked.xlsx", WorkspaceFixtures.passwordProtected(), null)).get("error")).isEqualTo("The file is encrypted or protected by a password. Remove the password and upload it again.");
        // 6 MB of one letter zips to a few KB (far past the ratio guard) but the upload limit of this test is small: use a small bomb
        byte[] bomb = WorkspaceFixtures.zipBomb(WorkspaceFixtures.flatFile("Z-"), 40 << 20);
        assertThat(bomb.length).isLessThan(60 << 10);
        assertThat(flash(upload(team.admin(), account, "AMAZON_BULK", "import", "bomb.xlsx", bomb, null)).get("error"))
                .isEqualTo("The file expands to far more data than its size allows, so it was refused.");
        assertThat(flash(upload(team.admin(), account, "AMAZON_BULK", "import", "big.tsv", new byte[70 << 10], null)).get("error"))
                .isIn("The file is larger than the limit.", "This kind of file is not supported. Use .xlsx, .xlsm, .tsv or .csv in UTF-8.");
        byte[] big = ("a\tb\n" + "x\ty\n".repeat(20_000)).getBytes(StandardCharsets.UTF_8);
        assertThat(flash(upload(team.admin(), account, "AMAZON_BULK", "import", "big.tsv", big, null)).get("error"))
                .isEqualTo("The file is larger than the limit.");
        assertThat(flash(upload(team.admin(), account, "AMAZON_BULK", "import", "broken.xlsx", "PK\u0003\u0004 broken".getBytes(StandardCharsets.UTF_8), null)).get("error")).isEqualTo("The file is damaged and cannot be read.");
        assertThat(flash(upload(team.admin(), account, "AMAZON_BULK", "import", null, null, "")).get("error")).isEqualTo("Choose a file or paste cells.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_import_stage WHERE client_id = ?", Long.class, team.client().getId())).isZero();
    }

    @Test
    void pastedCellsFromASheetImportLikeAFile() throws Exception {
        Team team = team();
        UUID account = createAccount(team, "Shop Eight", "AMAZON");
        String text = new String(WorkspaceFixtures.flatFileTsv("V-"), StandardCharsets.UTF_8);

        String stage = stageOf(upload(team.admin(), account, "AMAZON_BULK", "import", null, null, text));
        String mapping = html(get("/console/workspace/import/mapping").with(signedIn(team.admin())).param("stage", stage).param("mode", "import")
                .param("account", account.toString()).param("kind", "AMAZON_BULK").param("sheet", "data").param("fieldRow", "3")
                .param("firstDataRow", "4").param("skuCol", "0"), 200);
        MockHttpServletRequestBuilder commit = withFields(post("/console/workspace/import/commit"), team.admin(), stage, account, "AMAZON_BULK", "data", mapping);
        perform(commit, 302);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_listing WHERE client_id = ?", Long.class, team.client().getId())).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT source_format FROM workspace_template WHERE client_id = ?", String.class, team.client().getId())).isEqualTo("TSV");
    }

    // ------------------------------------------------------------------ the pages themselves

    @Test
    void everyPageIsAccessibleWithoutInlineStylesAndInBothLanguages() throws Exception {
        Team team = team();
        UUID account = createAccount(team, "Shop Nine", "AMAZON");
        createAccount(team, "Shop Etsy", "ETSY");
        importFile(team, team.admin(), account, WorkspaceFixtures.flatFile("L-"), null);
        List<String> paths = List.of("/console/workspace", "/console/workspace/accounts", "/console/workspace/import",
                "/console/workspace/import?mode=template&account=" + account);
        for (String language : new String[] {null, "vi"}) {
            for (String path : paths) {
                String page = html(in(get(path).with(signedIn(team.admin())), language), 200);
                String body = withoutShell(page);
                assertThat(page).as(path + " " + language).contains("<html lang=\"" + (language == null ? "en" : "vi") + "\"").doesNotContain("??")
                        .contains("<h1").contains("id=\"main\"");
                assertThat(body).as(path).doesNotContain(" style=").doesNotContain("<script");
                assertThat(page).contains(language == null ? "Listing workspace" : "Kho listing");
            }
        }
        String vi = html(in(get("/console/workspace").with(signedIn(team.member())), "vi"), 200);
        assertThat(vi).contains("Chưa kiểm").contains("Danh sách listing").contains("Đang bán");
        String list = html(get("/console/workspace").with(signedIn(team.member())), 200);
        assertThat(list).contains("href=\"/console/workspace\"").contains("aria-current=\"page\"").contains("href=\"/console/workspace/accounts\"")
                .contains("href=\"/console/workspace/import\"").contains("lg-i-workspace");
        // the sidebar icons are drawn once in the sprite and are decorative
        assertThat(list).contains("<symbol id=\"lg-i-workspace\"").contains("<symbol id=\"lg-i-s-draft\"");
        String viImport = html(in(get("/console/workspace/import").with(signedIn(team.admin())), "vi"), 200);
        assertThat(viImport).contains("Nhập dữ liệu").contains("Tài khoản bán").contains("Loại sheet");
        String viSheet = html(in(get("/console/workspace/import/sheet").with(signedIn(team.admin())).param("stage", stageOf(upload(team.admin(), account,
                "AMAZON_BULK", "import", "flat.xlsx", WorkspaceFixtures.flatFile("L-"), null))).param("mode", "import")
                .param("account", account.toString()).param("kind", "AMAZON_BULK"), "vi"), 200);
        assertThat(viSheet).contains("Đoán").contains("Dòng tên field");
    }
}
