package com.thanhdatpb.listingguard.api.rewrite;

import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.rewrite.StubLlm.answer;
import static com.thanhdatpb.listingguard.api.rewrite.StubLlm.candidate;
import static com.thanhdatpb.listingguard.api.rewrite.StubLlm.tag;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.editor.EditorForm;
import com.thanhdatpb.listingguard.api.console.editor.EditorListingMapper;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.rewrite.RewriteOutcome.Status;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import com.thanhdatpb.listingguard.rag.llm.LlmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The rewrite suggestions with a stub model (ADR-037): what the checks let through, what they drop, what is stored and what is not.
 * The listings are synthetic; a "mark" is a watchlist entry of the test's own client, which the trademark check finds like a catalog
 * mark.
 */
@SpringBootTest(properties = {"listing-guard.console.enabled=true", "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test", "spring.mail.host=mail.example.test",
        "listing-guard.llm.rewrite.enabled=true"})
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, RewriteTestConfig.class})
class ListingRewriterIntegrationTest {

    /** Over the 140 characters of an Etsy title: the rules report an ERROR. */
    private static final String TOO_LONG = "shirt " + "long ".repeat(35).strip();
    private static final String TOO_LONG_TAG = "an extremely long tag name over the limit";

    @Autowired ListingRewriter rewriter;
    @Autowired RewriteRunStore runs;
    @Autowired StubLlm llm;
    @Autowired ClientProvisioning provisioning;
    @Autowired ClientRepository clients;
    @Autowired WatchlistService watchlists;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender mailSender;

    @BeforeEach
    void reset() {
        llm.reset();
    }

    private static ObjectNode listing(String title, String tags) {
        EditorForm form = new EditorForm("ETSY", title, tags, "A synthetic mug.", "", "19.99", "5", "Home", "i_did",
                "made_to_order", "", "", "");
        return EditorListingMapper.map(EditorMarketplace.ETSY, form).listing();
    }

    private TestClient client() {
        return newClient(provisioning, clients, PlanCode.PRO);
    }

    private String watch(TestClient client) {
        String mark = "Zorvex" + UUID.randomUUID().toString().replace("-", "").substring(0, 5);
        watchlists.replace(client.id(), List.of(new EntryInput(mark, List.of(), List.of(), null, null, true)), "api-key:test", null);
        return mark;
    }

    private int stored(String table, TestClient client) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE client_id = ?", Integer.class, client.id());
    }

    @Test
    void aCandidateThatKeepsAnErrorOrAddsAMarkIsDroppedAndTheSafeOneIsShown() {
        TestClient client = client();
        String mark = watch(client);
        llm.reply(answer(
                candidate(TOO_LONG.substring(0, TOO_LONG.length() - 5)),
                candidate(mark + " cotton shirt"),
                candidate("Plain cotton shirt")));

        RewriteOutcome outcome = rewriter.suggest(client.id(), null, "ETSY", listing(TOO_LONG, "mug\nceramic"), null);

        assertThat(outcome.status()).isEqualTo(Status.SUGGESTIONS);
        assertThat(outcome.candidates()).singleElement().satisfies(safe -> {
            assertThat(safe.number()).isEqualTo(1);
            assertThat(safe.title()).isEqualTo("Plain cotton shirt");
            assertThat(safe.tags()).isEmpty();
        });
        assertThat(outcome.originalTitle()).isEqualTo(TOO_LONG);
        assertThat(outcome.runId()).isNotNull();
    }

    @Test
    void whenNoCandidatePassesTheOutcomeIsNoSafeSuggestionAndNothingUncheckedIsReturned() {
        TestClient client = client();
        String mark = watch(client);
        llm.reply(answer(
                candidate(TOO_LONG.substring(0, TOO_LONG.length() - 5)),
                candidate(mark + " cotton shirt"),
                candidate(TOO_LONG)));

        RewriteOutcome outcome = rewriter.suggest(client.id(), null, "ETSY", listing(TOO_LONG, "mug\nceramic"), null);

        assertThat(outcome.status()).isEqualTo(Status.NONE_SAFE);
        assertThat(outcome.candidates()).isEmpty();
        assertThat(stored("rewrite_suggestion_run", client)).isEqualTo(1);
    }

    @Test
    void aFaultyTagIsReplacedOnlyByATagThatClearsTheErrorAndNamesTheCurrentValue() {
        TestClient client = client();
        llm.reply(answer(
                candidate(null, tag(1, TOO_LONG_TAG, "long tag")),
                candidate(null, tag(0, "mug", "cup")),
                candidate(null, tag(1, "a tag that is not there", "cup"))));

        RewriteOutcome outcome = rewriter.suggest(client.id(), null, "ETSY",
                listing("Plain cotton shirt", "mug\n" + TOO_LONG_TAG), null);

        assertThat(outcome.status()).isEqualTo(Status.SUGGESTIONS);
        assertThat(outcome.candidates()).singleElement().satisfies(safe -> {
            assertThat(safe.title()).isNull();
            assertThat(safe.tags()).singleElement().satisfies(change -> {
                assertThat(change.index()).isEqualTo(1);
                assertThat(change.from()).isEqualTo(TOO_LONG_TAG);
                assertThat(change.to()).isEqualTo("long tag");
            });
        });
    }

    @Test
    void aListingWithOnlyATrademarkFindingNeedsACandidateThatRemovesIt() {
        TestClient client = client();
        String mark = watch(client);
        llm.reply(answer(
                candidate(mark + " cotton shirts"),
                candidate("Plain cotton shirt")));

        RewriteOutcome outcome = rewriter.suggest(client.id(), null, "ETSY", listing(mark + " cotton shirt", "mug\nceramic"), null);

        assertThat(outcome.status()).isEqualTo(Status.SUGGESTIONS);
        assertThat(outcome.candidates()).singleElement().satisfies(safe -> assertThat(safe.title()).isEqualTo("Plain cotton shirt"));
    }

    @Test
    void candidatesThatAreLongerStuffedEmptyOrUnchangedNeverReachTheChecks() {
        TestClient client = client();
        llm.reply(answer(
                candidate(TOO_LONG + " extra"),
                candidate(""),
                candidate(TOO_LONG)));

        RewriteOutcome outcome = rewriter.suggest(client.id(), null, "ETSY", listing(TOO_LONG, "mug\nceramic"), null);

        assertThat(outcome.status()).isEqualTo(Status.NONE_SAFE);
    }

    @Test
    void aModelFailureOrAnUnreadableAnswerShowsNothingAndStoresNoRun() {
        TestClient client = client();
        llm.fail(new LlmException(LlmException.Kind.TIMEOUT, "timeout"));
        assertThat(rewriter.suggest(client.id(), null, "ETSY", listing(TOO_LONG, "mug\nceramic"), null).status())
                .isEqualTo(Status.UNAVAILABLE);

        llm.reply("this is not json");
        RewriteOutcome unreadable = rewriter.suggest(client.id(), null, "ETSY", listing(TOO_LONG, "mug\nceramic"), null);
        assertThat(unreadable.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(unreadable.candidates()).isEmpty();
        assertThat(stored("rewrite_suggestion_run", client)).isZero();
    }

    @Test
    void aListingWithNothingToFixDoesNotCallTheModel() {
        TestClient client = client();

        RewriteOutcome outcome = rewriter.suggest(client.id(), null, "ETSY", listing("Plain cotton shirt", "mug\nceramic"), null);

        assertThat(outcome.status()).isEqualTo(Status.NOT_NEEDED);
        assertThat(llm.requests()).isEmpty();
    }

    @Test
    void thePromptCarriesTheTitleTheTagsAndTheProblemsButNotTheDescription() {
        TestClient client = client();
        ObjectNode listing = listing(TOO_LONG, "mug\nceramic");
        listing.put("description", "SECRET-DESCRIPTION-9");
        llm.reply(answer(candidate("Plain cotton shirt")));

        rewriter.suggest(client.id(), null, "ETSY", listing, null);

        assertThat(llm.requests()).singleElement().satisfies(request -> {
            assertThat(request.user()).contains("title_length").contains("ceramic").contains("shirt long long");
            assertThat(request.user()).doesNotContain("SECRET-DESCRIPTION-9").doesNotContain("19.99");
            assertThat(request.system()).contains("untrusted data");
            assertThat(request.maxOutputTokens()).isPositive();
        });
    }

    @Test
    void verifyingCandidatesStoresNoCheckAndNoListingText() {
        TestClient client = client();
        llm.reply(answer(candidate("Plain cotton shirt"), candidate("Another plain shirt")));

        rewriter.suggest(client.id(), null, "ETSY", listing(TOO_LONG, "mug\nceramic"), null);

        assertThat(stored("listing_check", client)).isZero();
        assertThat(stored("trademark_check", client)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM rewrite_suggestion_run WHERE client_id = ?", client.id()).toString())
                .doesNotContain("Plain cotton shirt").doesNotContain("shirt long");
    }

    @Test
    void withTheDefaultOfOneRoundANoneSafeAnswerEndsAfterOneCallWithThePromptAndTheRowOfAdr037() {
        TestClient client = client();
        llm.reply(answer(candidate(TOO_LONG), candidate(TOO_LONG + " extra")));

        RewriteOutcome outcome = rewriter.suggest(client.id(), null, "ETSY", listing(TOO_LONG, "mug\nceramic"), null);

        assertThat(outcome.status()).isEqualTo(Status.NONE_SAFE);
        assertThat(outcome.showRounds()).as("the panel says nothing about rounds when only one is allowed").isFalse();
        assertThat(llm.requests()).as("one call and no second round").hasSize(1);
        assertThat(llm.requests().get(0).system()).doesNotContain("Prompt version").doesNotContain("previous_rounds");
        assertThat(llm.requests().get(0).user()).startsWith("marketplace: ETSY\nlisting (JSON, data):\n")
                .contains("\nproblems (JSON, data):\n").doesNotContain("previous_rounds").doesNotContain("round:");
        assertThat(jdbc.queryForObject("SELECT rounds_used FROM rewrite_suggestion_run WHERE id = ?", Integer.class, outcome.runId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT safe_round FROM rewrite_suggestion_run WHERE id = ?", Integer.class, outcome.runId()))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT candidates_safe FROM rewrite_suggestion_run WHERE id = ?", Integer.class,
                outcome.runId())).isZero();
    }

    @Test
    void withTheDefaultOfOneRoundASafeRunStoresRoundOneAndAnUnavailableModelStoresNothing() {
        TestClient client = client();
        llm.reply(answer(candidate("Plain cotton shirt")));

        RewriteOutcome safe = rewriter.suggest(client.id(), null, "ETSY", listing(TOO_LONG, "mug\nceramic"), null);

        assertThat(safe.status()).isEqualTo(Status.SUGGESTIONS);
        assertThat(llm.requests()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT rounds_used FROM rewrite_suggestion_run WHERE id = ?", Integer.class, safe.runId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT safe_round FROM rewrite_suggestion_run WHERE id = ?", Integer.class, safe.runId()))
                .isEqualTo(1);

        llm.reset();
        llm.fail(new LlmException(LlmException.Kind.TIMEOUT, "timeout"));
        RewriteOutcome unavailable = rewriter.suggest(client.id(), null, "ETSY", listing(TOO_LONG, "mug\nceramic"), null);
        assertThat(unavailable.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(llm.requests()).as("a failure is not retried in another round").hasSize(1);
        assertThat(stored("rewrite_suggestion_run", client)).isEqualTo(1);
    }

    @Test
    void aCopyIsCountedOncePerRunAndOnlyForTheOwnersClient() {
        TestClient client = client();
        TestClient other = client();
        llm.reply(answer(candidate("Plain cotton shirt")));
        UUID unknownResult = UUID.randomUUID();
        RewriteOutcome first = rewriter.suggest(client.id(), null, "ETSY", listing(TOO_LONG, "mug\nceramic"),
                new FeedbackTarget("listing-rules", unknownResult, 0));
        llm.reply(answer(candidate(TOO_LONG)));
        rewriter.suggest(client.id(), null, "ETSY", listing(TOO_LONG, "mug\nceramic"), null);

        assertThat(runs.markUsed(client.id(), first.runId(), 1)).isTrue();
        assertThat(runs.markUsed(client.id(), first.runId(), 1)).isTrue();
        assertThat(runs.markUsed(client.id(), first.runId(), 2)).as("the run offered one candidate").isFalse();
        assertThat(runs.markUsed(other.id(), first.runId(), 1)).as("a run of another client").isFalse();
        assertThat(runs.markUsed(client.id(), UUID.randomUUID(), 1)).isFalse();

        RewriteRunStore.Usage usage = runs.usage(client.id(), Instant.now().minus(Duration.ofHours(1)),
                Instant.now().plus(Duration.ofHours(1)));
        assertThat(usage.runs()).isEqualTo(2);
        assertThat(usage.withSafe()).isEqualTo(1);
        assertThat(usage.used()).isEqualTo(1);
        assertThat(usage.rate()).isEqualTo(1.0);
        assertThat(runs.usage(other.id(), Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(1))).runs())
                .isZero();
        assertThat(jdbc.queryForObject("SELECT anchor_pack FROM rewrite_suggestion_run WHERE id = ?", String.class, first.runId()))
                .as("an address that is not the client's finding is stored as no anchor").isNull();
    }
}
