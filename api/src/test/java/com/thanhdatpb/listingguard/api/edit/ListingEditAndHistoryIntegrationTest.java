package com.thanhdatpb.listingguard.api.edit;

import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.history.CheckHistoryService;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"listing-guard.console.enabled=true", "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test", "spring.mail.host=mail.example.test"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class ListingEditAndHistoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClientProvisioning provisioning;
    @Autowired ClientRepository clients;
    @Autowired WatchlistService watchlists;
    @Autowired CheckHistoryService history;
    @Autowired ConsoleAccountRepository accounts;
    @MockitoBean JavaMailSender mailSender;

    private ObjectNode listing(String title) {
        ObjectNode node = mapper.createObjectNode();
        node.put("title", title);
        return node;
    }

    private JsonNode edit(TestClient client, JsonNode before, JsonNode after) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("marketplace", "ETSY");
        request.set("before", before);
        request.set("after", after);
        String response = mvc.perform(post("/api/v1/listing-edits").header("X-API-Key", client.key())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response);
    }

    private void check(TestClient client, String title) throws Exception {
        ObjectNode listing = listing(title);
        ObjectNode request = mapper.createObjectNode().put("marketplace", "ETSY");
        request.set("listing", listing);
        mvc.perform(post("/api/v1/checks").header("X-API-Key", client.key()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk());
    }

    @Test
    void editComparisonFindsNewResolvedAndWatchlistFindingsAndPreservesFindingTargets() throws Exception {
        TestClient client = newClient(provisioning, clients, PlanCode.PRO);
        String mark = "Zorvex" + UUID.randomUUID().toString().replace("-", "").substring(0, 5);
        watchlists.replace(client.id(), List.of(new EntryInput(mark, List.of(), List.of(), null, null, true)), "api-key:test", null);

        JsonNode unchanged = edit(client, listing("Plain cotton shirt"), listing("Plain cotton shirt"));
        assertThat(unchanged.path("newFindings")).isEmpty();
        assertThat(unchanged.path("modifiedFindings")).isEmpty();
        assertThat(unchanged.path("remainingFindings")).isNotEmpty();

        JsonNode added = edit(client, listing("Plain cotton shirt"), listing(mark + " cotton shirt"));
        JsonNode watchlistFinding = find(added.path("newFindings"), "watchlist_match");
        assertThat(watchlistFinding.path("feedbackTarget").path("pack").asText()).isEqualTo("trademark-risk");
        assertThat(watchlistFinding.path("feedbackTarget").path("checkId").asText())
                .isEqualTo(added.path("after").path("trademarkRisk").path("checkId").asText());

        String tooLong = "shirt " + "long ".repeat(35);
        JsonNode errorAdded = edit(client, listing("Plain shirt"), listing(tooLong));
        assertThat(find(errorAdded.path("newFindings"), "title_length")).isNotNull();
        JsonNode resolved = edit(client, listing(tooLong), listing("Plain shirt"));
        JsonNode resolvedFinding = find(resolved.path("modifiedFindings"), "title_length");
        assertThat(resolvedFinding.path("after").isNull()).isTrue();
        assertThat(resolvedFinding.path("feedbackTarget").path("checkId").asText())
                .isEqualTo(resolved.path("before").path("listingRules").path("checkId").asText());
    }

    @Test
    void historyIsClientScopedAndPaginatesStoredResults() throws Exception {
        TestClient client = newClient(provisioning, clients, PlanCode.PRO);
        TestClient other = newClient(provisioning, clients, PlanCode.PRO);
        for (int i = 0; i < 27; i++) check(client, "History item " + i + " " + UUID.randomUUID());
        check(other, "Other client's listing " + UUID.randomUUID());

        var first = history.page(client.id(), new CheckHistoryService.Query(null, null, "", "", "", 0));
        var second = history.page(client.id(), new CheckHistoryService.Query(null, null, "", "", "", 1));
        var isolated = history.page(other.id(), new CheckHistoryService.Query(null, null, "", "", "", 0));
        assertThat(first.total()).isEqualTo(27);
        assertThat(first.rows()).hasSize(CheckHistoryService.PAGE_SIZE);
        assertThat(second.rows()).hasSize(2);
        assertThat(isolated.total()).isEqualTo(1);
        assertThat(first.rows()).allSatisfy(row -> assertThat(row.marketplace()).isEqualTo("ETSY"));
    }

    @Test
    void editModeFeedbackWidgetTargetsTheStoredFindingOnTheAfterSide() throws Exception {
        TestClient client = newClient(provisioning, clients, PlanCode.PRO);
        String email = "edit-" + UUID.randomUUID() + "@example.test";
        accounts.saveAndFlush(new ConsoleAccount(email, client.id(), ConsoleRole.MEMBER));
        String tooLong = "shirt " + "length ".repeat(30);
        ObjectNode before = listing("Plain shirt");
        ObjectNode after = listing(tooLong);

        String html = mvc.perform(post("/console/check/edit").with(signedIn(new ConsoleUser(email, client.id()))).with(csrf())
                        .param("marketplace", "ETSY").param("before", mapper.writeValueAsString(before))
                        .param("after", mapper.writeValueAsString(after)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode compared = edit(client, before, after);
        JsonNode newFinding = find(compared.path("newFindings"), "title_length");
        String afterCheckId = compared.path("after").path("listingRules").path("checkId").asText();
        assertThat(newFinding.path("feedbackTarget").path("checkId").asText()).isEqualTo(afterCheckId);
        assertThat(html).contains("data-draft-fix", "data-field=\"title\"", "Check completed")
                .contains("name=\"pack\" value=\"listing-rules\"")
                .contains("name=\"checkId\" value=\"" + afterCheckId + "\"");
    }

    private static JsonNode find(JsonNode findings, String ruleId) {
        for (JsonNode finding : findings) {
            if (ruleId.equals(finding.path("before").path("ruleId").asText())
                    || ruleId.equals(finding.path("after").path("ruleId").asText())) return finding;
        }
        throw new AssertionError("No compared finding for " + ruleId + " in " + findings);
    }
}
