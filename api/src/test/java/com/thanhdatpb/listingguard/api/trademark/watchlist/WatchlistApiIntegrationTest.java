package com.thanhdatpb.listingguard.api.trademark.watchlist;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The client trademark watchlist over HTTP with a served catalog (ADR-031): GET/PUT, revisions and validation, matching in a
 * trademark check (source, evidence, alias, case and punctuation), isolation between clients and the cache. Every test uses
 * clients of its own, so no other test's analysis version changes. Every name is invented.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class WatchlistApiIntegrationTest {

    private static final String WATCHLIST = "/api/v1/trademark-watchlist";
    private static final String TRADEMARK = "/api/v1/trademark-checks";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ClientProvisioning provisioning;

    private String keyA;
    private String keyB;
    private String mark;

    @BeforeEach
    void clients() {
        keyA = newClient();
        keyB = newClient();
        mark = "Brimwick " + capitalized(unique());
    }

    private String newClient() {
        String name = "wl-" + unique().substring(0, 20);
        provisioning.createClient(name);
        return provisioning.createKey(name, "watchlist test").secret();
    }

    /** Letters only, so the text can never look like a catalog mark or a leet spelling of one. */
    static String unique() {
        String letters = "ghijklmnopqrstuv";
        StringBuilder out = new StringBuilder();
        for (char c : java.util.UUID.randomUUID().toString().replace("-", "").toCharArray()) {
            out.append(letters.charAt(Character.digit(c, 16)));
        }
        return out.toString();
    }

    private static String capitalized(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private ObjectNode entry(String markText, String note, String... aliases) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("mark", markText);
        ArrayNode list = entry.putArray("aliases");
        for (String alias : aliases) {
            list.add(alias);
        }
        entry.putArray("niceClasses").add(25);
        entry.put("serialNumber", "12345678");
        if (note != null) {
            entry.put("note", note);
        }
        return entry;
    }

    private ObjectNode list(ObjectNode... entries) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode array = body.putArray("entries");
        for (ObjectNode entry : entries) {
            array.add(entry);
        }
        return body;
    }

    private ResultActions putList(String key, JsonNode body, String ifMatch) throws Exception {
        MockHttpServletRequestBuilder request = put(WATCHLIST).header(HEADER, key).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return mvc.perform(request);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode check(String key, String title) throws Exception {
        ObjectNode listing = mapper.createObjectNode();
        listing.put("title", title);
        listing.put("description", "Plain description");
        ObjectNode body = mapper.createObjectNode();
        body.put("marketplace", "ETSY");
        body.set("listing", listing);
        return json(mvc.perform(post(TRADEMARK).header(HEADER, key).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().isOk()));
    }

    private static List<JsonNode> watchlistFindings(JsonNode result) {
        return StreamSupport.stream(result.get("findings").spliterator(), false)
                .filter(f -> f.get("source").asText().equals("WATCHLIST")).toList();
    }

    @Test
    void aClientWithoutAListReadsAnEmptyOne() throws Exception {
        JsonNode view = json(mvc.perform(get(WATCHLIST).header(HEADER, keyA)).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\"")));

        assertThat(view.get("revision").asLong()).isZero();
        assertThat(view.get("version").isNull()).isTrue();
        assertThat(view.get("entries")).isEmpty();
        assertThat(view.get("limits").get("maxEntries").asInt()).isPositive();
    }

    @Test
    void putReplacesTheListAndAnIdenticalListChangesNothing() throws Exception {
        JsonNode first = json(putList(keyA, list(entry(mark, "từng bị claim", "Otterz")), null).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\"")));
        JsonNode again = json(putList(keyA, list(entry(mark, "từng bị claim", "Otterz")), "\"1\"").andExpect(status().isOk()));

        assertThat(first.get("revision").asLong()).isEqualTo(1);
        assertThat(first.get("version").asText()).hasSize(64);
        JsonNode stored = first.get("entries").get(0);
        assertThat(stored.get("mark").asText()).isEqualTo(mark);
        assertThat(stored.get("enabled").asBoolean()).isTrue();
        assertThat(stored.get("addedBy").asText()).startsWith("api-key:");
        assertThat(again.get("revision").asLong()).isEqualTo(1);
        assertThat(again.get("entries").get(0).get("id")).isEqualTo(stored.get("id"));
    }

    @Test
    void aStaleRevisionIsRefusedAndNothingChanges() throws Exception {
        putList(keyA, list(entry(mark, null)), null).andExpect(status().isOk());

        JsonNode problem = json(putList(keyA, list(), "\"0\"").andExpect(status().isPreconditionFailed()));

        assertThat(problem.get("code").asText()).isEqualTo("watchlist-revision-mismatch");
        assertThat(problem.get("currentRevision").asLong()).isEqualTo(1);
        assertThat(json(mvc.perform(get(WATCHLIST).header(HEADER, keyA))).get("entries")).hasSize(1);
    }

    @Test
    void everyProblemOfAListIsAnsweredAtOnce() throws Exception {
        ObjectNode duplicate = entry(mark.toUpperCase() + "!", null);
        ObjectNode badSerial = entry("Paddle " + unique(), null);
        badSerial.put("serialNumber", "12-34");

        JsonNode problem = json(putList(keyA, list(entry(mark, null), duplicate, badSerial), null)
                .andExpect(status().isBadRequest()));

        assertThat(problem.get("code").asText()).isEqualTo("invalid-request");
        assertThat(problem.get("errors").findValuesAsText("field")).containsExactly("entries[1].mark", "entries[2].serialNumber");
        assertThat(json(mvc.perform(get(WATCHLIST).header(HEADER, keyA))).get("revision").asLong()).isZero();
    }

    @Test
    void aCheckReportsTheWatchlistWithItsSourceAndEntry() throws Exception {
        putList(keyA, list(entry(mark, "từng bị claim", "Otterz")), null).andExpect(status().isOk());

        JsonNode result = check(keyA, "Retro " + mark.toUpperCase().replace(' ', '-') + "! tee");
        JsonNode viaAlias = check(keyA, "Retro otterz tee");

        assertThat(result.get("catalogStatus").asText()).isEqualTo("SERVED");
        assertThat(result.get("watchlist").get("revision").asLong()).isEqualTo(1);
        assertThat(result.get("watchlist").get("entries").asInt()).isEqualTo(1);
        List<JsonNode> findings = watchlistFindings(result);
        assertThat(findings).hasSize(1);
        JsonNode finding = findings.get(0);
        assertThat(finding.get("ruleId").asText()).isEqualTo("watchlist_match");
        assertThat(finding.get("risk").asText()).isEqualTo("HIGH");
        assertThat(finding.get("mark").asText()).isEqualTo(mark);
        assertThat(finding.get("sourceIds").get(0).asText()).isEqualTo("uspto:12345678");
        assertThat(finding.get("watchlistEntry").get("note").asText()).isEqualTo("từng bị claim");
        assertThat(finding.get("watchlistEntry").get("matchedAlias").isNull()).isTrue();
        assertThat(finding.get("message").asText()).contains(mark).contains("từng bị claim");
        assertThat(result.get("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(watchlistFindings(viaAlias)).singleElement()
                .satisfies(f -> assertThat(f.get("watchlistEntry").get("matchedAlias").asText()).isEqualTo("Otterz"));
    }

    @Test
    void anotherClientsWatchlistChangesNothing() throws Exception {
        String title = "Retro " + mark + " tee";
        JsonNode before = check(keyB, title);
        putList(keyA, list(entry(mark, null)), null).andExpect(status().isOk());

        JsonNode after = check(keyB, title);

        assertThat(watchlistFindings(after)).isEmpty();
        assertThat(after.get("watchlist").isNull()).isTrue();
        assertThat(after.get("analysisVersion")).isEqualTo(before.get("analysisVersion"));
        assertThat(after.get("cached").asBoolean()).isTrue();
        assertThat(json(mvc.perform(get(WATCHLIST).header(HEADER, keyB))).get("entries")).isEmpty();
    }

    @Test
    void changingTheWatchlistInvalidatesTheCache() throws Exception {
        String title = "Retro " + mark + " tee";
        putList(keyA, list(entry(mark, "first")), null).andExpect(status().isOk());
        JsonNode first = check(keyA, title);
        JsonNode cached = check(keyA, title);

        putList(keyA, list(entry(mark, "second")), null).andExpect(status().isOk());
        JsonNode changed = check(keyA, title);
        putList(keyA, list(), null).andExpect(status().isOk());
        JsonNode none = check(keyA, title);

        assertThat(first.get("cached").asBoolean()).isFalse();
        assertThat(cached.get("cached").asBoolean()).isTrue();
        assertThat(changed.get("cached").asBoolean()).isFalse();
        assertThat(changed.get("analysisVersion")).isNotEqualTo(first.get("analysisVersion"));
        assertThat(watchlistFindings(changed).get(0).get("watchlistEntry").get("note").asText()).isEqualTo("second");
        assertThat(none.get("watchlist").isNull()).isTrue();
        assertThat(watchlistFindings(none)).isEmpty();
        assertThat(none.get("analysisVersion")).isNotEqualTo(changed.get("analysisVersion"));
    }

    @Test
    void aDisabledEntryIsKeptButNotScanned() throws Exception {
        ObjectNode off = entry(mark, null);
        off.put("enabled", false);
        JsonNode view = json(putList(keyA, list(off), null).andExpect(status().isOk()));

        JsonNode result = check(keyA, "Retro " + mark + " tee");

        assertThat(view.get("entries")).hasSize(1);
        assertThat(view.get("version").isNull()).isTrue();
        assertThat(watchlistFindings(result)).isEmpty();
        assertThat(result.get("watchlist").isNull()).isTrue();
    }

    @Test
    void aStoredResultIsReadBackWithTheWatchlistEvidence() throws Exception {
        putList(keyA, list(entry(mark, "từng bị claim")), null).andExpect(status().isOk());
        JsonNode result = check(keyA, "Retro " + mark + " tee");

        JsonNode read = json(mvc.perform(get(TRADEMARK + "/" + result.get("checkId").asText()).header(HEADER, keyA))
                .andExpect(status().isOk()));

        assertThat(read.get("watchlist")).isEqualTo(result.get("watchlist"));
        assertThat(watchlistFindings(read)).singleElement()
                .satisfies(f -> assertThat(f.get("watchlistEntry")).isEqualTo(watchlistFindings(result).get(0).get("watchlistEntry")));
    }
}
