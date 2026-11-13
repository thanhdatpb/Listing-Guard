package com.thanhdatpb.listingguard.api.console.bulk;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.fileRequest;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.resultRows;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.runRequest;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.selectedTargets;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.sheetLines;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER_15;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
 * The "Tải file" tab (ADR-029): a file of a format the input adapters know (ADR-025) is read with that adapter's own columns and
 * checked at once; any other table goes to the mapping step like a paste, with the delimiter found from its first row. Synthetic
 * files only.
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
class ConsoleBulkFileIntegrationTest {

    private static final String ETSY_HEADER = "SKU,TITLE,DESCRIPTION,PRICE,QUANTITY,TAGS,MATERIALS";
    private static final String AMAZON_HEADER = String.join("\t", "item_sku", "item_name", "brand_name", "product_description",
            "bullet_point1", "bullet_point2", "bullet_point3", "bullet_point4", "bullet_point5", "standard_price", "quantity",
            "generic_keywords", "feed_product_type", "parentage", "parent_sku");

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @Autowired
    JdbcTemplate jdbc;

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

    private String upload(ConsoleUser user, String marketplace, String name, String text, int status) throws Exception {
        return mvc.perform(fileRequest(user, marketplace, name, text)).andExpect(status().is(status)).andReturn().getResponse()
                .getContentAsString();
    }

    @Test
    void anEtsyExportIsRecognizedFromItsHeaderAndCheckedAtOnceWithTheSkuOfItsOwnColumn() throws Exception {
        String csv = ETSY_HEADER + "\nE-1,Plain ceramic mug " + unique() + ",\"A synthetic mug.\",19.99,5,\"mug,ceramic\",clay\n"
                + "E-2,Plain ceramic cup " + unique() + ",A synthetic cup.,12.50,2,\"cup,tea\",clay\n";

        String result = upload(user(), "ETSY", "export.csv", csv, 200);

        assertThat(result).doesNotContain("id=\"run-form\"").contains("id=\"result\"")
                .contains("File được nhận dạng là “etsy-listings-export”");
        List<ResultRow> rows = resultRows(result);
        assertThat(rows).extracting(ResultRow::sku).containsExactly("E-1", "E-2");
        assertThat(rows).extracting(ResultRow::line).containsExactly(2, 3);
        assertThat(rows).extracting(ResultRow::status).doesNotContain("Không đọc được", "Chưa kiểm tra");
        assertThat(sheetLines(result)).hasSize(3);
    }

    @Test
    void aFileWithAByteOrderMarkIsRecognizedToo() throws Exception {
        String csv = "﻿" + ETSY_HEADER + "\nE-1,Plain ceramic mug " + unique() + ",A synthetic mug.,19.99,5,\"mug,ceramic\",clay\n";

        String result = upload(user(), "ETSY", "export.csv", csv, 200);

        assertThat(resultRows(result)).extracting(ResultRow::sku).containsExactly("E-1");
    }

    @Test
    void aRowOfTheFileThatCannotBeReadIsSaidWithItsLineAndTheOthersAreChecked() throws Exception {
        String csv = ETSY_HEADER + "\nE-1,Plain ceramic mug " + unique() + ",A synthetic mug.,\"19,99\",5,\"mug,ceramic\",clay\n"
                + "E-2,Plain ceramic cup " + unique() + ",A synthetic cup.,12.50,2,\"cup,tea\",clay\n";

        String result = upload(user(), "ETSY", "export.csv", csv, 200);

        List<ResultRow> rows = resultRows(result);
        assertThat(rows.get(0).status()).isEqualTo("Không đọc được");
        assertThat(rows.get(1).status()).isNotEqualTo("Không đọc được");
        assertThat(sheetLines(result).get(1)).startsWith("Không đọc được\t").contains("price");
    }

    @Test
    void aFileOfAnotherMarketplaceThanTheOneChosenIsNotCheckedAndSaysWhich() throws Exception {
        ConsoleUser user = user();
        String csv = ETSY_HEADER + "\nE-1,Plain ceramic mug " + unique() + ",A synthetic mug.,19.99,5,\"mug,ceramic\",clay\n";

        String html = upload(user, "AMAZON", "export.csv", csv, 400);

        assertThat(html).contains("etsy-listings-export").contains("ETSY").contains("bạn đã chọn Amazon");
        assertThat(PlanTestSupport.countOf(jdbc, "listing_check", user.clientId())).isZero();
    }

    @Test
    void anAmazonFlatFileWithParentAndChildRowsIsCheckedRowByRowAndNoFamilyIsCheckedBecauseItsAttributesAreNotRead() throws Exception {
        ConsoleUser user = user();
        String parentRow = String.join("\t", "P-1", "Family tee " + unique(), "Acme", "A synthetic tee.", "one", "two", "three", "four",
                "five", "9.99", "1", "term", "SHIRT", "parent", "");
        String childRow = String.join("\t", "C-1", "Family tee red " + unique(), "Acme", "A synthetic tee.", "one", "two", "three",
                "four", "five", "9.99", "1", "term", "SHIRT", "child", "P-1");
        String tsv = AMAZON_HEADER + "\n" + parentRow + "\n" + childRow + "\n";

        String result = upload(user, "AMAZON", "flat.tsv", tsv, 200);

        assertThat(resultRows(result)).extracting(ResultRow::sku).containsExactly("P-1", "C-1");
        assertThat(result).contains("amazon-flat-file").contains("chưa kiểm tra biến thể").doesNotContain("<h4>Biến thể</h4>");
        assertThat(PlanTestSupport.countOf(jdbc, "variation_check", user.clientId())).as("no family was sent to the check").isZero();
    }

    @Test
    void aTableOfNoKnownFormatGoesToTheMappingStepWithTheDelimiterFoundFromItsFirstRow() throws Exception {
        ConsoleUser user = user();
        String title = "Mug, large " + unique();
        String csv = "Tiêu đề,Mô tả\n\"" + title + "\",A synthetic mug.\n";

        String mapping = upload(user, "ETSY", "mine.csv", csv, 200);

        assertThat(mapping).contains("id=\"run-form\"").doesNotContain("File được nhận dạng");
        assertThat(BulkPages.hidden(mapping, "delimiter")).isEqualTo("comma");
        assertThat(selectedTargets(mapping)).containsExactly("title", "description");

        String result = mvc.perform(runRequest(user, mapping, Map.of(), Map.of(), null)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(resultRows(result)).hasSize(1);
        assertThat(resultRows(result).get(0).status()).isNotEqualTo("Không đọc được");

        String semicolon = upload(user, "ETSY", "mine.csv", "Tiêu đề;Mô tả\nMug;desc\n", 200);
        assertThat(BulkPages.hidden(semicolon, "delimiter")).isEqualTo("semicolon");
        String tabs = upload(user, "ETSY", "mine.tsv", "Tiêu đề\tMô tả\nMug\tdesc\n", 200);
        assertThat(BulkPages.hidden(tabs, "delimiter")).isEqualTo("tab");
    }

    @Test
    void aFileThatIsNotUtf8IsRefusedWithAHowToAndNothingIsChecked() throws Exception {
        ConsoleUser user = user();
        byte[] latin1 = ("Tiêu de,Mô tả\nMug é,desc\n").getBytes(StandardCharsets.ISO_8859_1);

        String html = mvc.perform(fileRequest(user, "ETSY", "old.csv", latin1)).andExpect(status().isBadRequest()).andReturn()
                .getResponse().getContentAsString();

        assertThat(html).contains("không phải UTF-8").contains("CSV UTF-8");
        assertThat(PlanTestSupport.countOf(jdbc, "listing_check", user.clientId())).isZero();
    }

    @Test
    void aFileThatIsNotACsvOrIsEmptyOrHasAnUnclosedQuoteIsRefusedWithAReason() throws Exception {
        ConsoleUser user = user();

        assertThat(upload(user, "ETSY", "book.xlsx", "x", 400)).contains("Chỉ nhận file .csv, .tsv hoặc .txt");
        assertThat(mvc.perform(fileRequest(user, "ETSY", "empty.csv", new byte[0])).andExpect(status().isBadRequest()).andReturn()
                .getResponse().getContentAsString()).contains("Chưa chọn file");
        assertThat(upload(user, "ETSY", "bad.csv", "Tiêu đề,Mô tả\n\"never closed,x\n", 400)).contains("dòng 2");
        assertThat(upload(user, "ETSY", "header-only.csv", "Tiêu đề,Mô tả\n", 400)).contains("Chỉ có dòng tiêu đề");
    }
}
