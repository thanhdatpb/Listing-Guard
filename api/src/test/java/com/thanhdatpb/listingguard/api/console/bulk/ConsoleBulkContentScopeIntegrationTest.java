package com.thanhdatpb.listingguard.api.console.bulk;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.prepareRequest;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.resultRows;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.runRequest;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.sheetLines;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER_15;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.CheckScope;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.check.ScopedCheckResult;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleLocaleResolver;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.console.bulk.BulkPages.ResultRow;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.web.util.HtmlUtils;

/**
 * The content scope of the bulk check (ADR-052) with plans on and the synthetic trademark catalog loaded, as a seller who writes title,
 * tags and description in a Sheet uses it: a table with no category, price or quantity column. The console default here is the old
 * one (full); the pilot default and the configured default are in {@link ConsoleBulkScopeDefaultsIntegrationTest}.
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
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, PlanTestClockConfig.class})
class ConsoleBulkContentScopeIntegrationTest {

    /** What a Sheet of title, tags and description looks like: no category, price, quantity, who made or when made. */
    private static final String SHEET_HEADER = "SKU\tTiêu đề\tMô tả\tTag 1\tTag 2";

    private static final String MISSING = "Thiếu trường bắt buộc";

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
    ActiveRulesets rulesets;

    @Autowired
    CheckService checkService;

    @Autowired
    MutableClock clock;

    @MockitoBean
    JavaMailSender mailSender;

    @BeforeEach
    void startInTheMiddleOfNovember() {
        clock.set(NOVEMBER_15);
    }

    private ConsoleUser user() {
        return userOf(PlanTestSupport.newClient(provisioning, clients, PlanCode.SELLER));
    }

    private int used(ConsoleUser user) {
        return PlanTestSupport.used(jdbc, user.clientId(), NOVEMBER);
    }

    private static String sheetRow(String sku, String title, String tag1, String tag2) {
        return String.join("\t", sku, title, "A synthetic mug for tea.", tag1, tag2);
    }

    private static String sheet(String... rows) {
        return SHEET_HEADER + "\n" + String.join("\n", rows) + "\n";
    }

    private String prepare(ConsoleUser user, String text) throws Exception {
        return mvc.perform(prepareRequest(user, "ETSY", text, null)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    /** The run a browser sends from the mapping step, with the scope radio set to {@code scope} (null: the form names none). */
    private MockMultipartHttpServletRequestBuilder runIn(ConsoleUser user, String mapping, String scope, boolean remember) {
        MockMultipartHttpServletRequestBuilder request = runRequest(user, mapping, Map.of(), Map.of(), remember);
        return scope == null ? request : request.param("scope", scope);
    }

    private String run(ConsoleUser user, String text, String scope) throws Exception {
        return mvc.perform(runIn(user, prepare(user, text), scope, false)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    private int titleLimit() {
        RulesetConfig config = rulesets.get(RulesetConfig.DEFAULT_PACK_ID, "ETSY").config();
        return config.rule("title_length").intParam("max_length");
    }

    private List<Map<String, Object>> storedChecks(ConsoleUser user) {
        return jdbc.queryForList("""
                SELECT scope, analysis_version, readiness_status, readiness_score, readiness_ready, content_hash
                FROM listing_check WHERE client_id = ? ORDER BY created_at, id""", user.clientId());
    }

    // ---- the content scope on a Sheet that has no category

    @Test
    void aSheetWithoutACategoryColumnIsNotAnErrorInContentScopeAndTheTableSaysWhatWasNotChecked() throws Exception {
        ConsoleUser seller = user();

        String result = run(seller, sheet(sheetRow("C-1", "Plain ceramic mug " + unique(), "mug", "ceramic"),
                sheetRow("C-2", "Plain ceramic cup " + unique(), "cup", "ceramic")), "content");

        List<ResultRow> rows = resultRows(result);
        assertThat(rows).extracting(ResultRow::status).containsExactly("Đạt (nội dung)", "Đạt (nội dung)");
        assertThat(result).doesNotContain(MISSING);
        assertThat(result).contains("Bảng không có cột: price, quantity, who_made, when_made, category — chưa kiểm các trường này.");
        assertThat(Pattern.compile("Bảng không có cột").matcher(result).results().count()).as("one note for the table, not one per row")
                .isEqualTo(1);
        assertThat(result).contains("Đạt (nội dung): 2");
        assertThat(rows.get(0).statusCss()).isEqualTo("clean");
        // the summary that goes back to the Sheet says what was not checked
        assertThat(sheetLines(result).get(1)).contains("chưa kiểm: price, quantity, who_made, when_made, category");
    }

    @Test
    void theStoredResultIsPartialNeverReadyAndNamesTheRuleThatLeftFieldsOut() throws Exception {
        ConsoleUser seller = user();
        run(seller, sheet(sheetRow("C-1", "Plain ceramic mug " + unique(), "mug", "ceramic")), "content");

        Map<String, Object> stored = storedChecks(seller).get(0);

        assertThat(stored.get("scope")).isEqualTo("CONTENT");
        assertThat(stored.get("readiness_status")).isEqualTo("PARTIAL");
        assertThat(stored.get("readiness_score")).isNull();
        assertThat(stored.get("readiness_ready")).isEqualTo(false);
        assertThat((String) stored.get("analysis_version")).contains("+content:price,quantity,who_made,when_made,category");
    }

    @Test
    void theCheckServiceReportsSkippedRuleIdsAndPartialReadinessForAContentResult() {
        ConsoleUser seller = user();
        ObjectNode listing = new ObjectMapper().createObjectNode().put("title", "Plain ceramic mug " + unique())
                .put("description", "A synthetic mug for tea.");
        listing.putArray("tags").add("mug").add("ceramic");

        ScopedCheckResult scoped = checkService.checkDetailed(seller.clientId(), new CheckRequest("ETSY", listing),
                CheckScope.content(Set.of("title", "description", "tags")));

        assertThat(scoped.scope()).isEqualTo(CheckScope.Kind.CONTENT);
        assertThat(scoped.skippedRuleIds()).containsExactly("required_fields");
        assertThat(scoped.skippedFields()).containsExactly("price", "quantity", "who_made", "when_made", "category");
        assertThat(scoped.partial()).isTrue();
        assertThat(scoped.result().readiness().status()).isEqualTo("PARTIAL");
        assertThat(scoped.result().readiness().score()).isNull();
        assertThat(scoped.result().readiness().ready()).isFalse();
        assertThat(scoped.result().findings()).extracting(finding -> finding.ruleId()).doesNotContain("required_fields");
        assertThat(scoped.result().analysisVersion()).contains("+content");
        assertThat(scoped.result().rulesetVersion()).doesNotContain("+content");
    }

    @Test
    void everyOtherRuleStillSpeaksInContentScope() throws Exception {
        ConsoleUser seller = user();
        String longTitle = "Mug " + unique() + " " + "ceramic ".repeat(titleLimit() / 8 + 5);
        String longTag = "t".repeat(40);

        String result = run(seller, sheet(sheetRow("C-1", longTitle, longTag, "ceramic")), "content");

        ResultRow row = resultRows(result).get(0);
        assertThat(row.status()).isIn("Lỗi", "Cảnh báo");
        assertThat(row.findings()).isGreaterThan(0);
        assertThat(result).contains("title_length").contains("tag_length").doesNotContain(MISSING);
        assertThat(result).contains("Bảng không có cột:");
    }

    @Test
    void aColumnThatIsInTheTableButEmptyIsStillAnErrorInContentScope() throws Exception {
        ConsoleUser seller = user();
        String text = "SKU\tTiêu đề\tMô tả\tTag 1\tDanh mục\n"
                + String.join("\t", "C-1", "Plain ceramic mug " + unique(), "A synthetic mug for tea.", "mug", "") + "\n";

        String result = run(seller, text, "content");

        ResultRow row = resultRows(result).get(0);
        assertThat(row.status()).isEqualTo("Lỗi");
        assertThat(HtmlUtils.htmlUnescape(result)).contains(MISSING + " 'category'");
        assertThat(result).as("category has a column, so it is not among the fields left out")
                .contains("Bảng không có cột: price, quantity, who_made, when_made — chưa kiểm các trường này.");
        assertThat(storedChecks(seller).get(0).get("readiness_status")).isEqualTo("PARTIAL");
    }

    @Test
    void aTableWithAColumnForEveryRequiredFieldLeavesNothingOutAndSaysSo() throws Exception {
        ConsoleUser seller = user();
        String text = "SKU\tTiêu đề\tMô tả\tTag 1\tGiá\tSố lượng\tDanh mục\twho_made\twhen_made\n"
                + String.join("\t", "C-1", "Plain ceramic mug " + unique(), "A synthetic mug for tea.", "mug", "19.99", "5", "Home",
                "i_did", "made_to_order") + "\n";

        String result = run(seller, text, "content");

        assertThat(resultRows(result).get(0).status()).as("still named a content check").isEqualTo("Đạt (nội dung)");
        assertThat(result).contains("Bảng có cột cho mọi trường bắt buộc").doesNotContain("Bảng không có cột");
        assertThat(storedChecks(seller).get(0).get("readiness_status")).as("nothing was left out").isEqualTo("SCORED");
    }

    @Test
    void theReadinessPageOfThePilotDoesNotExistWithoutThePilotProfile() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/console/readiness")
                .with(com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn(user()))).andExpect(status().isNotFound());
    }

    // ---- the full scope is the old behaviour

    @Test
    void theFullScopeReportsEveryMissingFieldAndIsIdenticalToARunThatNamesNoScope() throws Exception {
        ConsoleUser seller = user();
        String text = sheet(sheetRow("F-1", "Plain ceramic mug " + unique(), "mug", "ceramic"),
                sheetRow("F-2", "Plain ceramic cup " + unique(), "cup", "ceramic"));

        String named = run(seller, text, "full");
        String none = run(seller, text, null);

        assertThat(resultRows(named)).extracting(ResultRow::status).containsExactly("Lỗi", "Lỗi");
        assertThat(HtmlUtils.htmlUnescape(named)).contains(MISSING + " 'price'").contains(MISSING + " 'category'");
        assertThat(named).doesNotContain("Đạt (nội dung)").doesNotContain("Bảng không có cột").doesNotContain("Phạm vi");
        assertThat(sheetLines(named).get(0)).as("the two columns it always had").isEqualTo("Trạng thái\tTóm tắt");
        assertThat(resultRows(none)).isEqualTo(resultRows(named));
        assertThat(sheetLines(none)).isEqualTo(sheetLines(named));
        assertThat(storedChecks(seller)).allSatisfy(check -> {
            assertThat(check.get("scope")).isEqualTo("FULL");
            assertThat(check.get("analysis_version")).as("the version of the ruleset, as it always was")
                    .isEqualTo(rulesets.get(RulesetConfig.DEFAULT_PACK_ID, "ETSY").config().version());
            assertThat(check.get("readiness_status")).isEqualTo("SCORED");
        });
    }

    @Test
    void anUnknownScopeIsRefusedAndNothingIsChecked() throws Exception {
        ConsoleUser seller = user();
        String mapping = prepare(seller, sheet(sheetRow("C-1", "Plain ceramic mug " + unique(), "mug", "ceramic")));

        mvc.perform(runIn(seller, mapping, "everything", false)).andExpect(status().isBadRequest());

        assertThat(used(seller)).isZero();
        assertThat(storedChecks(seller)).isEmpty();
    }

    // ---- what goes back to the Sheet and the CSV

    @Test
    void theColumnsToPasteBackAndTheCsvNameTheScopeAndAFullRunKeepsItsTwoColumns() throws Exception {
        ConsoleUser seller = user();
        String text = SHEET_HEADER + "\n" + sheetRow("C-1", "Plain ceramic mug " + unique(), "mug", "ceramic") + "\n"
                + "\t\t\t\t\n" + sheetRow("C-3", "Plain ceramic cup " + unique(), "cup", "ceramic") + "\n";

        String content = run(seller, text, "content");

        List<String> lines = sheetLines(content);
        assertThat(lines.get(0)).isEqualTo("Trạng thái\tTóm tắt\tPhạm vi");
        assertThat(lines.get(1).split("\t", -1)).hasSize(3);
        assertThat(lines.get(1)).startsWith("Đạt (nội dung)\t").endsWith("\tKiểm nội dung");
        assertThat(lines.get(2)).as("a blank row stays a blank line").isEqualTo("\t\t");
        assertThat(lines.get(3)).endsWith("\tKiểm nội dung");
        String csv = csvOf(content);
        assertThat(csv).contains("Dòng,SKU,Trạng thái,Số phát hiện,Tóm tắt,Phạm vi\r\n");
        assertThat(csv).contains("Đạt (nội dung)").contains(",Kiểm nội dung\r\n");

        String full = run(seller, text, "full");
        assertThat(csvOf(full)).contains("Dòng,SKU,Trạng thái,Số phát hiện,Tóm tắt\r\n").doesNotContain("Phạm vi");
    }

    private static String csvOf(String page) {
        Matcher href = Pattern.compile("href=\"data:text/csv;charset=utf-8;base64,([^\"]+)\"").matcher(page);
        assertThat(href.find()).as("the CSV link").isTrue();
        return new String(Base64.getDecoder().decode(href.group(1)), StandardCharsets.UTF_8);
    }

    // ---- cache and history

    @Test
    void aContentResultIsNeverReusedForAFullCheckAndTheOtherWayAround() throws Exception {
        ConsoleUser seller = user();
        String text = sheet(sheetRow("C-1", "Plain ceramic mug " + unique(), "mug", "ceramic"));

        run(seller, text, "content");
        assertThat(storedChecks(seller)).hasSize(1);
        int afterContent = used(seller);
        assertThat(afterContent).as("one listing-rules result and one trademark result").isEqualTo(2);

        run(seller, text, "content");
        assertThat(storedChecks(seller)).as("the same table in the same scope is answered from the store").hasSize(1);
        assertThat(used(seller)).isEqualTo(afterContent);

        String full = run(seller, text, "full");
        List<Map<String, Object>> stored = storedChecks(seller);
        assertThat(stored).as("the full check is a result of its own").hasSize(2);
        assertThat(used(seller)).as("one new listing-rules result; the trademark result does not depend on the scope")
                .isEqualTo(afterContent + 1);
        assertThat(stored.get(0).get("content_hash")).isEqualTo(stored.get(1).get("content_hash"));
        assertThat(stored).extracting(check -> check.get("scope")).containsExactly("CONTENT", "FULL");
        assertThat(stored.get(0).get("analysis_version")).isNotEqualTo(stored.get(1).get("analysis_version"));
        assertThat(resultRows(full).get(0).status()).as("the full check did not inherit the content verdict").isEqualTo("Lỗi");

        run(seller, text, "full");
        assertThat(storedChecks(seller)).hasSize(2);
    }

    @Test
    void twoContentResultsAreTheSameOnlyWhenTheyLeftOutTheSameFields() throws Exception {
        ConsoleUser seller = user();
        String title = "Plain ceramic mug " + unique();
        run(seller, sheet(sheetRow("C-1", title, "mug", "ceramic")), "content");
        String withCategory = "SKU\tTiêu đề\tMô tả\tTag 1\tTag 2\tDanh mục\n"
                + String.join("\t", "C-1", title, "A synthetic mug for tea.", "mug", "ceramic", "") + "\n";

        run(seller, withCategory, "content");

        List<Map<String, Object>> stored = storedChecks(seller);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).get("content_hash")).as("the same listing").isEqualTo(stored.get(1).get("content_hash"));
        assertThat(stored.get(0).get("analysis_version")).isNotEqualTo(stored.get(1).get("analysis_version"));
    }

    @Test
    void thePartialReadinessIsOnlyValidForAContentResult() throws Exception {
        ConsoleUser seller = user();
        run(seller, sheet(sheetRow("F-1", "Plain ceramic mug " + unique(), "mug", "ceramic")), "full");

        assertThatThrownBy(() -> jdbc.update("UPDATE listing_check SET readiness_status = 'PARTIAL', readiness_score = NULL, "
                + "readiness_ready = FALSE WHERE client_id = ?", seller.clientId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE listing_check SET scope = 'EVERYTHING' WHERE client_id = ?", seller.clientId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- the choice is remembered with the mapping

    @Test
    void theScopeIsRememberedWithTheColumnMappingAndStoresNoCellValue() throws Exception {
        ConsoleUser seller = user();
        UUID accountId = accounts.save(new ConsoleAccount(seller.email(), seller.clientId())).getId();
        String title = "Remembered mug " + unique();
        String text = sheet(sheetRow("C-1", title, "mug", "ceramic"));

        String first = prepare(seller, text);
        assertThat(first).as("the console default is the full check").containsPattern("id=\"scope-full\"[^>]*checked")
                .doesNotContainPattern("id=\"scope-content\"[^>]*checked");

        mvc.perform(runIn(seller, first, "content", true)).andExpect(status().isOk());

        String stored = jdbc.queryForObject("SELECT mapping::text FROM console_bulk_mapping WHERE account_id = ? AND marketplace = 'ETSY'",
                String.class, accountId);
        assertThat(stored).contains("\"scope\": \"content\"").doesNotContain(title);
        String second = prepare(seller, text);
        assertThat(second).containsPattern("id=\"scope-content\"[^>]*checked").doesNotContainPattern("id=\"scope-full\"[^>]*checked");

        mvc.perform(runIn(seller, second, "full", true)).andExpect(status().isOk());
        assertThat(prepare(seller, text)).containsPattern("id=\"scope-full\"[^>]*checked");

        mvc.perform(runIn(seller, second, "content", false)).andExpect(status().isOk());
        assertThat(prepare(seller, text)).as("a run without the box keeps what was remembered")
                .containsPattern("id=\"scope-full\"[^>]*checked");
    }

    @Test
    void aRefusedRunComesBackToTheMappingStepWithTheScopeTheSellerChose() throws Exception {
        ConsoleUser seller = user();
        String mapping = prepare(seller, "Plain ceramic mug " + unique() + "\tgift\n");

        String again = mvc.perform(runIn(seller, mapping, "content", false)).andExpect(status().isBadRequest()).andReturn().getResponse()
                .getContentAsString();

        assertThat(again).contains("id=\"mapping-problems\"").containsPattern("id=\"scope-content\"[^>]*checked");
    }

    // ---- both languages

    @Test
    void theScopeChoiceTheNoteAndTheStatusAreInTheLanguageOfThePage() throws Exception {
        ConsoleUser seller = user();
        String text = sheet(sheetRow("C-1", "Plain ceramic mug " + unique(), "mug", "ceramic"));

        for (String language : List.of("en", "vi")) {
            Cookie cookie = new Cookie(ConsoleLocaleResolver.COOKIE, language);
            String mapping = mvc.perform(prepareRequest(seller, "ETSY", text, null).cookie(cookie)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String result = mvc.perform(runIn(seller, mapping, "content", false).cookie(cookie)).andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString();

            for (String page : List.of(mapping, result)) {
                assertThat(page).as(language).contains("<html lang=\"" + language + "\"").doesNotContain("??").doesNotContain("#{")
                        .doesNotContain("${");
            }
            if (language.equals("en")) {
                assertThat(mapping).contains("What to check").contains("Check the content (only the columns in the table)")
                        .contains("Check everything needed to publish").doesNotContain("Kiểm nội dung (chỉ");
                assertThat(result).contains("The table has no column for: price, quantity, who_made, when_made, category.")
                        .contains("OK (content): 1").doesNotContain("Bảng không có cột");
                assertThat(resultRows(result).get(0).status()).isEqualTo("OK (content)");
                assertThat(sheetLines(result).get(0)).isEqualTo("Status\tSummary\tScope");
                assertThat(sheetLines(result).get(1)).endsWith("\tContent check").contains("not checked: price");
            } else {
                assertThat(mapping).contains("Phạm vi kiểm tra").contains("Kiểm nội dung (chỉ các cột có trong bảng)")
                        .contains("Kiểm đầy đủ để đăng").doesNotContain("What to check");
                assertThat(result).contains("Bảng không có cột: price, quantity, who_made, when_made, category — chưa kiểm các trường này.")
                        .contains("Đạt (nội dung): 1").doesNotContain("The table has no column");
                assertThat(resultRows(result).get(0).status()).isEqualTo("Đạt (nội dung)");
                assertThat(sheetLines(result).get(0)).isEqualTo("Trạng thái\tTóm tắt\tPhạm vi");
                assertThat(sheetLines(result).get(1)).endsWith("\tKiểm nội dung").contains("chưa kiểm: price");
            }
        }
    }
}
