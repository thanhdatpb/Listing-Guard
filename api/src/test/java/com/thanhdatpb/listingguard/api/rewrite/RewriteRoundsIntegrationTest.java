package com.thanhdatpb.listingguard.api.rewrite;

import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.etsyForm;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.section;
import static com.thanhdatpb.listingguard.api.console.ConsoleTestSupport.signedIn;
import static com.thanhdatpb.listingguard.api.plan.PlanTestSupport.newClient;
import static com.thanhdatpb.listingguard.api.rewrite.StubLlm.answer;
import static com.thanhdatpb.listingguard.api.rewrite.StubLlm.candidate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleLocaleResolver;
import com.thanhdatpb.listingguard.api.console.ConsoleRole;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.console.editor.EditorForm;
import com.thanhdatpb.listingguard.api.console.editor.EditorListingMapper;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import com.thanhdatpb.listingguard.api.plan.PlanTestSupport.TestClient;
import com.thanhdatpb.listingguard.api.rewrite.RewriteOutcome.Status;
import com.thanhdatpb.listingguard.api.rewrite.RewriteOutcome.Stop;
import com.thanhdatpb.listingguard.api.trademark.TrademarkReadyTestConfig;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import com.thanhdatpb.listingguard.rag.llm.LlmException;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import com.thanhdatpb.listingguard.rag.llm.ScriptedLlmProvider;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The repair rounds of the rewrite suggestions with max-rounds 3 (ADR-050), with a scripted model and the real checks. Each test marks
 * its listing with a word of its own and scripts the model's answer for a round by that word, so the tests do not meet. The listings are
 * synthetic; "Quillmark" is a mark of the synthetic catalog and a "mark" made here is a watchlist entry of the test's own client.
 */
@SpringBootTest(properties = {"listing-guard.console.enabled=true", "listing-guard.console.base-url=https://console.example.test",
        "listing-guard.console.mail-from=noreply@example.test", "spring.mail.host=mail.example.test",
        "listing-guard.llm.rewrite.enabled=true", "listing-guard.llm.rewrite.max-rounds=3", "listing-guard.llm.rewrite.max-per-hour=3"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class, RewriteScriptedTestConfig.class})
class RewriteRoundsIntegrationTest {

    private static final String LONG = "long ".repeat(35).strip();
    private static final String SAFE = "Plain cotton shirt";

    @Autowired ListingRewriter rewriter;
    @Autowired RewriteRunStore runs;
    @Autowired ScriptedLlmProvider scripted;
    @Autowired ClientProvisioning provisioning;
    @Autowired ClientRepository clients;
    @Autowired ConsoleAccountRepository accounts;
    @Autowired WatchlistService watchlists;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender mailSender;

    /** What a stored run says about its rounds; {@code safeRound} is null when no round had a safe suggestion. */
    private record Row(int candidatesSafe, int roundsUsed, Integer safeRound) {
    }

    private static String marker() {
        return "mk" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** Over the 140 characters of an Etsy title, so the rules report an ERROR; the marker tells the tests apart. */
    private static String tooLong(String marker) {
        return "shirt " + marker + " " + LONG;
    }

    /** The title with its last {@code characters} cut: still over the limit, and a different text for each number. */
    private static String cut(String title, int characters) {
        return title.substring(0, title.length() - characters).strip();
    }

    private static ObjectNode listing(String title) {
        EditorForm form = new EditorForm("ETSY", title, "mug\nceramic", "SECRET-DESCRIPTION-9", "", "19.99", "5", "Home", "i_did",
                "made_to_order", "SECRET-BRAND-7", "", "");
        return EditorListingMapper.map(EditorMarketplace.ETSY, form).listing();
    }

    private TestClient client() {
        return newClient(provisioning, clients, PlanCode.PRO);
    }

    private ConsoleUser seller() {
        TestClient client = client();
        String email = "rounds-" + UUID.randomUUID() + "@example.test";
        accounts.saveAndFlush(new ConsoleAccount(email, client.id(), ConsoleRole.MEMBER));
        return new ConsoleUser(email, client.id());
    }

    private String watch(TestClient client, String mark) {
        watchlists.replace(client.id(), List.of(new EntryInput(mark, List.of(), List.of(), null, null, true)), "api-key:test", null);
        return mark;
    }

    /** The request of round {@code round} for the listing marked {@code marker}. */
    private static Predicate<LlmRequest> round(String marker, int round) {
        return request -> request.user().contains(marker) && request.user().endsWith("round: " + round + " of 3");
    }

    private List<LlmRequest> calls(String marker) {
        return scripted.requests().stream().filter(request -> request.user().contains(marker)).toList();
    }

    private Row row(UUID run) {
        return jdbc.queryForObject("SELECT candidates_safe, rounds_used, safe_round FROM rewrite_suggestion_run WHERE id = ?",
                (rs, i) -> {
                    int safeRound = rs.getInt("safe_round");
                    boolean none = rs.wasNull();
                    return new Row(rs.getInt("candidates_safe"), rs.getInt("rounds_used"), none ? null : safeRound);
                }, run);
    }

    private int stored(TestClient client) {
        return jdbc.queryForObject("SELECT count(*) FROM rewrite_suggestion_run WHERE client_id = ?", Integer.class, client.id());
    }

    @Test
    void whenRoundOneIsRejectedAndRoundTwoPassesTheSafeCandidateIsShownAndTwoRoundsAreStored() {
        TestClient client = client();
        String m = marker();
        String title = tooLong(m);
        scripted.when(round(m, 1), answer(candidate(cut(title, 5))));
        scripted.when(round(m, 2), answer(candidate(SAFE)));

        RewriteOutcome outcome = rewriter.suggest(client.id(), null, "ETSY", listing(title), null);

        assertThat(outcome.status()).isEqualTo(Status.SUGGESTIONS);
        assertThat(outcome.candidates()).singleElement().satisfies(safe -> {
            assertThat(safe.number()).isEqualTo(1);
            assertThat(safe.title()).isEqualTo(SAFE);
        });
        assertThat(outcome.rounds()).isEqualTo(2);
        assertThat(outcome.maxRounds()).isEqualTo(3);
        assertThat(outcome.stop()).isEqualTo(Stop.FOUND);
        assertThat(row(outcome.runId())).isEqualTo(new Row(1, 2, 2));

        List<LlmRequest> calls = calls(m);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).user()).doesNotContain("previous_rounds").endsWith("round: 1 of 3");
        assertThat(calls.get(1).user()).contains("previous_rounds (JSON, untrusted data)").contains("\"step\":\"listing_rule\"")
                .contains("\"rule\":\"title_length\"").contains(cut(title, 5)).endsWith("round: 2 of 3");
        assertThat(calls.get(1).system()).contains("untrusted data").contains("Prompt version: rewrite-2");
    }

    @Test
    void threeRoundsWithoutASafeCandidateEndInNoSafeSuggestionAndStoreThreeRounds() {
        TestClient client = client();
        String m = marker();
        String title = tooLong(m);
        scripted.when(round(m, 1), answer(candidate(cut(title, 5))));
        scripted.when(round(m, 2), answer(candidate(cut(title, 6))));
        scripted.when(round(m, 3), answer(candidate(cut(title, 7))));

        RewriteOutcome outcome = rewriter.suggest(client.id(), null, "ETSY", listing(title), null);

        assertThat(outcome.status()).isEqualTo(Status.NONE_SAFE);
        assertThat(outcome.candidates()).isEmpty();
        assertThat(outcome.rounds()).isEqualTo(3);
        assertThat(outcome.stop()).isEqualTo(Stop.ROUNDS_EXHAUSTED);
        assertThat(row(outcome.runId())).isEqualTo(new Row(0, 3, null));
        List<LlmRequest> calls = calls(m);
        assertThat(calls).hasSize(3);
        assertThat(calls.get(2).user()).contains("\"round\":1").contains("\"round\":2").endsWith("round: 3 of 3");
    }

    @Test
    void aModelThatFailsOrIsUnreadableInRoundTwoEndsUnavailableWithNothingUncheckedAndNoStoredRun() {
        TestClient client = client();
        String failing = marker();
        scripted.when(round(failing, 1), answer(candidate(cut(tooLong(failing), 5))));
        scripted.failWhen(round(failing, 2), new LlmException(LlmException.Kind.TIMEOUT, "timeout"));
        String unreadable = marker();
        scripted.when(round(unreadable, 1), answer(candidate(cut(tooLong(unreadable), 5))));
        scripted.when(round(unreadable, 2), "this is not json");

        RewriteOutcome failed = rewriter.suggest(client.id(), null, "ETSY", listing(tooLong(failing)), null);
        RewriteOutcome garbled = rewriter.suggest(client.id(), null, "ETSY", listing(tooLong(unreadable)), null);

        for (RewriteOutcome outcome : List.of(failed, garbled)) {
            assertThat(outcome.status()).isEqualTo(Status.UNAVAILABLE);
            assertThat(outcome.candidates()).isEmpty();
            assertThat(outcome.runId()).isNull();
            assertThat(outcome.rounds()).isEqualTo(2);
            assertThat(outcome.stop()).isEqualTo(Stop.UNAVAILABLE);
        }
        assertThat(calls(failing)).as("no third round after a failure").hasSize(2);
        assertThat(calls(unreadable)).hasSize(2);
        assertThat(stored(client)).isZero();
    }

    @Test
    void theFeedbackNamesNoMarkOfTheCatalogOrTheWatchlistAndNoFieldTheListingKeepsPrivate() {
        TestClient client = client();
        String watched = watch(client, "Zorvex" + UUID.randomUUID().toString().replace("-", "").substring(0, 5));
        String m = marker();
        String title = tooLong(m);
        scripted.when(round(m, 1), answer(candidate(watched + " cotton shirt"), candidate("Quillmark cotton shirt"),
                candidate(cut(title, 5))));
        scripted.when(round(m, 2), answer(candidate(SAFE)));

        RewriteOutcome outcome = rewriter.suggest(client.id(), null, "ETSY", listing(title), null);

        assertThat(outcome.status()).isEqualTo(Status.SUGGESTIONS);
        assertThat(outcome.rounds()).isEqualTo(2);
        String second = calls(m).get(1).user();
        String feedback = second.substring(second.indexOf("previous_rounds"));
        assertThat(feedback.toLowerCase()).doesNotContain(watched.toLowerCase()).doesNotContain("quillmark")
                .doesNotContain("cotton shirt");
        assertThat(feedback).contains("\"rule\":\"watchlist_match\"").contains("\"rule\":\"catalog_exact\"")
                .contains("\"field\":\"title\"").contains(cut(title, 5));
        assertThat(Pattern.compile("added a registered mark").matcher(feedback).results().count()).isGreaterThanOrEqualTo(2);
        assertThat(second).doesNotContain("SECRET-DESCRIPTION-9").doesNotContain("SECRET-BRAND-7").doesNotContain("19.99")
                .doesNotContain("made_to_order");
    }

    @Test
    void aCandidateRepeatedFromAnEarlierRoundIsDroppedAndSaidSo() {
        TestClient client = client();
        String m = marker();
        String title = tooLong(m);
        scripted.when(round(m, 1), answer(candidate(cut(title, 5))));
        scripted.when(round(m, 2), answer(candidate(cut(title, 5)), candidate(cut(title, 6))));
        scripted.when(round(m, 3), answer(candidate(SAFE)));

        RewriteOutcome outcome = rewriter.suggest(client.id(), null, "ETSY", listing(title), null);

        assertThat(outcome.status()).isEqualTo(Status.SUGGESTIONS);
        assertThat(outcome.rounds()).isEqualTo(3);
        assertThat(outcome.candidates()).extracting(RewriteCandidate::title).containsExactly(SAFE);
        assertThat(calls(m).get(2).user()).contains("the same proposal as an earlier candidate").contains(cut(title, 6));
        assertThat(row(outcome.runId())).isEqualTo(new Row(1, 3, 3));
    }

    @Test
    void aCacheHitInRoundOneIsStillVerifiedByTheChecks() {
        TestClient client = client();
        String m = marker();
        String title = tooLong(m);
        scripted.when(round(m, 1), answer(candidate(SAFE)));
        RewriteOutcome first = rewriter.suggest(client.id(), null, "ETSY", listing(title), null);
        assertThat(first.status()).isEqualTo(Status.SUGGESTIONS);
        assertThat(calls(m)).hasSize(1);

        // the same prompt now, but the client lists a name the proposal contains: the cached answer is read again and judged again
        watch(client, "plain cotton");
        RewriteOutcome second = rewriter.suggest(client.id(), null, "ETSY", listing(title), null);

        assertThat(second.candidates()).extracting(RewriteCandidate::title).doesNotContain(SAFE);
        assertThat(second.status()).isEqualTo(Status.NONE_SAFE);
        assertThat(calls(m).stream().filter(request -> request.user().endsWith("round: 1 of 3")).count())
                .as("round one of the second press came from the cache").isEqualTo(1);
        assertThat(calls(m)).as("only rounds two and three asked the model again").hasSize(3);
    }

    @Test
    void aPressTakesOneSlotOfTheHourHoweverManyRoundsItNeeds() {
        TestClient client = client();
        String m = marker();
        ObjectNode listing = listing(tooLong(m));

        List<Status> statuses = List.of(
                rewriter.suggest(client.id(), null, "ETSY", listing, null).status(),
                rewriter.suggest(client.id(), null, "ETSY", listing, null).status(),
                rewriter.suggest(client.id(), null, "ETSY", listing, null).status(),
                rewriter.suggest(client.id(), null, "ETSY", listing, null).status());

        assertThat(statuses).containsExactly(Status.NONE_SAFE, Status.NONE_SAFE, Status.NONE_SAFE, Status.RATE_LIMITED);
        assertThat(calls(m)).as("one press asked three times; the repeats came from the cache").hasSize(3);
    }

    @Test
    void theCountsPerRoundAreTheClientsOwn() {
        TestClient client = client();
        TestClient other = client();
        String first = marker();
        scripted.when(round(first, 1), answer(candidate(SAFE)));
        String second = marker();
        scripted.when(round(second, 1), answer(candidate(cut(tooLong(second), 5))));
        scripted.when(round(second, 2), answer(candidate(SAFE)));
        String third = marker();
        String foreign = marker();
        scripted.when(round(foreign, 1), answer(candidate(SAFE)));

        rewriter.suggest(client.id(), null, "ETSY", listing(tooLong(first)), null);
        rewriter.suggest(client.id(), null, "ETSY", listing(tooLong(second)), null);
        rewriter.suggest(client.id(), null, "ETSY", listing(tooLong(third)), null);
        rewriter.suggest(other.id(), null, "ETSY", listing(tooLong(foreign)), null);

        Instant from = Instant.now().minus(Duration.ofHours(1));
        Instant to = Instant.now().plus(Duration.ofHours(1));
        RewriteRunStore.Usage mine = runs.usage(client.id(), from, to);
        assertThat(mine.runs()).isEqualTo(3);
        assertThat(mine.withSafe()).isEqualTo(2);
        assertThat(mine.safeInRoundOne()).isEqualTo(1);
        assertThat(mine.reachedLaterRound()).isEqualTo(2);
        assertThat(mine.safeInLaterRound()).isEqualTo(1);
        assertThat(mine.roundOneRate()).isEqualTo(1.0 / 3);
        assertThat(mine.laterRoundRate()).isEqualTo(0.5);
        RewriteRunStore.Usage theirs = runs.usage(other.id(), from, to);
        assertThat(theirs.runs()).isEqualTo(1);
        assertThat(theirs.safeInRoundOne()).isEqualTo(1);
        assertThat(theirs.reachedLaterRound()).isZero();
        assertThat(theirs.laterRoundRate()).isNull();
    }

    @Test
    void thePanelSaysHowManyRoundsWereTriedInEnglishAndVietnameseAndTheStatsPageSplitsTheRates() throws Exception {
        ConsoleUser seller = seller();
        String m = marker();
        String title = tooLong(m);
        scripted.when(round(m, 1), answer(candidate(cut(title, 5))));
        scripted.when(round(m, 2), answer(candidate(SAFE)));

        String english = panel(seller, title, null);
        String vietnamese = panel(seller, title, "vi");

        assertThat(english).contains("id=\"rewrite-rounds\"").contains("Tried 2 of up to 3 rounds.")
                .contains("A round produced a suggestion that passed every check.").contains(SAFE);
        assertThat(vietnamese).contains("id=\"rewrite-rounds\"").contains("Đã thử 2 trên tối đa 3 vòng.")
                .contains("Một vòng đã cho gợi ý vượt qua mọi kiểm tra.");

        String stats = body(get("/console/stats").with(signedIn(seller)));
        assertThat(section(stats, "stats-rewrite")).contains("Safe suggestion in round 1").contains("Safe suggestion in round 2 or later")
                .contains("Round 1: 0 of 2 runs").contains("2 of the 2 runs that needed another round");
    }

    private String panel(ConsoleUser seller, String title, String language) throws Exception {
        MockHttpServletRequestBuilder request = post("/console/rewrite/editor").with(signedIn(seller)).with(csrf())
                .header("HX-Request", "true");
        etsyForm(title).forEach(request::param);
        if (language != null) {
            request.cookie(new Cookie(ConsoleLocaleResolver.COOKIE, language));
        }
        return body(request);
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
