package com.thanhdatpb.listingguard.api.console.bulk;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.prepareRequest;
import static com.thanhdatpb.listingguard.api.console.bulk.BulkPages.runRequest;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.rewrite.StubLlm.answer;
import static com.thanhdatpb.listingguard.api.rewrite.StubLlm.candidate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.rewrite.RewriteTestConfig;
import com.thanhdatpb.listingguard.api.rewrite.StubLlm;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.web.util.HtmlUtils;

/**
 * The "Gợi ý sửa" button on the rows of the bulk result (ADR-037): only a row with an ERROR or a trademark finding carries its listing
 * and a button, and the listing the page carries is what the button posts back. The model is a stub.
 */
@SpringBootTest(properties = {
        "listing-guard.console.enabled=true",
        "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test",
        "listing-guard.console.default-locale=vi",
        "spring.mail.host=mail.example.test",
        "listing-guard.llm.rewrite.enabled=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, RewriteTestConfig.class})
class ConsoleBulkRewriteIntegrationTest {

    private static final String HEADER = "SKU\tTiêu đề\tMô tả\tTag 1\tTag 2\tGiá\tSố lượng\tDanh mục\twho_made\twhen_made\tGhi chú";
    private static final String TOO_LONG = "shirt " + "long ".repeat(35).strip();

    @Autowired MockMvc mvc;
    @Autowired ClientProvisioning provisioning;
    @Autowired ClientRepository clients;
    @Autowired StubLlm llm;
    @MockitoBean JavaMailSender mailSender;

    @BeforeEach
    void reset() {
        llm.reset();
    }

    private static String row(String sku, String title) {
        return String.join("\t", sku, title, "A synthetic mug.", "mug", "ceramic", "19.99", "5", "Home", "i_did", "made_to_order", "");
    }

    private String bulkResult(ConsoleUser user, String... rows) throws Exception {
        String text = HEADER + "\n" + String.join("\n", rows) + "\n";
        String mapping = mvc.perform(prepareRequest(user, "ETSY", text, null)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return mvc.perform(runRequest(user, mapping, Map.of(), Map.of(), null)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void onlyARowWithAnErrorHasTheButtonAndItsListingAndTheButtonPostsThatListingBack() throws Exception {
        ConsoleUser user = userOf(newClient(provisioning, clients, PlanCode.PRO));
        String result = bulkResult(user, row("OK-1", "Plain cotton mug"), row("BAD-1", TOO_LONG));

        assertThat(result).contains("/console/rewrite.js");
        assertThat(count(result, "action=\"/console/rewrite/bulk\"")).as("one button, for the row with an ERROR").isEqualTo(1);
        Matcher listing = Pattern.compile("name=\"listing\" value=\"([^\"]*)\"").matcher(result);
        assertThat(listing.find()).isTrue();
        String json = HtmlUtils.htmlUnescape(listing.group(1));
        assertThat(json).contains(TOO_LONG).doesNotContain("Plain cotton mug");
        assertThat(result).contains("name=\"marketplace\" value=\"ETSY\"").contains("id=\"rewrite-result-2\"");

        llm.reply(answer(candidate("Plain cotton shirt")));
        String panel = mvc.perform(post("/console/rewrite/bulk").with(signedIn(user)).with(csrf()).header("HX-Request", "true")
                        .param("marketplace", "ETSY").param("listing", json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(panel).contains("data-copy-text=\"Plain cotton shirt\"").contains("id=\"rewrite-panel\"");
    }

    @Test
    void aListingThatIsNotAnObjectOrNotJsonIsRefusedWithoutBeingEchoed() throws Exception {
        ConsoleUser user = userOf(newClient(provisioning, clients, PlanCode.PRO));

        for (String bad : new String[] {"[1,2]", "not json SECRET-TEXT", ""}) {
            String body = mvc.perform(post("/console/rewrite/bulk").with(signedIn(user)).with(csrf()).param("marketplace", "ETSY")
                            .param("listing", bad))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).doesNotContain("SECRET-TEXT");
        }
        assertThat(llm.requests()).isEmpty();
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
