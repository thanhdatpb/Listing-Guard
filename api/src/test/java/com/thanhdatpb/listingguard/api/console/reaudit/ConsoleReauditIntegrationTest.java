package com.thanhdatpb.listingguard.api.console.reaudit;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.report.HealthReport;
import com.thanhdatpb.listingguard.api.report.HealthReportService;
import com.thanhdatpb.listingguard.api.report.ReportQuery;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.web.util.HtmlUtils;

/**
 * The periodic re-audit page (ADR-035) end to end: a file read by an input adapter, the split of new and known risks when the catalog
 * or the watchlist changed, the comparison of two runs, the trace that is stored (hashes and counts, never text), the CSV, the reminder
 * on the home page, the health report, and the files the page refuses. Synthetic listings only.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "listing-guard.console.default-locale=vi",
        "spring.mail.host=mail.example.test",
        "listing-guard.plans.enabled=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class ConsoleReauditIntegrationTest {

    private static final String ETSY_HEADER = "SKU,TITLE,DESCRIPTION,PRICE,QUANTITY,TAGS,MATERIALS";

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReauditStore store;

    @Autowired
    WatchlistService watchlists;

    @Autowired
    HealthReportService reports;

    @MockitoBean
    JavaMailSender mailSender;

    private ConsoleUser user() {
        return userOf(PlanTestSupport.newClient(provisioning, clients, PlanCode.SELLER));
    }

    private static String row(String sku, String title) {
        return sku + "," + title + ",A synthetic mug.,19.99,5,\"mug,ceramic\",clay";
    }

    private static String file(String... rows) {
        return ETSY_HEADER + "\n" + String.join("\n", rows) + "\n";
    }

    private static MockMultipartHttpServletRequestBuilder upload(ConsoleUser user, String marketplace, String account, String name,
                                                                 byte[] bytes) {
        MockMultipartHttpServletRequestBuilder request = multipart("/console/reaudit/run").with(signedIn(user)).with(csrf());
        request.file(new MockMultipartFile("file", name, "text/csv", bytes)).param("marketplace", marketplace);
        if (account != null) {
            request.param("account", account);
        }
        return request;
    }

    private String run(ConsoleUser user, String account, String text) throws Exception {
        return mvc.perform(upload(user, "ETSY", account, "export.csv", text.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String refused(ConsoleUser user, String marketplace, String name, byte[] bytes, int status) throws Exception {
        return mvc.perform(upload(user, marketplace, null, name, bytes)).andExpect(status().is(status)).andReturn().getResponse()
                .getContentAsString();
    }

    /** The cells of the rows of the table with this id, tags removed, one list per row. */
    private static List<List<String>> table(String html, String id) {
        Matcher table = Pattern.compile("<table[^>]*id=\"" + id + "\"[^>]*>(.*?)</table>", Pattern.DOTALL).matcher(html);
        if (!table.find()) {
            return List.of();
        }
        List<List<String>> rows = new ArrayList<>();
        Matcher tr = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(table.group(1));
        while (tr.find()) {
            List<String> cells = new ArrayList<>();
            Matcher td = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL).matcher(tr.group(1));
            while (td.find()) {
                cells.add(HtmlUtils.htmlUnescape(td.group(1).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim()));
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        return rows;
    }

    private static List<String> skus(String html, String id) {
        return table(html, id).stream().map(cells -> cells.get(1)).toList();
    }

    /**
     * The SKUs of the rows of a risk table that come from a mark (a catalog mark or a watchlist entry). The Etsy export has no
     * {@code who_made} or {@code when_made}, so every listing also has listing-rules errors; those have no mark and are known.
     */
    private static List<String> marked(String html, String id) {
        return table(html, id).stream().filter(cells -> !cells.get(3).equals("—")).map(cells -> cells.get(1)).distinct().toList();
    }

    private static String csv(String html) {
        Matcher href = Pattern.compile("id=\"download-csv\"\\s+href=\"data:text/csv;charset=utf-8;base64,([^\"]+)\"").matcher(html);
        assertThat(href.find()).as("the CSV link").isTrue();
        return new String(Base64.getDecoder().decode(href.group(1)), StandardCharsets.UTF_8);
    }

    private List<String> listItems(String html, String id) {
        Matcher block = Pattern.compile("id=\"" + id + "\"[^>]*>(.*?)</ul>", Pattern.DOTALL).matcher(html);
        if (!block.find()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        Matcher li = Pattern.compile("<li[^>]*>(.*?)</li>", Pattern.DOTALL).matcher(block.group(1));
        while (li.find()) {
            items.add(HtmlUtils.htmlUnescape(li.group(1).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim()));
        }
        return items;
    }

    private UUID latestRun(ConsoleUser user) {
        return jdbc.queryForObject("SELECT id FROM reaudit_run WHERE client_id = ? ORDER BY started_at DESC, id LIMIT 1", UUID.class,
                user.clientId());
    }

    private int runs(ConsoleUser user) {
        return PlanTestSupport.countOf(jdbc, "reaudit_run", user.clientId());
    }

    /** A catalog version that holds only the given marks, as an older version of the served catalog would. */
    private String olderCatalog(String... marks) {
        String version = "older-" + unique();
        Long id = jdbc.queryForObject("""
                INSERT INTO trademark_catalog_version (version, label, source_name, source_reference, source_license, source_date,
                    file_sha256, format_version, filters_json, pack_id, normalization_version, normalization_hash, records_read,
                    records_filtered, records_merged, records_loaded)
                VALUES (?, 'older', 'test', 'test', 'test', DATE '2026-10-01', repeat('a', 64), 'jsonl-1', '{}', 'trademark-risk',
                    'test', repeat('b', 64), 1, 0, 0, 1) RETURNING id""", Long.class, version);
        for (String mark : marks) {
            jdbc.update("""
                    INSERT INTO trademark_entry (catalog_version_id, normalized, display, nice_classes, status, common_word, source_ids)
                    VALUES (?, ?, ?, ARRAY[25]::smallint[], 'REGISTERED', FALSE, ARRAY['T-' || ?])""", id, mark.toLowerCase(), mark,
                    mark);
        }
        return version;
    }

    private void claimRunWasMadeWith(ConsoleUser user, String catalogVersion) {
        jdbc.update("UPDATE reaudit_run SET catalog_version = ? WHERE client_id = ?", catalogVersion, user.clientId());
    }

    // ---- the first run, the trace, the CSV

    @Test
    void theFirstRunHasNothingToCompareWithSoEveryRiskIsKnownAndOnlyHashesAndCountsAreStored() throws Exception {
        ConsoleUser user = user();
        String token = unique();
        String html = run(user, null, file(row("E-1", "Zorvex ceramic mug " + token), row("E-2", "Plain ceramic cup " + token),
                row("=E-3", "Zorvex ceramic bowl " + token)));

        assertThat(html).contains("id=\"first-run\"").contains("id=\"known-table\"").doesNotContain("id=\"new-table\"")
                .doesNotContain("id=\"changes\"").contains("etsy-listings-export");
        assertThat(marked(html, "known-table")).contains("E-1", "=E-3").doesNotContain("E-2");
        assertThat(table(html, "known-table")).anySatisfy(cells -> assertThat(cells.get(3)).containsIgnoringCase("zorvex"));

        UUID id = latestRun(user);
        assertThat(jdbc.queryForMap("SELECT catalog_version, rows_total, rows_checked, findings_new, previous_run_id FROM reaudit_run "
                + "WHERE id = ?", id)).containsEntry("catalog_version", TrademarkReadyTestConfig.catalogVersion())
                .containsEntry("rows_total", 3).containsEntry("rows_checked", 3).containsEntry("findings_new", 0)
                .containsEntry("previous_run_id", null);
        assertThat(jdbc.queryForObject("SELECT findings FROM reaudit_run WHERE id = ?", Integer.class, id)).isGreaterThanOrEqualTo(2);
        List<ReauditStore.Row> rows = store.rows(id);
        assertThat(rows).extracting(ReauditStore.Row::sku).containsExactly("E-1", "E-2", "=E-3");
        assertThat(rows).extracting(ReauditStore.Row::rowHash).allMatch(hash -> hash.matches("[0-9a-f]{64}")).doesNotHaveDuplicates();
        String stored = jdbc.queryForObject("SELECT (SELECT row_to_json(r)::text FROM reaudit_run r WHERE id = ?) || "
                + "(SELECT string_agg(row_to_json(w)::text, ' ') FROM reaudit_run_row w WHERE run_id = ?)", String.class, id, id);
        assertThat(stored).as("no title, tag or description is stored").doesNotContain(token).doesNotContain("Zorvex")
                .doesNotContain("A synthetic mug").doesNotContain("ceramic");

        String csv = csv(html);
        assertThat(csv).startsWith("﻿").contains("SKU,Account,Luật,Nhãn,Mức rủi ro,Nhóm,Dòng,Trường");
        assertThat(csv.lines()).anyMatch(line -> line.startsWith("E-1,,") && line.toLowerCase().contains("zorvex"))
                .anyMatch(line -> line.startsWith("'=E-3,,") && line.toLowerCase().contains("zorvex"))
                .noneMatch(line -> line.startsWith("E-2,") && line.toLowerCase().contains("zorvex"));
        assertThat(csv).doesNotContain(token);
    }

    @Test
    void theSecondRunSaysWhichListingsWereEditedAddedAndDroppedAndKeepsKnownRisksKnown() throws Exception {
        ConsoleUser user = user();
        String token = unique();
        run(user, "shop-a", file(row("E-1", "Zorvex ceramic mug " + token), row("E-2", "Plain ceramic cup " + token),
                row("E-3", "Plain ceramic bowl " + token)));

        String html = run(user, "shop-a", file(row("E-1", "Zorvex ceramic mug " + token), row("E-2", "Plain ceramic cup 2 " + token),
                row("E-4", "Plain ceramic jar " + token)));

        assertThat(html).doesNotContain("id=\"first-run\"").contains("id=\"changes\"").doesNotContain("id=\"new-table\"");
        assertThat(marked(html, "known-table")).containsOnly("E-1");
        assertThat(listItems(html, "changes-edited")).containsExactly("E-2");
        assertThat(listItems(html, "changes-added")).containsExactly("E-4");
        assertThat(listItems(html, "changes-dropped")).containsExactly("E-3");
        assertThat(listItems(html, "change-counts")).hasSize(4).anyMatch(item -> item.endsWith(": 1") && item.startsWith("Không đổi"));
        assertThat(runs(user)).isEqualTo(2);
        UUID second = latestRun(user);
        assertThat(jdbc.queryForObject("SELECT previous_run_id IS NOT NULL FROM reaudit_run WHERE id = ?", Boolean.class, second))
                .isTrue();

        // another account label is another scope: nothing to compare with, whatever the other one did
        String other = run(user, "shop-b", file(row("E-1", "Zorvex ceramic mug " + token)));
        assertThat(other).contains("id=\"first-run\"").doesNotContain("id=\"changes\"");
        assertThat(runs(user)).isEqualTo(3);
    }

    // ---- new and known when the catalog or the watchlist changed

    @Test
    void whenTheCatalogVersionChangedARiskFromAMarkTheOlderVersionLackedIsNewAndOneItHadIsKnown() throws Exception {
        ConsoleUser user = user();
        String token = unique();
        String listing = file(row("E-1", "Zorvex ceramic mug " + token), row("E-2", "Brask ceramic mug " + token),
                row("E-3", "Plain ceramic cup " + token));
        String first = run(user, null, listing);
        assertThat(marked(first, "known-table")).containsOnly("E-1", "E-2");

        // the previous run used an older catalog that had Zorvex and not Brask
        claimRunWasMadeWith(user, olderCatalog("Zorvex"));
        String second = run(user, null, listing);

        assertThat(marked(second, "new-table")).as("Brask came with the new catalog").containsOnly("E-2");
        assertThat(marked(second, "known-table")).as("Zorvex was in both").containsOnly("E-1");
        assertThat(jdbc.queryForObject("SELECT findings_new FROM reaudit_run WHERE id = ?", Integer.class, latestRun(user)))
                .isEqualTo(table(second, "new-table").size()).isPositive();
        assertThat(table(second, "new-table")).allSatisfy(cells -> assertThat(cells.get(3)).containsIgnoringCase("brask"));
        String csv = csv(second);
        assertThat(csv.lines().filter(line -> line.startsWith("E-2,") && line.toLowerCase().contains("brask")).findFirst().orElseThrow())
                .contains("Mới");
        assertThat(csv.lines().filter(line -> line.startsWith("E-1,") && line.toLowerCase().contains("zorvex")).findFirst().orElseThrow())
                .contains("Đã biết");
    }

    @Test
    void ifThePreviousCatalogVersionIsGoneEveryCatalogRiskIsNewAndThePageSaysWhy() throws Exception {
        ConsoleUser user = user();
        String token = unique();
        String listing = file(row("E-1", "Zorvex ceramic mug " + token));
        run(user, null, listing);
        claimRunWasMadeWith(user, "gone-" + unique());

        String second = run(user, null, listing);

        assertThat(marked(second, "new-table")).containsOnly("E-1");
        assertThat(second).contains("không còn được lưu");
    }

    @Test
    void aWatchlistEntryAddedAfterTheLastRunIsNewOnTheNextRunAndKnownOnTheOneAfter() throws Exception {
        ConsoleUser user = user();
        String token = unique();
        String listing = file(row("E-1", "Zephyr Comets ceramic mug " + token), row("E-2", "Plain ceramic cup " + token));
        String first = run(user, null, listing);
        assertThat(marked(first, "known-table")).as("nothing in the catalog or the watchlist yet").isEmpty();
        Thread.sleep(20);

        watchlists.replace(user.clientId(), List.of(new EntryInput("Zephyr Comets", List.of(), List.of(25), null, "claim", true)),
                "test", null);
        String second = run(user, null, listing);

        assertThat(marked(second, "new-table")).containsOnly("E-1");
        assertThat(table(second, "new-table").get(0)).anyMatch(cell -> cell.equals("watchlist_match"));
        assertThat(jdbc.queryForObject("SELECT watchlist_revision FROM reaudit_run WHERE id = ?", Long.class, latestRun(user)))
                .isEqualTo(1L);

        String third = run(user, null, listing);

        assertThat(third).doesNotContain("id=\"new-table\"");
        assertThat(marked(third, "known-table")).containsOnly("E-1");
    }

    // ---- the reminder and the health report

    @Test
    void theHomePageRemindsWhenTheCatalogServedIsNotTheOneOfTheLatestRunOfAScope() throws Exception {
        ConsoleUser noRuns = user();
        assertThat(mvc.perform(get("/console").with(signedIn(noRuns))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).doesNotContain("id=\"reaudit-notice\"");

        ConsoleUser user = user();
        run(user, "shop-a", file(row("E-1", "Plain ceramic mug " + unique())));
        assertThat(mvc.perform(get("/console").with(signedIn(user))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).as("the run used the catalog served now").doesNotContain("id=\"reaudit-notice\"");

        claimRunWasMadeWith(user, olderCatalog("Zorvex"));
        String home = mvc.perform(get("/console").with(signedIn(user))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(home).contains("id=\"reaudit-notice\"").contains("Catalog đã cập nhật, nên rà lại")
                .contains(TrademarkReadyTestConfig.catalogVersion()).contains("shop-a").contains("href=\"/console/reaudit\"");

        run(user, "shop-a", file(row("E-1", "Plain ceramic mug " + unique())));
        assertThat(mvc.perform(get("/console").with(signedIn(user))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).as("a new run clears the reminder").doesNotContain("id=\"reaudit-notice\"");
    }

    @Test
    void theHealthReportCountsRunsAndScopesDueAndHoldsNoText() throws Exception {
        ConsoleUser user = user();
        String token = unique();
        run(user, "shop-a", file(row("SKU-" + token, "Zorvex ceramic mug " + token)));
        run(user, "shop-b", file(row("E-2", "Plain ceramic cup " + token)));
        claimRunWasMadeWith(user, olderCatalog("Zorvex"));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        HealthReport report = reports.summary(user.clientId(), new ReportQuery(today.minusDays(1), today.plusDays(1), null));

        assertThat(report.reaudit().runs()).isEqualTo(2);
        assertThat(report.reaudit().clients()).isEqualTo(1);
        assertThat(report.reaudit().listingsChecked()).isEqualTo(2);
        assertThat(report.reaudit().findings()).isGreaterThanOrEqualTo(1);
        assertThat(report.reaudit().servedCatalog()).isEqualTo(TrademarkReadyTestConfig.catalogVersion());
        assertThat(report.reaudit().scopesToReaudit()).as("both scopes were updated to the older catalog").isEqualTo(2L);
        assertThat(report.reaudit().toString()).doesNotContain(token);
    }

    // ---- what is refused

    @Test
    void aFileThatIsNotInAFormatTheAdaptersKnowIsRefusedAndNothingIsCheckedOrStored() throws Exception {
        ConsoleUser user = user();

        String html = refused(user, "ETSY", "mine.csv", "Tiêu đề,Mô tả\nMug,desc\n".getBytes(StandardCharsets.UTF_8), 400);

        assertThat(html).contains("id=\"error\"").contains("etsy-listings-export").contains("amazon-flat-file")
                .contains("Kiểm tra hàng loạt");
        assertThat(runs(user)).isZero();
        assertThat(PlanTestSupport.countOf(jdbc, "listing_check", user.clientId())).isZero();
    }

    @Test
    void aFileOfAnotherMarketplaceThanChosenIsRefused() throws Exception {
        ConsoleUser user = user();

        String html = refused(user, "AMAZON", "export.csv", file(row("E-1", "Plain mug " + unique())).getBytes(StandardCharsets.UTF_8),
                400);

        assertThat(html).contains("etsy-listings-export").contains("bạn đã chọn Amazon");
        assertThat(runs(user)).isZero();
    }

    @Test
    void aMalformedFileIsRefusedWithAReasonAndNothingIsStored() throws Exception {
        ConsoleUser user = user();
        byte[] latin1 = (ETSY_HEADER + "\nE-1,Mug é,x,1,1,a,b\n").getBytes(StandardCharsets.ISO_8859_1);

        assertThat(refused(user, "ETSY", "old.csv", latin1, 400)).contains("không phải UTF-8");
        assertThat(refused(user, "ETSY", "book.xlsx", new byte[] {1}, 400)).contains("Chỉ nhận file .csv, .tsv hoặc .txt");
        assertThat(refused(user, "ETSY", "empty.csv", new byte[0], 400)).contains("Chưa chọn file");
        assertThat(refused(user, "ETSY", "header.csv", (ETSY_HEADER + "\n").getBytes(StandardCharsets.UTF_8), 400))
                .contains("Chỉ có dòng tiêu đề");
        assertThat(refused(user, "ETSY", "quote.csv", (ETSY_HEADER + "\nE-1,\"never closed,x,1,1,a,b\n").getBytes(StandardCharsets.UTF_8),
                400)).contains("id=\"error\"");
        assertThat(mvc.perform(upload(user, "ETSY", "x".repeat(81), "export.csv", file(row("E-1", "Mug")).getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString()).contains("dài quá 80 ký tự");
        assertThat(runs(user)).isZero();
        assertThat(PlanTestSupport.countOf(jdbc, "listing_check", user.clientId())).isZero();
    }

    @Test
    void aRowThatCannotBeReadIsListedAsNotJudgedAndTheOthersAreCheckedAndItIsNeverCalledEdited() throws Exception {
        ConsoleUser user = user();
        String token = unique();
        String bad = "E-1,Plain ceramic mug " + token + ",A synthetic mug.,\"19,99\",5,\"mug,ceramic\",clay";

        String first = run(user, null, file(bad, row("E-2", "Plain ceramic cup " + token)));
        assertThat(first).contains("id=\"skipped\"");
        assertThat(skus(first, "skipped-table")).containsExactly("E-1");
        assertThat(store.rows(latestRun(user))).extracting(ReauditStore.Row::status)
                .containsExactly(ReauditStore.UNREADABLE, ReauditStore.CHECKED);

        String second = run(user, null, file(row("E-1", "Plain ceramic mug " + token), row("E-2", "Plain ceramic cup " + token)));

        assertThat(listItems(second, "changes-edited")).isEmpty();
        assertThat(listItems(second, "changes-added")).isEmpty();
        assertThat(listItems(second, "change-counts")).anyMatch(item -> item.startsWith("Không so được"));
    }

    @Test
    void thePageNeedsASignedInSessionAndTheFormShowsTheRecentRuns() throws Exception {
        mvc.perform(get("/console/reaudit")).andExpect(status().is3xxRedirection());

        ConsoleUser user = user();
        String empty = mvc.perform(get("/console/reaudit").with(signedIn(user))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(empty).contains("id=\"reaudit-form\"").doesNotContain("id=\"recent-table\"");
        run(user, "shop-a", file(row("E-1", "Plain ceramic mug " + unique())));

        String after = mvc.perform(get("/console/reaudit").with(signedIn(user))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(after).contains("id=\"recent-table\"").contains("shop-a").contains("<option value=\"shop-a\">");
    }

    // ---- the design system (ADR-042)

    private static int countOf(String html, String text) {
        int count = 0;
        for (int at = html.indexOf(text); at >= 0; at = html.indexOf(text, at + text.length())) {
            count++;
        }
        return count;
    }

    @Test
    void theFormOfAClientThatNeverRanIsAnInvitationWithTheUploadBoxAndNoLastRun() throws Exception {
        ConsoleUser user = user();

        String page = mvc.perform(get("/console/reaudit").with(signedIn(user))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(page).contains("id=\"reaudit-page\"").contains("class=\"lg-drop\"").contains("id=\"reaudit-form\"")
                .contains("id=\"last-run\"").contains("id=\"no-runs\"").contains("<h2 id=\"last-heading\"")
                .doesNotContain("id=\"recent-table\"").doesNotContain("id=\"reaudit-notice\"").doesNotContain("/console/console.css")
                .doesNotContain("class=\"page wide\"").doesNotContain("class=\"notice");
        assertThat(page).containsPattern("for=\"marketplace\"").containsPattern("for=\"account\"");
        assertThat(countOf(page, "lg-btn--primary")).as("one brand button: the run").isEqualTo(1);
        assertThat(page).containsPattern("<h1[^>]*>Rà định kỳ</h1>");
    }

    @Test
    void theLastRunIsACardWithItsCountsAndTheBannerOfAStaleCatalogHasAButtonToTheForm() throws Exception {
        ConsoleUser user = user();
        run(user, "shop-a", file(row("E-1", "Plain ceramic mug " + unique())));

        String fresh = mvc.perform(get("/console/reaudit").with(signedIn(user))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        int from = fresh.indexOf("id=\"last-run\"");
        String card = fresh.substring(from, fresh.indexOf("</section>", from));
        assertThat(card).contains("shop-a").contains("Listing đã rà").contains("Rủi ro đã biết").doesNotContain("id=\"no-runs\"");
        assertThat(fresh).as("the run used the catalog served now").doesNotContain("id=\"reaudit-notice\"");

        claimRunWasMadeWith(user, olderCatalog("Zorvex"));
        String stale = mvc.perform(get("/console/reaudit").with(signedIn(user))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(stale).contains("id=\"reaudit-notice\"").contains("lg-banner--warning").contains("Catalog đã cập nhật, nên rà lại")
                .contains(TrademarkReadyTestConfig.catalogVersion()).contains("shop-a");
        // the listings are not stored, so "Re-audit now" leads to the form that takes the file again
        assertThat(stale).containsPattern("<a class=\"lg-btn lg-btn--small\" href=\"#reaudit-form\">");
        assertThat(stale.indexOf("id=\"reaudit-notice\"")).isLessThan(stale.indexOf("id=\"reaudit-form\""));
    }

    @Test
    void theResultHasTheNewRisksWithTheDiamondAndTheKnownRisksInAFoldThatOpensWhenItIsAllThereIs() throws Exception {
        ConsoleUser user = user();
        String text = "Zorvex ceramic mug " + unique();

        String first = run(user, "shop-fold", file(row("E-1", text)));
        assertThat(first).contains("id=\"known-table\"").contains("<details class=\"lg-card lg-fold\" open=\"open\">")
                .contains("id=\"download-csv\"").doesNotContain("id=\"new-table\"");
        assertThat(countOf(first, "lg-btn--primary")).as("one brand button: the CSV").isEqualTo(1);

        claimRunWasMadeWith(user, olderCatalog("Zorvex"));
        String second = run(user, "shop-fold", file(row("E-1", text), row("E-2", "Brask ceramic mug " + unique())));

        assertThat(second).contains("id=\"new-table\"").contains("class=\"lg-mark lg-mark--inline\"")
                .contains("<details class=\"lg-card lg-fold\">").doesNotContain("class=\"notice");
        assertThat(second.indexOf("id=\"new-risks\"")).as("new risks come first").isLessThan(second.indexOf("id=\"known-risks\""));
    }
}
