package com.thanhdatpb.listingguard.api.console.bulk;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.fileRequest;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.prepareRequest;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.resultRows;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.runRequest;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.sheetLines;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The limits of the bulk check (3 rows and 1 KB here, so a test needs no large input) and a deployment with no trademark catalog
 * loaded and plans off (the default): a larger input is refused as a whole and never cut, nothing is checked or counted for it, and a
 * catalog that is not served leaves the rules running and is said on every row (ADR-029).
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "listing-guard.console.default-locale=vi",
        "spring.mail.host=mail.example.test",
        "listing-guard.console.bulk.max-rows=3",
        "listing-guard.console.bulk.max-bytes=1KB"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConsoleBulkSmallLimitsIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    JavaMailSender mailSender;

    private ConsoleUser user() {
        return userOf(PlanTestSupport.newClient(provisioning, clients, PlanCode.FREE));
    }

    private static String rows(int count) {
        StringBuilder text = new StringBuilder("SKU\tTiêu đề\n");
        for (int i = 1; i <= count; i++) {
            text.append("S-").append(i).append("\tPlain ceramic mug ").append(unique()).append('\n');
        }
        return text.toString();
    }

    private int stored(ConsoleUser user) {
        return PlanTestSupport.countOf(jdbc, "listing_check", user.clientId());
    }

    @Test
    void moreRowsThanTheLimitAreRefusedAsAWholeTheTextComesBackAndNothingIsCheckedOrCounted() throws Exception {
        ConsoleUser user = user();
        String text = rows(4);

        String html = mvc.perform(prepareRequest(user, "ETSY", text, null)).andExpect(status().isBadRequest()).andReturn()
                .getResponse().getContentAsString();

        assertThat(html).contains("Có 4 dòng dữ liệu").contains("vượt giới hạn 3").contains("id=\"paste-form\"")
                .contains("S-4");
        assertThat(stored(user)).isZero();
    }

    @Test
    void exactlyTheLimitPassesAndEveryRowIsAnswered() throws Exception {
        ConsoleUser user = user();

        String mapping = mvc.perform(prepareRequest(user, "ETSY", rows(3), null)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String result = mvc.perform(runRequest(user, mapping, Map.of(), Map.of(), null)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();

        assertThat(resultRows(result)).hasSize(3);
        assertThat(stored(user)).isEqualTo(3);
    }

    @Test
    void aTextOverTheByteLimitIsRefusedAndNotEchoedBack() throws Exception {
        ConsoleUser user = user();
        String text = "SKU\tTiêu đề\nS-1\t" + "x".repeat(1100) + "\n";

        String html = mvc.perform(prepareRequest(user, "ETSY", text, null)).andExpect(status().isPayloadTooLarge()).andReturn()
                .getResponse().getContentAsString();

        assertThat(html).contains("lớn hơn giới hạn 1 KB").doesNotContain("xxxxxxxxxx");
        assertThat(stored(user)).isZero();
    }

    @Test
    void aFileOverTheByteLimitIsRefused() throws Exception {
        ConsoleUser user = user();

        String html = mvc.perform(fileRequest(user, "ETSY", "big.csv", "x".repeat(1500))).andExpect(status().isPayloadTooLarge())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("File lớn hơn giới hạn 1 KB");
    }

    @Test
    void aFileOfAKnownFormatWithMoreRowsThanTheLimitIsRefusedBeforeAnythingIsChecked() throws Exception {
        ConsoleUser user = user();
        StringBuilder csv = new StringBuilder("SKU,TITLE,DESCRIPTION,PRICE,QUANTITY,TAGS,MATERIALS\n");
        for (int i = 1; i <= 4; i++) {
            csv.append("E-").append(i).append(",Mug ").append(unique()).append(",,,,,\n");
        }

        String html = mvc.perform(fileRequest(user, "ETSY", "export.csv", csv.toString())).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("nhiều hơn 3 dòng dữ liệu");
        assertThat(stored(user)).isZero();
    }

    @Test
    void aCatalogThatIsNotLoadedIsSaidOnThePageAndOnEveryRowAndTheRulesStillRun() throws Exception {
        ConsoleUser user = user();
        String mapping = mvc.perform(prepareRequest(user, "ETSY", rows(2), null)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(mapping).as("plans are off").contains("Gói và quota chưa được bật");
        String result = mvc.perform(runRequest(user, mapping, Map.of(), Map.of(), null)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();

        assertThat(result).contains("Chưa kiểm tra nhãn hiệu cho mọi dòng").contains("chưa nạp danh mục nhãn hiệu");
        assertThat(resultRows(result)).extracting(BulkPages.ResultRow::status).doesNotContain("Chưa kiểm tra", "Không đọc được");
        for (String line : sheetLines(result).subList(1, 3)) {
            assertThat(line).contains("[Chưa kiểm tra nhãn hiệu: chưa nạp danh mục nhãn hiệu");
        }
        assertThat(PlanTestSupport.used(jdbc, user.clientId(), java.time.LocalDate.now().withDayOfMonth(1))).as("plans are off").isZero();
    }
}
