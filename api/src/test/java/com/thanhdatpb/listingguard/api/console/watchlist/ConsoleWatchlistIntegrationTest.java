package com.thanhdatpb.listingguard.api.console.watchlist;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.WatchlistView;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The "Watchlist" page of the console (ADR-031): who may change the list (an ADMIN account, never a MEMBER), the revision guard,
 * every refusal at once, the two-step import from a Sheet or a CSV, the export, and escaping. Every name is invented.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleWatchlistIntegrationTest {

    private static final String PAGE = "/console/watchlist";
    private static final Pattern REVISION = Pattern.compile("name=\"revision\" value=\"(\\d+)\"");

    /** A client with two console accounts and the user objects that sign them in. */
    private record Team(Client client, ConsoleUser admin, ConsoleUser member) {
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ConsoleAccountRepository accounts;

    @Autowired
    WatchlistService watchlists;

    @MockitoBean
    JavaMailSender mailSender;

    private Team team() {
        Client client = provisioning.createClient("w" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        String id = UUID.randomUUID().toString();
        ConsoleUser admin = new ConsoleUser("admin-" + id + "@example.test", client.getId());
        ConsoleUser member = new ConsoleUser("member-" + id + "@example.test", client.getId());
        accounts.saveAndFlush(new ConsoleAccount(admin.email(), client.getId(), ConsoleRole.ADMIN));
        accounts.saveAndFlush(new ConsoleAccount(member.email(), client.getId(), ConsoleRole.MEMBER));
        return new Team(client, admin, member);
    }

    private String page(ConsoleUser user) throws Exception {
        return mvc.perform(get(PAGE).with(signedIn(user))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static String revisionIn(String html) {
        Matcher matcher = REVISION.matcher(html);
        assertThat(matcher.find()).as("a form with the revision").isTrue();
        return matcher.group(1);
    }

    private ResultActions add(ConsoleUser user, String revision, String mark, String aliases) throws Exception {
        return mvc.perform(post(PAGE + "/entries").with(signedIn(user)).with(csrf()).param("revision", revision)
                .param("mark", mark).param("aliases", aliases).param("classes", "25").param("serialNumber", "")
                .param("note", "từng bị claim"));
    }

    private static EntryInput entry(String mark) {
        return new EntryInput(mark, null, null, null, null, null);
    }

    private void seed(Team team, String... marks) {
        watchlists.replace(team.client().getId(), Arrays.stream(marks).map(ConsoleWatchlistIntegrationTest::entry).toList(), "api-key:seed", null);
    }

    private WatchlistView stored(Team team) {
        return watchlists.read(team.client().getId());
    }

    @Test
    void anAdminAddsAnEntryAndSeesWhoAddedIt() throws Exception {
        Team team = team();

        add(team.admin(), "0", "Brimwick Otters", "Otterz; BWO").andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(PAGE)).andExpect(flash().attribute("notice", "“Brimwick Otters” was added."));

        String html = page(team.admin());
        assertThat(html).contains("Brimwick Otters").contains("Otterz; BWO").contains("từng bị claim")
                .contains("Added by " + team.admin().email()).contains("(1 entries, 1 switched on, at most 500)");
        assertThat(stored(team).entries()).singleElement().satisfies(entry -> {
            assertThat(entry.aliases()).containsExactly("Otterz", "BWO");
            assertThat(entry.niceClasses()).containsExactly(25);
            assertThat(entry.addedBy()).startsWith("console:");
        });
        assertThat(html).containsPattern("aria-current=\"page\"[^>]*>Watchlist<");
    }

    @Test
    void aMemberReadsTheListButCannotChangeIt() throws Exception {
        Team team = team();
        seed(team, "Brimwick Otters");

        String html = page(team.member());

        assertThat(html).contains("Brimwick Otters").contains("id=\"readonly\"").doesNotContain("id=\"save-form\"")
                .doesNotContain("id=\"import-form\"").doesNotContain("/toggle").doesNotContain("/delete");
        String entryId = stored(team).entries().get(0).id().toString();
        String revision = String.valueOf(stored(team).revision());
        mvc.perform(post(PAGE + "/entries").with(signedIn(team.member())).with(csrf()).param("revision", revision)
                .param("mark", "Paddle Hard")).andExpect(status().isForbidden());
        mvc.perform(post(PAGE + "/entries/" + entryId + "/toggle").with(signedIn(team.member())).with(csrf())
                .param("revision", revision)).andExpect(status().isForbidden());
        mvc.perform(post(PAGE + "/entries/" + entryId + "/delete").with(signedIn(team.member())).with(csrf())
                .param("revision", revision)).andExpect(status().isForbidden());
        mvc.perform(post(PAGE + "/import/preview").with(signedIn(team.member())).with(csrf()).param("pasted", "Paddle Hard"))
                .andExpect(status().isForbidden());
        mvc.perform(get(PAGE + "/export.csv").with(signedIn(team.member()))).andExpect(status().isOk());
        assertThat(stored(team).entries()).hasSize(1);
    }

    @Test
    void anAccountThatIsNotInTheClientCannotChangeItsList() throws Exception {
        Team team = team();
        Team other = team();

        mvc.perform(post(PAGE + "/entries").with(signedIn(new ConsoleUser(other.admin().email(), team.client().getId())))
                .with(csrf()).param("revision", "0").param("mark", "Brimwick Otters")).andExpect(status().isForbidden());

        assertThat(stored(team).entries()).isEmpty();
    }

    @Test
    void aWriteWithoutTheCsrfTokenIsRefused() throws Exception {
        Team team = team();

        mvc.perform(post(PAGE + "/entries").with(signedIn(team.admin())).param("revision", "0").param("mark", "Brimwick Otters"))
                .andExpect(status().isForbidden());

        assertThat(stored(team).entries()).isEmpty();
    }

    @Test
    void aClientNeverSeesAnotherClientsList() throws Exception {
        Team mine = team();
        Team theirs = team();
        seed(theirs, "Quillmark Rovers");

        assertThat(page(mine.admin())).doesNotContain("Quillmark Rovers").contains("The list is empty.");
    }

    @Test
    void aFormBuiltBeforeSomeoneElsesChangeIsRefusedAndNothingIsLost() throws Exception {
        Team team = team();
        seed(team, "Brimwick Otters");
        String staleRevision = revisionIn(page(team.admin()));
        watchlists.replace(team.client().getId(), List.of(entry("Brimwick Otters"), entry("Quillmark Rovers")),
                "api-key:other", null);

        String html = add(team.admin(), staleRevision, "Paddle Hard", "").andExpect(status().isConflict()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("Someone changed the list after this page was opened");
        assertThat(stored(team).entries()).extracting("mark").containsExactly("Brimwick Otters", "Quillmark Rovers");
    }

    @Test
    void everyProblemIsShownAndNothingIsSaved() throws Exception {
        Team team = team();
        seed(team, "Brimwick Otters");
        String revision = revisionIn(page(team.admin()));

        String html = mvc.perform(post(PAGE + "/entries").with(signedIn(team.admin())).with(csrf()).param("revision", revision)
                        .param("mark", "BRIMWICK-OTTERS!").param("classes", "99").param("serialNumber", "12"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("Nothing was saved").contains("is the same as “Brimwick Otters”")
                .contains("Nice class 99 is not between 1 and 45").contains("A serial number is eight digits.")
                .contains("value=\"BRIMWICK-OTTERS!\"");
        assertThat(stored(team).entries()).hasSize(1);
    }

    @Test
    void anEntryIsEditedSwitchedOffAndDeleted() throws Exception {
        Team team = team();
        seed(team, "Brimwick Otters");
        String id = stored(team).entries().get(0).id().toString();

        String editPage = mvc.perform(get(PAGE).param("edit", id).with(signedIn(team.admin()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(editPage).contains("Edit an entry").contains("name=\"id\" value=\"" + id + "\"")
                .contains("value=\"Brimwick Otters\"");

        mvc.perform(post(PAGE + "/entries").with(signedIn(team.admin())).with(csrf()).param("id", id)
                        .param("revision", revisionIn(editPage)).param("mark", "Brimwick Otters").param("aliases", "Otterz")
                        .param("note", "second"))
                .andExpect(status().is3xxRedirection());
        assertThat(stored(team).entries().get(0).aliases()).containsExactly("Otterz");
        assertThat(stored(team).entries().get(0).id().toString()).as("same entry").isEqualTo(id);

        mvc.perform(post(PAGE + "/entries/" + id + "/toggle").with(signedIn(team.admin())).with(csrf())
                .param("revision", String.valueOf(stored(team).revision()))).andExpect(status().is3xxRedirection());
        assertThat(stored(team).entries().get(0).enabled()).isFalse();
        assertThat(stored(team).version()).as("nothing enabled").isNull();

        mvc.perform(post(PAGE + "/entries/" + id + "/delete").with(signedIn(team.admin())).with(csrf())
                .param("revision", String.valueOf(stored(team).revision()))).andExpect(status().is3xxRedirection());
        assertThat(stored(team).entries()).isEmpty();
        mvc.perform(post(PAGE + "/entries/" + UUID.randomUUID() + "/delete").with(signedIn(team.admin())).with(csrf())
                .param("revision", String.valueOf(stored(team).revision()))).andExpect(status().isNotFound());
    }

    @Test
    void aPastedSheetIsPreviewedThenImportedAndNothingIsStoredByThePreview() throws Exception {
        Team team = team();
        seed(team, "Brimwick Otters");
        String sheet = "mark\talias\tclasses\tserial\tnote\n"
                + "Brimwick Otters\tOtterz\t25\t\ttừng bị claim\n"
                + "Quillmark Rovers\tRovers FC; QR\t25, 28\t12345678\t\n"
                + "Paddle Hard Brimwick Never Quits\t\t\t\tkhẩu hiệu\n";

        String preview = mvc.perform(post(PAGE + "/import/preview").with(signedIn(team.admin())).with(csrf())
                        .param("pasted", sheet))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(preview).contains("Preview of the import").contains("Updates an entry").contains("Adds an entry")
                .contains("id=\"apply-form\"");
        assertThat(stored(team).entries()).as("a preview stores nothing").hasSize(1);

        mvc.perform(post(PAGE + "/import").with(signedIn(team.admin())).with(csrf()).param("data", sheet)
                        .param("delimiter", "tab").param("revision", revisionIn(preview)))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("notice", "3 rows imported, 3 of them changed the list."));

        WatchlistView list = stored(team);
        assertThat(list.entries()).extracting("mark")
                .containsExactly("Brimwick Otters", "Quillmark Rovers", "Paddle Hard Brimwick Never Quits");
        assertThat(list.entries().get(1).aliases()).containsExactly("Rovers FC", "QR");
        assertThat(list.entries().get(1).niceClasses()).containsExactly(25, 28);
        assertThat(list.entries().get(1).serialNumber()).isEqualTo("12345678");
        assertThat(list.entries().get(0).note()).isEqualTo("từng bị claim");
        assertThat(list.entries().get(0).addedBy()).as("an update keeps who added it").isEqualTo("api-key:seed");
    }

    @Test
    void aRefusedRowBlocksTheImportAndSaysWhy() throws Exception {
        Team team = team();
        String sheet = "Quillmark Rovers\t\tabc\t\t\nBrimwick Otters\t\t25\t12\t\nBrimwick-Otters\t\t\t\t\n";

        String preview = mvc.perform(post(PAGE + "/import/preview").with(signedIn(team.admin())).with(csrf())
                        .param("pasted", sheet))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(preview).contains("Refused").contains("is not a list of Nice class numbers")
                .contains("A serial number is eight digits.").doesNotContain("id=\"apply-form\"")
                .contains("Nothing is saved while a row is refused.");
        mvc.perform(post(PAGE + "/import").with(signedIn(team.admin())).with(csrf()).param("data", sheet)
                .param("delimiter", "tab").param("revision", "0")).andExpect(status().isOk());
        assertThat(stored(team).entries()).isEmpty();
    }

    @Test
    void aCsvFileIsReadWithItsHeaderBomAndSemicolons() throws Exception {
        Team team = team();
        String csv = "﻿Nhãn hiệu;Biến thể;Nhóm;Số đơn;Ghi chú\r\nBrimwick Otters;Otterz|BWO;25;12345678;từng bị claim\r\n";

        String preview = mvc.perform(multipart(PAGE + "/import/preview").file(new MockMultipartFile("file", "list.csv", "text/csv",
                        csv.getBytes(StandardCharsets.UTF_8))).with(signedIn(team.admin())).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(preview).contains("Adds an entry").contains("id=\"apply-form\"").contains("value=\"semicolon\"");
    }

    @Test
    void aFileThatIsNotUtf8AndAnEmptyImportAreRefusedWithAMessage() throws Exception {
        Team team = team();

        String latin1 = mvc.perform(multipart(PAGE + "/import/preview").file(new MockMultipartFile("file", "list.csv", "text/csv",
                        "Brimwick café\n".getBytes(StandardCharsets.ISO_8859_1))).with(signedIn(team.admin())).with(csrf()))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String empty = mvc.perform(post(PAGE + "/import/preview").with(signedIn(team.admin())).with(csrf()).param("pasted", "  "))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(latin1).contains("The file is not UTF-8");
        assertThat(empty).contains("There is nothing to import");
    }

    @Test
    void theExportIsACsvWithABomAndAFormulaIsGuarded() throws Exception {
        Team team = team();
        watchlists.replace(team.client().getId(), List.of(
                new EntryInput("Brimwick Otters", List.of("Otterz", "BWO"), List.of(25), "12345678", "từng bị claim, \"quoted\"", true),
                new EntryInput("=HYPERLINK(\"x\")", null, null, null, "+1", false)), "api-key:seed", null);

        MockHttpServletResponse response = mvc.perform(get(PAGE + "/export.csv").with(signedIn(team.member())))
                .andExpect(status().isOk()).andReturn().getResponse();
        String csv = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition")).contains("attachment").contains("trademark-watchlist.csv");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(csv).startsWith("﻿mark,aliases,classes,serial,note,enabled\r\n")
                .contains("Brimwick Otters,Otterz; BWO,25,12345678,\"từng bị claim, \"\"quoted\"\"\",true\r\n")
                .contains("\"'=HYPERLINK(\"\"x\"\")\",,,,'+1,false\r\n");
    }

    @Test
    void anExportComesBackAsTheSameListWhenItIsImportedAgain() throws Exception {
        Team team = team();
        watchlists.replace(team.client().getId(), List.of(
                new EntryInput("Brimwick Otters", List.of("Otterz"), List.of(25), "12345678", "từng bị claim", true),
                new EntryInput("=Danger", null, null, null, "-note", false)), "api-key:seed", null);
        WatchlistView before = stored(team);
        String csv = mvc.perform(get(PAGE + "/export.csv").with(signedIn(team.admin()))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        List<WatchlistTable.Row> rows = WatchlistTable.read(csv, ',');
        WatchlistTable.Merge merge = WatchlistTable.merge(before.entries(), rows, watchlists::normalizedForm);

        assertThat(merge.rows()).extracting(WatchlistTable.MergedRow::outcome)
                .containsOnly(WatchlistTable.Outcome.UNCHANGED);
        assertThat(rows.get(1).entry().mark()).isEqualTo("=Danger");
        assertThat(rows.get(1).entry().note()).isEqualTo("-note");
    }

    @Test
    void whatTheClientTypedIsEscapedInThePage() throws Exception {
        Team team = team();
        seed(team, "<script>alert(1)</script>");

        String html = page(team.admin());

        assertThat(html).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }
}
