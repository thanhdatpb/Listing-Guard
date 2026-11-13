package com.thanhdatpb.listingguard.api.console.bulk;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.prepareRequest;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.resultRows;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.runRequest;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.selectedTargets;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.sheetLines;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER_15;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.console.bulk.BulkPages.ResultRow;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.web.BodyLimitFilter;
import jakarta.servlet.http.HttpSession;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The console bulk check with plans on and the synthetic trademark catalog loaded (ADR-029): a pasted table with and without a header
 * row, cells with line breaks and tabs in quotes, a column the page does not know, the cost of a run, the order of the answer, the
 * mapping an account used, a family of variations, and what is and is not kept. The checks themselves and their plan, quota and cache
 * rules are the API's and are tested there; these tests look at the page and at what it asks the services to do.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test",
        "listing-guard.plans.enabled=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, PlanTestClockConfig.class})
class ConsoleBulkIntegrationTest {

    private static final String HEADER = "SKU\tTiêu đề\tMô tả\tTag 1\tTag 2\tGiá\tSố lượng\tDanh mục\twho_made\twhen_made\tGhi chú";

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    ConsoleAccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TrademarkRulesets trademarkRulesets;

    @Autowired
    BulkProperties limits;

    @Autowired
    FilterRegistrationBean<BodyLimitFilter> bodyLimit;

    @Autowired
    MutableClock clock;

    @MockitoBean
    JavaMailSender mailSender;

    @BeforeEach
    void startInTheMiddleOfNovember() {
        clock.set(NOVEMBER_15);
    }

    private ConsoleUser user(PlanCode plan) {
        return userOf(PlanTestSupport.newClient(provisioning, clients, plan));
    }

    private int used(ConsoleUser user) {
        return PlanTestSupport.used(jdbc, user.clientId(), NOVEMBER);
    }

    private String prepare(ConsoleUser user, String marketplace, String text, String header) throws Exception {
        return mvc.perform(prepareRequest(user, marketplace, text, header)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    private String run(ConsoleUser user, String mappingHtml) throws Exception {
        return mvc.perform(runRequest(user, mappingHtml, Map.of(), Map.of(), null)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
    }

    /** A row of the table in {@link #HEADER}'s columns: a listing with every field the Etsy rules require. */
    private static String goodRow(String sku, String title) {
        return String.join("\t", sku, title, "A synthetic mug.", "mug", "ceramic", "19.99", "5", "Home", "i_did", "made_to_order", "");
    }

    private static String table(String... rows) {
        return HEADER + "\n" + String.join("\n", rows) + "\n";
    }

    // ---- the page and the guess

    @Test
    void thePageIsInTheMenuAndNothingOnItIsCached() throws Exception {
        MvcResult page = mvc.perform(get("/console/bulk").with(signedIn(user(PlanCode.FREE)))).andExpect(status().isOk()).andReturn();

        String html = page.getResponse().getContentAsString();
        assertThat(html).contains("Kiểm tra hàng loạt").contains("Dán từ Sheet").contains("Tải file")
                .contains("href=\"/console/bulk\"");
        assertThat(page.getResponse().getHeader("Cache-Control")).contains("no-store");

        // the other pages of the console list it too
        String editor = mvc.perform(get("/console/check").with(signedIn(user(PlanCode.FREE)))).andReturn().getResponse()
                .getContentAsString();
        assertThat(editor).contains("href=\"/console/bulk\"");
    }

    @Test
    void aPasteWithAHeaderRowGuessesEachColumnFromItsNameAndLeavesTheUnknownOneOut() throws Exception {
        String mapping = prepare(user(PlanCode.SELLER), "ETSY", table(goodRow("S-1", "Plain ceramic mug " + unique())), null);

        assertThat(selectedTargets(mapping)).containsExactly("sku", "title", "description", "tags", "tags", "price", "quantity",
                "category", "who_made", "when_made", "");
        assertThat(mapping).contains("1</strong> dòng dữ liệu").contains("Dòng đầu được đọc là tiêu đề")
                .doesNotContain("Đã áp dụng cách chọn cột");
    }

    @Test
    void aPasteWithoutAHeaderRowNamesTheColumnsLikeASheetAndGuessesNothing() throws Exception {
        String mapping = prepare(user(PlanCode.SELLER), "ETSY", "Plain ceramic mug " + unique() + "\tgift\n", null);

        assertThat(mapping).contains("Không có dòng tiêu đề").contains("Cột A").contains("Cột B");
        assertThat(selectedTargets(mapping)).containsExactly("", "");
    }

    @Test
    void aMappingThatNamesNoListingFieldComesBackToTheMappingStepAndChecksNothing() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        String mapping = prepare(seller, "ETSY", "Plain ceramic mug " + unique() + "\tgift\n", null);

        String again = mvc.perform(runRequest(seller, mapping, Map.of(), Map.of(), null)).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(again).contains("id=\"mapping-problems\"").contains("ít nhất một cột").contains("id=\"run-form\"");
        assertThat(used(seller)).isZero();
        assertThat(PlanTestSupport.countOf(jdbc, "listing_check", seller.clientId())).isZero();
    }

    @Test
    void aTargetGivenToTwoColumnsIsRefusedWithoutCheckingAnything() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        String mapping = prepare(seller, "ETSY", table(goodRow("S-1", "Plain ceramic mug " + unique())), null);

        String again = mvc.perform(runRequest(seller, mapping, Map.of(2, "title"), Map.of(), null)).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(again).contains("chỉ nhận một cột").contains("Tiêu đề, Mô tả");
        assertThat(used(seller)).isZero();
    }

    // ---- a run: order, statuses, cost

    @Test
    void theAnswerHasOneRowPerInputRowInTheInputOrderAndTheColumnToPasteBackLinesUpWithIt() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        String text = table(
                goodRow("S-1", "Plain ceramic mug " + unique()),
                goodRow("S-2", "Zorvex ceramic mug " + unique()),
                "\t\t\t\t\t\t\t\t\t\t",
                String.join("\t", "S-4", "Bad price mug " + unique(), "A synthetic mug.", "mug", "ceramic", "19,99", "5", "Home",
                        "i_did", "made_to_order", "a private note"),
                String.join("\t", "S-5", "Long tag mug " + unique(), "A synthetic mug.", "a very very long tag name",
                        "a very very long tag name", "19.99", "5", "Home", "i_did", "made_to_order", ""));

        String result = run(seller, prepare(seller, "ETSY", text, null));

        List<ResultRow> rows = resultRows(result);
        assertThat(rows).extracting(ResultRow::sku).containsExactly("S-1", "S-2", null, "S-4", "S-5");
        assertThat(rows).extracting(ResultRow::line).as("the line of the text where each row starts").containsExactly(2, 3, 4, 5, 6);
        assertThat(rows.get(0).status()).isIn("Đạt", "Cảnh báo");
        assertThat(rows.get(1).status()).as("a trademark risk of HIGH").isEqualTo("Cảnh báo");
        assertThat(rows.get(2).status()).isEqualTo("Dòng trống");
        assertThat(rows.get(3).status()).isEqualTo("Không đọc được");
        assertThat(rows.get(4).status()).as("an ERROR of the rules").isEqualTo("Lỗi");

        // the block to paste back: a header line, then one line per input row, in the same order, two cells each
        List<String> lines = sheetLines(result);
        assertThat(lines).hasSize(rows.size() + 1);
        assertThat(lines.get(0)).isEqualTo("Trạng thái\tTóm tắt");
        for (int i = 0; i < rows.size(); i++) {
            String[] cells = lines.get(i + 1).split("\t", -1);
            assertThat(cells).as("line " + i + " has two cells").hasSize(2);
            if (rows.get(i).status().equals("Dòng trống")) {
                assertThat(lines.get(i + 1)).as("a blank input row is a blank line").isEqualTo("\t");
            } else {
                assertThat(cells[0]).isEqualTo(rows.get(i).status());
                assertThat(cells[1]).isNotBlank();
            }
        }
        assertThat(lines.get(2)).as("the trademark finding is the main one of S-2").contains("Zorvex");
        assertThat(lines.get(4)).startsWith("Không đọc được\t").contains("19,99");
        assertThat(result).doesNotContain("a private note");
    }

    @Test
    void aRunCostsOneUnitPerNewResultAndTheSameTableAgainCostsNothing() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        String text = table(goodRow("S-1", "Plain ceramic mug " + unique()), goodRow("S-2", "Plain ceramic cup " + unique()),
                String.join("\t", "S-3", "Unreadable " + unique(), "", "", "", "$5", "", "", "", "", ""));

        String mapping = prepare(seller, "ETSY", text, null);
        assertThat(mapping).contains("Tối đa 6 đơn vị quota cho 3 dòng");
        run(seller, mapping);

        assertThat(used(seller)).as("two listings, rules and trademark each; the unreadable row is free").isEqualTo(4);

        String again = run(seller, prepare(seller, "ETSY", text, null));
        assertThat(used(seller)).as("every result is stored").isEqualTo(4);
        assertThat(resultRows(again)).hasSize(3);
    }

    @Test
    void aPlanWithoutTheTrademarkPackChecksTheRulesAndSaysTheTrademarkWasNotChecked() throws Exception {
        ConsoleUser free = user(PlanCode.FREE);
        String text = table(goodRow("S-1", "Plain ceramic mug " + unique()));

        String result = run(free, prepare(free, "ETSY", text, null));

        assertThat(result).contains("Chưa kiểm tra nhãn hiệu cho mọi dòng").contains("gói FREE không gồm kiểm tra nhãn hiệu");
        assertThat(sheetLines(result).get(1)).contains("[Chưa kiểm tra nhãn hiệu:");
        assertThat(used(free)).isEqualTo(1);
    }

    @Test
    void theTrademarkDisclaimerIsOnThePageNextToTheTableAndInEachRowDetail() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        String result = run(seller, prepare(seller, "ETSY", table(goodRow("S-1", "Zorvex ceramic mug " + unique())), null));

        String disclaimer = trademarkRulesets.ruleset().disclaimer();
        assertThat(result).contains(disclaimer).contains("id=\"result-disclaimer\"").contains("không phải lời xác nhận của sàn");
        assertThat(result.split(Pattern.quote(disclaimer), -1).length - 1).as("the page and the row detail").isGreaterThan(1);
        assertThat(result).containsPattern("(?i)Nhãn trong danh mục: zorvex");
    }

    @Test
    void aRunWithAUsedUpQuotaAtTheStartIsRefusedBeforeAnythingIsChecked() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        PlanTestSupport.setUsed(jdbc, seller.clientId(), NOVEMBER, PlanTestSupport.quota(jdbc, PlanCode.SELLER));
        String mapping = prepare(seller, "ETSY", table(goodRow("S-1", "No quota mug " + unique())), null);

        String refused = mvc.perform(runRequest(seller, mapping, Map.of(), Map.of(), null)).andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();

        assertThat(refused).contains("Quota tháng đã hết").contains("2026-12-01").contains("Chưa kiểm tra dòng nào");
        assertThat(PlanTestSupport.countOf(jdbc, "listing_check", seller.clientId())).isZero();
    }

    @Test
    void whenTheQuotaRunsOutInTheMiddleTheRowsAlreadyStoredAreStillAnsweredAndTheRestAreNotChecked() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        String first = table(goodRow("S-1", "First mug " + unique()));
        run(seller, prepare(seller, "ETSY", first, null));
        assertThat(used(seller)).isEqualTo(2);
        // two units left: the stored first row is free, the second needs two and the third needs two more
        PlanTestSupport.setUsed(jdbc, seller.clientId(), NOVEMBER, PlanTestSupport.quota(jdbc, PlanCode.SELLER) - 2);

        String both = first + goodRow("S-2", "Second mug " + unique()) + "\n" + goodRow("S-3", "Third mug " + unique()) + "\n";
        String result = run(seller, prepare(seller, "ETSY", both, null));

        List<ResultRow> rows = resultRows(result);
        assertThat(rows.get(0).status()).as("answered from the stored result").isIn("Đạt", "Cảnh báo");
        assertThat(rows.get(1).status()).as("the second listing used the last two units").isIn("Đạt", "Cảnh báo");
        assertThat(rows.get(2).status()).as("nothing left for the third").isEqualTo("Chưa kiểm tra");
        assertThat(result).contains("Quota hết giữa chừng");
        assertThat(used(seller)).isEqualTo(PlanTestSupport.quota(jdbc, PlanCode.SELLER));
    }

    // ---- cells that hold line breaks and tabs

    @Test
    void aCellWithALineBreakAndATabInQuotesIsOneCellAndTheLinesAfterItKeepTheirNumbers() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        String text = "Tiêu đề\tMô tả\tSKU\n"
                + "Mug " + unique() + "\t\"line one\nline two\twith a tab and \"\"quotes\"\"\"\tQ-1\n"
                + "Cup " + unique() + "\tplain\tQ-2\n";

        String mapping = prepare(seller, "ETSY", text, null);
        assertThat(mapping).contains("2</strong> dòng dữ liệu");
        String result = run(seller, mapping);

        List<ResultRow> rows = resultRows(result);
        assertThat(rows).extracting(ResultRow::sku).containsExactly("Q-1", "Q-2");
        assertThat(rows).extracting(ResultRow::line).as("the quoted line break counts: the second row starts on line 4")
                .containsExactly(2, 4);
        assertThat(rows).extracting(ResultRow::status).doesNotContain("Không đọc được");
        assertThat(sheetLines(result)).as("header line and two rows, whatever the cell held").hasSize(3);
    }

    @Test
    void aPasteWithoutAHeaderRowIsCheckedFromTheColumnsTheSellerChose() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        String text = "Plain ceramic mug " + unique() + "\tmug|ceramic|gift\nPlain ceramic cup " + unique() + "\tcup|tea\n";
        String mapping = prepare(seller, "ETSY", text, null);

        String result = mvc.perform(runRequest(seller, mapping, Map.of(0, "title", 1, "tags"), Map.of("tags", "pipe"), false))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        List<ResultRow> rows = resultRows(result);
        assertThat(rows).extracting(ResultRow::line).containsExactly(1, 2);
        assertThat(rows).extracting(ResultRow::status).doesNotContain("Không đọc được", "Chưa kiểm tra");
        assertThat(sheetLines(result)).as("no header line when the table had no header").hasSize(2);
        // the tags were cut at the separator the seller chose: short tags, none too long, none repeated
        assertThat(result).doesNotContain("tag_length").doesNotContain("tag_duplicate");
    }

    // ---- the mapping an account used

    @Test
    void theMappingTheAccountUsedIsAppliedTheNextTimeByColumnNameAndStoresNoCellValue() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        UUID accountId = accounts.save(new ConsoleAccount(seller.email(), seller.clientId())).getId();
        String title = "Remembered mug " + unique();
        String first = "Tên hàng\tNhãn phụ\n" + title + "\tmug|ceramic\n";
        String mapping = prepare(seller, "ETSY", first, "yes");
        assertThat(selectedTargets(mapping)).as("nothing the page can guess").containsExactly("", "");

        mvc.perform(runRequest(seller, mapping, Map.of(0, "title", 1, "tags"), Map.of("tags", "pipe"), true)).andExpect(status().isOk());

        String stored = jdbc.queryForObject("SELECT mapping::text FROM console_bulk_mapping WHERE account_id = ? AND marketplace = 'ETSY'",
                String.class, accountId);
        assertThat(stored).contains("Tên hàng").contains("Nhãn phụ").contains("title").contains("pipe");
        assertThat(stored).as("labels and targets, never a cell").doesNotContain(title).doesNotContain("ceramic");

        // another sheet with the same headers in another order: the header is recognized and the mapping follows the names
        String second = "Nhãn phụ\tTên hàng\ngift|tea\tAnother mug " + unique() + "\n";
        String again = prepare(seller, "ETSY", second, null);
        assertThat(selectedTargets(again)).containsExactly("tags", "title");
        assertThat(BulkPages.selected(again, "sep-tags")).isEqualTo("pipe");
        assertThat(again).contains("Đã áp dụng cách chọn cột bạn dùng lần trước").contains("Dòng đầu được đọc là tiêu đề");
        assertThat(BulkPages.hidden(again, "header")).isEqualTo("yes");
    }

    @Test
    void aMappingIsNotRememberedWhenTheBoxIsNotTickedOrForAnotherMarketplaceOrAnotherAccount() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        accounts.save(new ConsoleAccount(seller.email(), seller.clientId()));
        ConsoleUser other = user(PlanCode.SELLER);
        accounts.save(new ConsoleAccount(other.email(), other.clientId()));
        // two columns the page cannot guess (a table of a single unknown column is read as the titles)
        String text = "Tên hàng\tGhi chú\nWithout the box " + unique() + "\tnote\n";
        String mapping = prepare(seller, "ETSY", text, "yes");

        mvc.perform(runRequest(seller, mapping, Map.of(0, "title"), Map.of(), false)).andExpect(status().isOk());
        assertThat(selectedTargets(prepare(seller, "ETSY", text, "yes"))).as("box not ticked").containsExactly("", "");

        mvc.perform(runRequest(seller, mapping, Map.of(0, "title"), Map.of(), true)).andExpect(status().isOk());
        assertThat(selectedTargets(prepare(seller, "ETSY", text, "yes"))).containsExactly("title", "");
        assertThat(selectedTargets(prepare(seller, "AMAZON", text, "yes"))).as("another marketplace").containsExactly("", "");
        assertThat(selectedTargets(prepare(other, "ETSY", text, "yes"))).as("another account").containsExactly("", "");
    }

    // ---- variations

    @Test
    void rowsWithTheSameParentSkuAreOneFamilyAndItsFindingsGoToTheRowsTheyNameAtOneUnitPerFamily() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        String family = "TEE-P-" + unique();
        String text = "SKU\tTiêu đề\tSKU cha\tMàu\tSize\n"
                + "TEE-1\tFirst tee " + unique() + "\t" + family + "\tRed\tS\n"
                + "TEE-2\tSecond tee " + unique() + "\t" + family + "\tRed\tM\n"
                + "TEE-3\tThird tee " + unique() + "\t" + family + "\tRed\tS\n";
        String mapping = prepare(seller, "ETSY", text, null);
        assertThat(selectedTargets(mapping)).containsExactly("sku", "title", "parent_sku", "attribute", "attribute");

        String result = run(seller, mapping);

        assertThat(result).contains("<h4>Biến thể</h4>").contains("duplicate_combination");
        assertThat(resultRows(result)).hasSize(3);
        // three listings with a title only: rules and trademark each (6), and one unit for the family
        assertThat(used(seller)).isEqualTo(7);
        run(seller, prepare(seller, "ETSY", text, null));
        assertThat(used(seller)).as("the stored results answer a second run").isEqualTo(7);
    }

    @Test
    void aPlanWithoutTheVariationPackStillChecksTheRowsAndSaysTheFamilyWasNotChecked() throws Exception {
        ConsoleUser free = user(PlanCode.FREE);
        String text = "SKU\tTiêu đề\tSKU cha\tMàu\n"
                + "TEE-1\tFirst tee " + unique() + "\tTEE-P\tRed\n"
                + "TEE-2\tSecond tee " + unique() + "\tTEE-P\tBlue\n";

        String result = run(free, prepare(free, "ETSY", text, null));

        assertThat(result).contains("Chưa kiểm tra biến thể: gói FREE không gồm kiểm tra biến thể.");
        assertThat(resultRows(result)).extracting(ResultRow::status).doesNotContain("Chưa kiểm tra");
    }

    // ---- what is kept, and what a browser must not do with it

    @Test
    void thePastedTextIsNotKeptByTheServerBetweenTheTwoStepsOrAfterTheRun() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        String secret = "Confidential mug " + unique();
        MvcResult prepared = mvc.perform(prepareRequest(seller, "ETSY", table(goodRow("S-1", secret)), null)).andExpect(status().isOk())
                .andReturn();
        MvcResult done = mvc.perform(runRequest(seller, prepared.getResponse().getContentAsString(), Map.of(), Map.of(), null))
                .andExpect(status().isOk()).andReturn();

        for (MvcResult result : List.of(prepared, done)) {
            HttpSession session = result.getRequest().getSession(false);
            if (session != null) {
                for (String name : Collections.list(session.getAttributeNames())) {
                    assertThat(String.valueOf(session.getAttribute(name))).as("session attribute " + name).doesNotContain(secret);
                }
            }
            assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM console_bulk_mapping WHERE mapping::text LIKE ?", Integer.class,
                "%" + secret + "%")).isZero();
    }

    @Test
    void whatWasPastedComesBackEscapedNeverAsMarkup() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        String text = "SKU\tTiêu đề\n<img src=x onerror=alert(1)>\t<script>alert('x')</script> " + unique() + "\n";
        String mapping = prepare(seller, "ETSY", text, null);
        String result = run(seller, mapping);

        for (String page : List.of(mapping, result)) {
            assertThat(page).doesNotContain("<script>alert").doesNotContain("<img src=x").contains("&lt;");
        }
        assertThat(resultRows(result).get(0).sku()).isEqualTo("<img src=x onerror=alert(1)>");
    }

    @Test
    void aFormulaInAFindingOrASkuIsDefusedInTheColumnToPasteBack() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);
        String text = "SKU\tTiêu đề\n=HYPERLINK(\"x\")\t=1+1 " + unique() + "\n";

        String result = run(seller, prepare(seller, "ETSY", text, null));

        for (String line : sheetLines(result)) {
            for (String cell : line.split("\t", -1)) {
                assertThat(cell).as("a cell that starts like a formula").doesNotStartWith("=");
            }
        }
        assertThat(result).contains("href=\"data:text/csv;charset=utf-8;base64,");
    }

    @Test
    void aPostWithoutACsrfTokenIsRefusedAndNothingIsChecked() throws Exception {
        ConsoleUser seller = user(PlanCode.SELLER);

        mvc.perform(multipart("/console/bulk/prepare").with(signedIn(seller)).param("source", "paste").param("marketplace", "ETSY")
                .param("text", table(goodRow("S-1", "No token " + unique())))).andExpect(status().isForbidden());
        mvc.perform(multipart("/console/bulk/run").with(signedIn(seller)).param("marketplace", "ETSY").param("data", "x"))
                .andExpect(status().isForbidden());
        assertThat(used(seller)).isZero();
    }

    @Test
    void anUnknownMarketplaceAndARequestWithoutASignInAreRefused() throws Exception {
        mvc.perform(prepareRequest(user(PlanCode.FREE), "WALMART", "x", null)).andExpect(status().isBadRequest());
        mvc.perform(get("/console/bulk")).andExpect(status().is3xxRedirection());
        mvc.perform(multipart("/console/bulk/prepare").with(csrf()).param("source", "paste").param("marketplace", "ETSY")
                .param("text", "x")).andExpect(status().is3xxRedirection());
    }

    @Test
    void theBodyLimitAllowsAMultipartPostToTheTwoPagesOnlyUpToTheirOwnLimit() throws Exception {
        BodyLimitFilter filter = bodyLimit.getFilter();
        long allowed = limits.requestLimitBytes();

        assertThat(statusOf(filter, "/console/bulk/prepare", allowed - 1)).isEqualTo(200);
        assertThat(statusOf(filter, "/console/bulk/run", allowed - 1)).isEqualTo(200);
        assertThat(statusOf(filter, "/console/bulk/prepare", allowed + 1)).isEqualTo(413);
        assertThat(statusOf(filter, "/console/check", 300_000)).as("another page keeps the ordinary limit").isEqualTo(413);
        assertThat(statusOf(filter, "/api/v1/checks", 300_000)).isEqualTo(413);
    }

    private static int statusOf(BodyLimitFilter filter, String path, long declaredBytes) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("multipart/form-data; boundary=x");
        // the mock request reports the length of its content
        request.setContent(new byte[(int) declaredBytes]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain.getRequest() == null ? response.getStatus() : 200;
    }
}
