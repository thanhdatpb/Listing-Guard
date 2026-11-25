package com.thanhdatpb.listingguard.api.console.bulk;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.prepareRequest;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.resultRows;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.runRequest;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.NOVEMBER_15;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.MutableClock;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
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
 * The default scope of the bulk check under the pilot profile (ADR-052): with {@code listing-guard.console.pilot=true} and no
 * {@code console.bulk.default-scope} a table is checked in content scope unless the seller chose otherwise. The same table with the
 * profile off (the default of every other deployment) is checked in full: {@link ConsoleBulkContentScopeIntegrationTest}.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.pilot=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "listing-guard.console.default-locale=vi",
        "spring.mail.host=mail.example.test",
        "listing-guard.plans.enabled=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, PlanTestClockConfig.class})
class ConsoleBulkScopeDefaultsIntegrationTest {

    private static final String TABLE_HEADER = "SKU\tTiêu đề\tMô tả\tTag 1\tTag 2";

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

    private static String table() {
        return TABLE_HEADER + "\n" + String.join("\t", "P-1", "Plain ceramic mug " + unique(), "A synthetic mug for tea.", "mug", "ceramic")
                + "\n";
    }

    private String prepare(ConsoleUser user, String text) throws Exception {
        return mvc.perform(prepareRequest(user, "ETSY", text, null)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    private List<Map<String, Object>> pilotDays(ConsoleUser user) {
        return jdbc.queryForList("SELECT scope, checked FROM seller_pilot_daily WHERE client_id = ? ORDER BY scope", user.clientId());
    }

    @Test
    void underThePilotProfileTheColumnStepOffersTheContentScopeAndARunThatNamesNoScopeIsAContentCheck() throws Exception {
        ConsoleUser seller = user();
        String mapping = prepare(seller, table());

        assertThat(mapping).containsPattern("id=\"scope-content\"[^>]*checked").doesNotContainPattern("id=\"scope-full\"[^>]*checked");

        String result = mvc.perform(runRequest(seller, mapping, Map.of(), Map.of(), false)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();

        assertThat(resultRows(result)).extracting(BulkPages.ResultRow::status).containsExactly("Đạt (nội dung)");
        assertThat(result).doesNotContain("Thiếu trường bắt buộc").contains("Bảng không có cột:");
    }

    @Test
    void aSellersRememberedChoiceOfTheFullScopeWinsOverThePilotDefault() throws Exception {
        ConsoleUser seller = user();
        accounts.save(new ConsoleAccount(seller.email(), seller.clientId()));
        String text = table();
        String mapping = prepare(seller, text);

        mvc.perform(runRequest(seller, mapping, Map.of(), Map.of(), true).param("scope", "full")).andExpect(status().isOk());

        String again = prepare(seller, text);
        assertThat(again).containsPattern("id=\"scope-full\"[^>]*checked").doesNotContainPattern("id=\"scope-content\"[^>]*checked");
    }

    @Test
    void thePilotCountsTheScopesApart() throws Exception {
        ConsoleUser seller = user();
        String mapping = prepare(seller, table());

        mvc.perform(runRequest(seller, mapping, Map.of(), Map.of(), false).param("scope", "content")).andExpect(status().isOk());
        mvc.perform(runRequest(seller, mapping, Map.of(), Map.of(), false).param("scope", "full")).andExpect(status().isOk());

        assertThat(pilotDays(seller)).extracting(row -> row.get("scope"), row -> row.get("checked"))
                .containsExactly(tuple("CONTENT", 1L), tuple("FULL", 1L));
    }
}
