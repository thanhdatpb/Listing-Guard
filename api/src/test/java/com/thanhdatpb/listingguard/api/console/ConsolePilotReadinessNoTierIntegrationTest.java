package com.thanhdatpb.listingguard.api.console;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import jakarta.servlet.http.Cookie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The "Sẵn sàng" page (ADR-053) over a catalog without tiers (a jsonl-1 catalog) and the ruleset that has no calibration, a deployment
 * whose default scope is the full check, fix suggestions off and one AI flag on: every line the pilot template would have the other way
 * says "missing" and what to do, and the lines that are in order still say so.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.pilot=true",
        "listing-guard.console.default-locale=vi",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "spring.mail.host=mail.example.test",
        "listing-guard.console.bulk.default-scope=full",
        "listing-guard.fixes.enabled=false",
        "listing-guard.embedding.provider=hashing"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class ConsolePilotReadinessNoTierIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ClientProvisioning provisioning;
    @Autowired ClientRepository clients;
    @Autowired ConsoleAccountRepository accounts;
    @MockitoBean JavaMailSender mailSender;

    private ConsoleUser admin() {
        ConsoleUser user = userOf(PlanTestSupport.newClient(provisioning, clients, PlanCode.SELLER));
        accounts.saveAndFlush(new ConsoleAccount(user.email(), user.clientId(), ConsoleRole.ADMIN));
        return user;
    }

    private static String state(String page, String key) {
        Matcher row = Pattern.compile("<tr id=\"readiness-" + key + "\" data-state=\"(ok|missing)\"").matcher(page);
        assertThat(row.find()).as("the line " + key).isTrue();
        return row.group(1);
    }

    @Test
    void aCatalogWithoutTiersAFullDefaultScopeAndAnAiFlagOnAreMissingAndTheRestIsInOrder() throws Exception {
        String page = mvc.perform(get("/console/readiness").with(signedIn(admin()))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(state(page, "catalog")).as("served and not the demo").isEqualTo("ok");
        assertThat(state(page, "tier")).isEqualTo("missing");
        assertThat(state(page, "wordlist")).isEqualTo("missing");
        assertThat(state(page, "ruleset")).as("no calibration by tier").isEqualTo("missing");
        assertThat(state(page, "scope")).isEqualTo("missing");
        assertThat(state(page, "fixes")).isEqualTo("missing");
        assertThat(state(page, "ai-embedding")).isEqualTo("missing");
        assertThat(state(page, "ai-llm")).isEqualTo("ok");
        assertThat(state(page, "watchlist")).as("an empty watchlist").isEqualTo("missing");
        assertThat(state(page, "users")).as("an administrator and no seller").isEqualTo("missing");
        assertThat(page).contains("Catalog không có tier (jsonl-1, hoặc chưa phục vụ catalog nào).")
                .contains("Chưa xác định: catalog không có tier.").contains("không hiệu chỉnh theo tier.")
                .contains("Kiểm đầy đủ để đăng.").contains("0 mục đang bật.").contains("1 ADMIN, 0 MEMBER.").contains("Đang bật")
                .contains("Đang tắt").contains("Chuyển đổi lại bằng hf-convert để có jsonl-2")
                .contains("Còn 8 mục thiếu (5/13 mục đạt).");
    }

    @Test
    void theSameLinesSpeakEnglishOnAnEnglishPage() throws Exception {
        String page = mvc.perform(get("/console/readiness").with(signedIn(admin())).cookie(new Cookie(ConsoleLocaleResolver.COOKIE, "en")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("The catalog has no tiers (jsonl-1, or none is served).").contains("Not known: the catalog has no tiers.")
                .contains("without the calibration by tier.").contains("Full check to publish.").contains("0 entries are on.")
                .contains("1 ADMIN, 0 MEMBER.").contains("8 of 13 items are missing (5 are in order).")
                .contains("Convert again with hf-convert").doesNotContain("Thiếu").doesNotContain("Đạt");
    }
}
