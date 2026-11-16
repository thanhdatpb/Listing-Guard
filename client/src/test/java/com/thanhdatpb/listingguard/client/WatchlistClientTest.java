package com.thanhdatpb.listingguard.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.client.ApiStub.Reply;
import com.thanhdatpb.listingguard.client.model.TrademarkCatalogStatus;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckRequest;
import com.thanhdatpb.listingguard.client.model.TrademarkCheckResult;
import com.thanhdatpb.listingguard.client.model.TrademarkFindingSource;
import com.thanhdatpb.listingguard.client.model.TrademarkWatchlist;
import com.thanhdatpb.listingguard.client.model.TrademarkWatchlistEntryInput;
import com.thanhdatpb.listingguard.client.model.TrademarkWatchlistRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The watchlist calls of the SDK (ADR-031) against a stub: the one {@code PUT}, the revision headers, the problems. */
class WatchlistClientTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String WATCHLIST = """
            {"revision": 3, "version": "8a1f", "updatedAt": "2026-11-16T08:02:11.512000Z",
             "entries": [{"id": "6f2a9c4e-1b3d-4e58-a7c9-0d2e4f6a8b1c", "mark": "Brimwick Otters", "aliases": ["Otterz"],
               "niceClasses": [25], "serialNumber": "12345678", "note": "từng bị claim", "enabled": true,
               "addedBy": "api-key:k1a2b3c4", "addedAt": "2026-11-10T03:40:00Z", "updatedBy": "api-key:k1a2b3c4",
               "updatedAt": "2026-11-16T08:02:11.512000Z"}],
             "limits": {"maxEntries": 500, "maxAliases": 20, "maxTextChars": 100, "maxNoteChars": 500, "maxTokens": 12},
             "someFutureMember": 1}
            """;

    @Test
    void getReadsTheListAndSendsNothingElse() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, WATCHLIST).with("ETag", "\"3\""))) {
            TrademarkWatchlist list = stub.client().trademarkWatchlist();

            ApiStub.Recorded r = stub.only();
            assertThat(r.method()).isEqualTo("GET");
            assertThat(r.target()).isEqualTo("/api/v1/trademark-watchlist");
            assertThat(r.body()).isEmpty();
            assertThat(r.header("If-Match")).isNull();
            assertThat(list.revision()).isEqualTo(3);
            assertThat(list.entries()).singleElement().satisfies(e -> {
                assertThat(e.id()).isEqualTo(UUID.fromString("6f2a9c4e-1b3d-4e58-a7c9-0d2e4f6a8b1c"));
                assertThat(e.mark()).isEqualTo("Brimwick Otters");
                assertThat(e.aliases()).containsExactly("Otterz");
                assertThat(e.enabled()).isTrue();
            });
            assertThat(list.limits().maxEntries()).isEqualTo(500);
        }
    }

    @Test
    void aConditionalGetSendsIfNoneMatchAndReadsA304() throws Exception {
        try (ApiStub stub = new ApiStub().replying(new Reply(304, "", Map.of("ETag", "\"3\""), Duration.ZERO))) {
            Conditional<TrademarkWatchlist> answer = stub.client().trademarkWatchlist("\"3\"");

            assertThat(stub.only().header("If-None-Match")).isEqualTo("\"3\"");
            assertThat(answer.notModified()).isTrue();
            assertThat(answer.value()).isNull();
            assertThat(answer.etag()).isEqualTo("\"3\"");
        }
    }

    @Test
    void putSendsTheWholeListWithTheExpectedRevision() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, WATCHLIST))) {
            TrademarkWatchlistRequest request = new TrademarkWatchlistRequest(List.of(
                    TrademarkWatchlistEntryInput.of("Brimwick Otters").withAliases(List.of("Otterz")).withNote("từng bị claim")));

            TrademarkWatchlist list = stub.client().replaceTrademarkWatchlist(request, 2L);

            ApiStub.Recorded r = stub.only();
            assertThat(r.method()).isEqualTo("PUT");
            assertThat(r.target()).isEqualTo("/api/v1/trademark-watchlist");
            assertThat(r.header("If-Match")).isEqualTo("\"2\"");
            assertThat(r.header("Content-Type")).isEqualTo("application/json");
            assertThat(r.header("X-API-Key")).isEqualTo(ApiStub.KEY);
            JsonNode body = M.readTree(r.body());
            assertThat(body.at("/entries/0/mark").asText()).isEqualTo("Brimwick Otters");
            assertThat(body.at("/entries/0/aliases/0").asText()).isEqualTo("Otterz");
            assertThat(body.at("/entries/0/note").asText()).isEqualTo("từng bị claim");
            assertThat(body.at("/entries/0").has("enabled")).as("an absent member is not sent").isFalse();
            assertThat(list.revision()).isEqualTo(3);
        }
    }

    @Test
    void putWithoutAnExpectedRevisionSendsNoIfMatch() throws Exception {
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, WATCHLIST))) {
            stub.client().replaceTrademarkWatchlist(new TrademarkWatchlistRequest(List.of()), null);

            assertThat(stub.only().header("If-Match")).isNull();
            assertThat(M.readTree(stub.only().body()).get("entries")).isEmpty();
        }
    }

    @Test
    void aStaleRevisionIsAWatchlistRevisionMismatchExceptionWithTheCurrentOne() throws Exception {
        String body = ApiStub.problem(412, "watchlist-revision-mismatch", "\"currentRevision\":4");
        try (ApiStub stub = new ApiStub().replying(Reply.problem(412, body))) {
            assertThatThrownBy(() -> stub.client().replaceTrademarkWatchlist(new TrademarkWatchlistRequest(List.of()), 3L))
                    .isInstanceOfSatisfying(WatchlistRevisionMismatchException.class, e -> {
                        assertThat(e.status()).isEqualTo(412);
                        assertThat(e.currentRevision()).isEqualTo(4L);
                        assertThat(e.code()).isEqualTo("watchlist-revision-mismatch");
                    });
            assertThat(stub.requests()).as("a 412 is never retried").hasSize(1);
        }
    }

    @Test
    void everyProblemOfAListIsInTheValidationException() throws Exception {
        String body = ApiStub.problem(400, "invalid-request",
                "\"errors\":[{\"field\":\"entries[0].mark\",\"message\":\"The mark is required.\"},"
                        + "{\"field\":\"entries[1].serialNumber\",\"message\":\"A serial number is eight digits.\"}]");
        try (ApiStub stub = new ApiStub().replying(Reply.problem(400, body))) {
            assertThatThrownBy(() -> stub.client().replaceTrademarkWatchlist(
                    new TrademarkWatchlistRequest(List.of(TrademarkWatchlistEntryInput.of(""))), null))
                    .isInstanceOfSatisfying(ValidationException.class,
                            e -> assertThat(e.errors()).extracting("field").containsExactly("entries[0].mark",
                                    "entries[1].serialNumber"));
        }
    }

    @Test
    void thePutIsRetriedLikeAPostOnlyForARateLimit() throws Exception {
        String limited = ApiStub.problem(429, "rate-limited", "");
        try (ApiStub stub = new ApiStub().then(Reply.problem(429, limited).with("Retry-After", "0"))
                .replying(Reply.json(200, WATCHLIST))) {
            TrademarkWatchlist list = stub.builder().retry(RetryPolicy.defaults()).delayer(delay -> Runnable::run).build()
                    .replaceTrademarkWatchlist(new TrademarkWatchlistRequest(List.of()), null);

            assertThat(list.revision()).isEqualTo(3);
            assertThat(stub.requests()).hasSize(2).allSatisfy(r -> assertThat(r.method()).isEqualTo("PUT"));
        }
    }

    @Test
    void anExpectedRevisionOnAnotherCallIsRefused() {
        assertThatThrownBy(() -> RawRequest.trademarkWatchlist().withExpectedRevision(1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RawRequest.replaceTrademarkWatchlist("{}").withExpectedRevision(-1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RawRequest.replaceTrademarkWatchlist("{}").withIfNoneMatch("\"1\""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTrademarkCheckShowsTheWatchlistSourceAndTheMissingCatalog() throws Exception {
        String result = """
                {"checkId": "5d1e7c2a-9b40-4f63-8e21-3c7a9d0b4f12", "marketplace": "ETSY", "productType": null,
                 "rulesetVersion": "trademark-risk-2026-11b", "catalogVersion": "none", "catalogStatus": "NOT_CONFIGURED",
                 "analysisVersion": "abc", "cached": false, "completeness": "INCOMPLETE", "riskLevel": "HIGH",
                 "failedRuleIds": [], "skippedRuleIds": ["catalog_exact"], "disclaimer": "d",
                 "createdAt": "2026-11-16T09:15:30Z", "expiresAt": "2026-12-16T09:15:30Z",
                 "watchlist": {"revision": 3, "version": "8a1f", "entries": 2},
                 "findings": [{"ruleId": "watchlist_match", "source": "WATCHLIST", "risk": "HIGH", "field": "title", "start": 0,
                   "end": 15, "snippet": "Brimwick Otters", "mark": "Brimwick Otters", "matchType": "PHRASE", "score": 1.0,
                   "insideColorPhrase": false, "otherRuleIds": [], "sourceIds": [], "sourceLinks": [], "riskAdjustments": [],
                   "watchlistEntry": {"entryId": "6f2a9c4e-1b3d-4e58-a7c9-0d2e4f6a8b1c", "mark": "Brimwick Otters",
                     "matchedAlias": null, "niceClasses": [25], "serialNumber": null, "note": "từng bị claim"},
                   "message": "m", "suggestion": "s"},
                  {"ruleId": "x", "source": "A_NEW_SOURCE", "risk": "LOW", "field": "title", "start": 0, "end": 1,
                   "snippet": "x", "matchType": "CONTEXT", "insideColorPhrase": false, "message": "m", "suggestion": "s"}]}
                """;
        try (ApiStub stub = new ApiStub().replying(Reply.json(200, result))) {
            TrademarkCheckResult read = stub.client().trademarkChecks(TrademarkCheckRequest.ofJson("ETSY", "{\"title\":\"x\"}"));

            assertThat(read.catalogStatus()).isEqualTo(TrademarkCatalogStatus.NOT_CONFIGURED);
            assertThat(read.skippedRuleIds()).containsExactly("catalog_exact");
            assertThat(read.watchlist().entries()).isEqualTo(2);
            assertThat(read.findings().get(0).source()).isEqualTo(TrademarkFindingSource.WATCHLIST);
            assertThat(read.findings().get(0).watchlistEntry().note()).isEqualTo("từng bị claim");
            assertThat(read.findings().get(1).source()).as("never folded into a known value")
                    .isEqualTo(TrademarkFindingSource.UNRECOGNIZED);
            assertThat(read.findings().get(1).watchlistEntry()).isNull();
        }
    }

    @Test
    void aRawRequestNeverShowsTheListInItsToString() {
        assertThat(RawRequest.replaceTrademarkWatchlist("{\"entries\":[{\"mark\":\"Brimwick Otters\"}]}").toString())
                .doesNotContain("Brimwick");
    }
}
