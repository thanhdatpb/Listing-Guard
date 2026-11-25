package com.thanhdatpb.listingguard.api.console.bulk;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.prepareRequest;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.resultRows;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.runRequest;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER_15;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.console.bulk.BulkPages.ResultRow;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.web.util.HtmlUtils;

/**
 * The synthetic Sheet of {@code docs/samples/sheet-content-sample.tsv} (eight invented listings with SKU, title, description and tags,
 * and no category, price or quantity column) checked in both scopes (ADR-052). The counts are the ones {@code docs/bulk-check.md} and
 * {@code docs/pilot-seller-guide.md} give: with the full check every row is an error because the Sheet has no category, and with the
 * content check only the rows with a real problem are.
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
class SheetContentSampleIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "docs", "samples", "sheet-content-sample.tsv");

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    MutableClock clock;

    @MockitoBean
    JavaMailSender mailSender;

    @BeforeEach
    void startInTheMiddleOfNovember() {
        clock.set(NOVEMBER_15);
    }

    private String run(ConsoleUser seller, String scope) throws Exception {
        String sample = Files.readString(SAMPLE, StandardCharsets.UTF_8);
        String mapping = mvc.perform(prepareRequest(seller, "ETSY", sample, null)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(BulkPages.selectedTargets(mapping)).as("the Sheet's own headers are recognized")
                .containsExactly("sku", "title", "description", "tags");
        MockMultipartHttpServletRequestBuilder request = runRequest(seller, mapping, Map.of(), Map.of(), false);
        return mvc.perform(request.param("scope", scope)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static Map<String, Long> statuses(String page) {
        return resultRows(page).stream().collect(Collectors.groupingBy(ResultRow::status, Collectors.counting()));
    }

    @Test
    void theSampleHasEightRowsAndNoCategoryPriceOrQuantityColumn() throws Exception {
        List<String> lines = Files.readAllLines(SAMPLE, StandardCharsets.UTF_8);

        assertThat(lines).hasSize(9);
        assertThat(lines.get(0)).isEqualTo("SKU\tTitle\tDescription\tTags");
        assertThat(lines).allSatisfy(line -> assertThat(line.split("\t", -1)).hasSize(4));
    }

    @Test
    void theFullCheckCallsEveryRowAnErrorForTheMissingFieldsAndTheContentCheckOnlyTheRowsWithARealProblem() throws Exception {
        ConsoleUser seller = userOf(PlanTestSupport.newClient(provisioning, clients, PlanCode.SELLER));

        String full = run(seller, "full");
        String content = run(seller, "content");

        assertThat(statuses(full)).as("before: the scope of the old check").containsExactly(Map.entry("Lỗi", 8L));
        assertThat(statuses(content)).as("after: the content scope")
                .containsOnly(Map.entry("Lỗi", 3L), Map.entry("Cảnh báo", 2L), Map.entry("Đạt (nội dung)", 3L));
        assertThat(resultRows(content)).filteredOn(row -> row.status().equals("Lỗi")).extracting(ResultRow::sku)
                .containsExactly("HC-003", "HC-004", "HC-006");
        assertThat(resultRows(content)).filteredOn(row -> row.status().equals("Cảnh báo")).extracting(ResultRow::sku)
                .containsExactly("HC-001", "HC-005");
        assertThat(content).contains("title_length").contains("tag_length").contains("duplicate_tags")
                .contains("title_promotional_terms");
        assertThat(HtmlUtils.htmlUnescape(content))
                .as("a column that is in the Sheet and empty is still an error: HC-006 has no description")
                .contains("Thiếu trường bắt buộc 'description'");
        assertThat(HtmlUtils.htmlUnescape(full)).contains("Thiếu trường bắt buộc 'category'");
        assertThat(content).doesNotContain("Thiếu trường bắt buộc 'category'");
    }
}
