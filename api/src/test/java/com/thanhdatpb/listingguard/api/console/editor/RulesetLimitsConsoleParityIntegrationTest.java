package com.thanhdatpb.listingguard.api.console.editor;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.checkPost;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.userOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestClockConfig;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The counters of the console editor and {@code GET /api/v1/rulesets/{marketplace}/limits} cannot differ: for each marketplace
 * (and for an Amazon product type) the {@code data-*} limits rendered in the page are compared with the limits in the JSON of
 * the endpoint, read independently of {@link EditorLimits}. Both come from {@code RulesetLimitsService}; this test holds that.
 * The settings are those of {@code ConsoleEditorFormIntegrationTest}, so the Spring context is shared.
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
class RulesetLimitsConsoleParityIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ClientProvisioning provisioning;

    @Autowired
    ClientRepository clients;

    @MockitoBean
    JavaMailSender mailSender;

    private ConsoleUser user() {
        return userOf(PlanTestSupport.newClient(provisioning, clients, PlanCode.FREE));
    }

    private JsonNode endpoint(String marketplace, String productType) throws Exception {
        String query = productType == null ? "" : "?productType=" + productType;
        return mapper.readTree(mvc.perform(get("/api/v1/rulesets/" + marketplace + "/limits" + query).header(HEADER, KEY_1))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("limits");
    }

    /** The strictest enabled limit of the field and kind in the JSON of the endpoint, or null when there is none. */
    private static Integer fromEndpoint(JsonNode limits, String field, String kind) {
        Integer strictest = null;
        for (JsonNode limit : limits) {
            if (limit.get("enabled").asBoolean() && limit.get("field").asText().equals(field)
                    && limit.get("kind").asText().equals(kind)) {
                strictest = strictest == null ? limit.get("value").asInt() : Math.min(strictest, limit.get("value").asInt());
            }
        }
        return strictest;
    }

    /** A {@code data-*} number of the counter that belongs to the field, or null when the counter or the attribute is absent. */
    private static Integer fromPage(String html, String counterFor, String attribute) {
        Matcher tag = Pattern.compile("<p[^>]*data-counter-for=\"" + counterFor + "\"[^>]*>").matcher(html);
        if (!tag.find()) {
            return null;
        }
        Matcher value = Pattern.compile("\\s" + attribute + "=\"(\\d+)\"").matcher(tag.group());
        return value.find() ? Integer.valueOf(value.group(1)) : null;
    }

    private void assertTheSame(String html, JsonNode limits) {
        assertThat(fromPage(html, "title", "data-limit")).as("title").isEqualTo(fromEndpoint(limits, "title", "MAX_LENGTH"));
        assertThat(fromPage(html, "tags", "data-limit-count")).as("tags count").isEqualTo(fromEndpoint(limits, "tags", "MAX_COUNT"));
        assertThat(fromPage(html, "tags", "data-limit-each")).as("each tag").isEqualTo(fromEndpoint(limits, "tags", "MAX_LENGTH"));
        assertThat(fromPage(html, "bulletPoints", "data-limit-count")).as("bullet count")
                .isEqualTo(fromEndpoint(limits, "bullet_points", "MAX_COUNT"));
        assertThat(fromPage(html, "bulletPoints", "data-limit-each")).as("each bullet")
                .isEqualTo(fromEndpoint(limits, "bullet_points", "MAX_LENGTH"));
        assertThat(fromPage(html, "searchTerms", "data-limit")).as("search terms")
                .isEqualTo(fromEndpoint(limits, "search_terms", "MAX_BYTES"));
    }

    @Test
    void theEtsyPageShowsTheLimitsOfTheEndpoint() throws Exception {
        String html = mvc.perform(get("/console/check").param("marketplace", "ETSY").with(signedIn(user())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode limits = endpoint("ETSY", null);

        assertThat(fromPage(html, "title", "data-limit")).as("the page has a title counter").isNotNull();
        assertThat(fromPage(html, "tags", "data-limit-count")).as("the page has a tag counter").isNotNull();
        assertTheSame(html, limits);
    }

    @Test
    void theAmazonPageShowsTheLimitsOfTheEndpoint() throws Exception {
        String html = mvc.perform(get("/console/check").param("marketplace", "AMAZON").with(signedIn(user())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode limits = endpoint("AMAZON", null);

        assertThat(fromPage(html, "searchTerms", "data-limit")).as("the page has a search terms counter").isNotNull();
        assertTheSame(html, limits);
    }

    @Test
    void theAmazonPageForAProductTypeShowsTheLimitsOfTheEndpointForThatProductType() throws Exception {
        // an unreadable price sends the form back at once, so nothing is checked and nothing is counted
        Map<String, String> form = new LinkedHashMap<>();
        form.put("marketplace", "AMAZON");
        form.put("title", "Synthetic suitcase");
        form.put("productType", "no-such-product-type");
        form.put("price", "not a price");
        String html = mvc.perform(checkPost(user(), form)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertTheSame(html, endpoint("AMAZON", "no-such-product-type"));
        assertThat(fromPage(html, "searchTerms", "data-limit")).isNotNull();
    }
}
