package com.thanhdatpb.listingguard.ruleengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.FixAction;
import com.thanhdatpb.listingguard.core.finding.FixSuggestion;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.Money;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import com.thanhdatpb.listingguard.ruleengine.config.Source;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The engine verifies every candidate fix against the whole ruleset (ADR-014, A4). */
class RuleEngineFixTest {

    // ---- the real Etsy ruleset -------------------------------------------------------------------------------

    private static final RulesetConfig ETSY = RulesetLoader.loadClasspath("rulesets/etsy-2026-12.yaml");
    private static final RuleEngine RAW_ENGINE = new RuleEngine(RuleRegistry.withDefaults());
    private static final FixingEngine ETSY_ENGINE = new FixingEngine(RAW_ENGINE);
    private static final int TITLE_MAX = ETSY.rule("title_length").intParam("max_length");
    private static final int TAG_MAX = ETSY.rule("tag_length").intParam("max_length");

    private static Listing etsy(String title, List<String> tags) {
        return new Listing(title, "A nice mug for tea.", tags, new Money(new BigDecimal("19.99"), "USD"), "Home > Mugs",
                List.of("clay"), List.of("https://example.com/1.jpg"), 5, "i_did", "made_to_order");
    }

    /**
     * Distinct words longer than {@code minLength} altogether, with spaces to cut at and nothing any rule dislikes. Each word is
     * long enough that a title cut at {@code TITLE_MAX} has fewer words than the title word count guidance, so no rule reports
     * the cut title (the Etsy ruleset has a word count rule at INFO).
     */
    private static String words(int minLength) {
        int padding = TITLE_MAX / ETSY.rule("title_word_count").intParam("max_words") + 2;
        StringBuilder text = new StringBuilder();
        for (int i = 1; text.length() <= minLength; i++) {
            text.append(i > 1 ? " " : "").append("w".repeat(padding)).append(i);
        }
        return text.toString();
    }

    /** What a caller that wants fixes uses: here {@code run} is {@code runDetailed(...).findings()}. */
    private record FixingEngine(RuleEngine engine) {
        List<Finding> run(Listing listing, RulesetConfig config) {
            return engine.runDetailed(listing, config).findings();
        }

        RuleRun runDetailed(Listing listing, RulesetConfig config) {
            return engine.runDetailed(listing, config);
        }
    }

    private static Finding only(List<Finding> findings, String ruleId) {
        List<Finding> matching = findings.stream().filter(f -> f.ruleId().equals(ruleId)).toList();
        assertThat(matching).as("findings of " + ruleId).hasSize(1);
        return matching.get(0);
    }

    private static long problems(List<Finding> findings) {
        return findings.stream().filter(f -> f.severity() != Severity.INFO).count();
    }

    @Test
    void aTitleFixSurvivesAndResolvesItsFinding() {
        Listing listing = etsy(words(TITLE_MAX), List.of("mug"));

        Finding finding = only(ETSY_ENGINE.run(listing, ETSY), "title_length");

        assertThat(finding.fix()).isNotNull();
        assertThat(finding.fix().action()).isEqualTo(FixAction.REPLACE);
        String proposed = finding.fix().proposedValue();
        assertThat(proposed.codePointCount(0, proposed.length())).isLessThanOrEqualTo(TITLE_MAX);
        assertThat(ETSY_ENGINE.run(listing.withTitle(proposed), ETSY)).isEmpty();
    }

    @Test
    void aTagFixSurvivesAndResolvesItsFinding() {
        String tooLong = "mug " + "x".repeat(TAG_MAX);
        Listing listing = etsy("Handmade ceramic mug", List.of("ceramic", tooLong));

        Finding finding = only(ETSY_ENGINE.run(listing, ETSY), "tag_length");

        assertThat(finding.field()).isEqualTo("tags[1]");
        assertThat(finding.fix().proposedValue()).isEqualTo("mug");
        assertThat(ETSY_ENGINE.run(listing.withTags(List.of("ceramic", "mug")), ETSY)).isEmpty();
    }

    @Test
    void aDuplicateTagFixIsKept() {
        Listing listing = etsy("Handmade ceramic mug", List.of("mug", "ceramic", "Mug"));

        Finding finding = only(ETSY_ENGINE.run(listing, ETSY), "duplicate_tags");

        assertThat(finding.field()).isEqualTo("tags[2]");
        assertThat(finding.fix().action()).isEqualTo(FixAction.REMOVE);
    }

    @Test
    void aCutThatMakesADuplicateTagIsDropped() {
        // Cutting the second tag at a word boundary gives "mug", which the first tag already is.
        String tooLong = "mug " + "x".repeat(TAG_MAX);
        Listing listing = etsy("Handmade ceramic mug", List.of("mug", tooLong));

        Finding finding = only(ETSY_ENGINE.run(listing, ETSY), "tag_length");

        assertThat(ETSY.rule("duplicate_tags").severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.fix()).as("the fix would create a duplicate").isNull();
        assertThat(finding.suggestion()).startsWith("Rút ngắn tag này");
        assertThat(finding.value()).isEqualTo(tooLong);
    }

    @Test
    void everyFixThatSurvivesRemovesAProblemAndAddsNone() {
        List<Listing> listings = List.of(
                etsy(words(TITLE_MAX), List.of("mug")),
                etsy("Handmade ceramic mug", List.of("mug", "ceramic", "MUG", "mug", "gift " + "y".repeat(TAG_MAX))),
                etsy(words(TITLE_MAX), List.of("a b", "A B", "tea " + "z".repeat(TAG_MAX), "tea pot")));
        int checked = 0;
        for (Listing listing : listings) {
            List<Finding> before = ETSY_ENGINE.run(listing, ETSY);
            for (Finding finding : before) {
                if (finding.fix() == null) {
                    continue;
                }
                checked++;
                Listing fixed = FixApplier.apply(listing, finding).orElseThrow();
                assertThat(problems(ETSY_ENGINE.run(fixed, ETSY))).as(finding.field()).isLessThan(problems(before));
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(5);
    }

    // ---- made-up rules, to reach the cases the real ones cannot -------------------------------------------------

    private interface Body {
        List<Finding> run(Listing listing, RulesetConfig config, Rule self);
    }

    private static Rule rule(String id, Body body) {
        return new Rule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Finding> evaluate(Listing listing, RulesetConfig config) {
                return body.run(listing, config, this);
            }
        };
    }

    /** Flags the title "long" and proposes {@code replacement}. */
    private static Rule fixer(String replacement) {
        return rule("fixer", (listing, config, self) -> "long".equals(listing.title())
                ? List.of(self.finding(config, "title", "long", "too long", "shorten it",
                        FixSuggestion.replace("use " + replacement, replacement)))
                : List.of());
    }

    /** Flags the title {@code bad}, at the severity the config gives rule "guard". */
    private static Rule guard(String bad) {
        return rule("guard", (listing, config, self) -> bad.equals(listing.title())
                ? List.of(self.finding(config, "title", bad, "guard", "guard"))
                : List.of());
    }

    private static Rule broken(String id) {
        return rule(id, (listing, config, self) -> {
            throw new IllegalStateException("boom");
        });
    }

    private static RuleSettings settings(String id, Severity severity) {
        return new RuleSettings(id, severity, new Source(SourceStatus.UNVERIFIED, List.of(), null), Map.of());
    }

    private static RulesetConfig config(RuleSettings... settings) {
        Map<String, RuleSettings> rules = new LinkedHashMap<>();
        for (RuleSettings s : settings) {
            rules.put(s.ruleId(), s);
        }
        return new RulesetConfig("listing-rules", "TEST", "test-1", LocalDate.of(2026, 11, 5), "hash", rules);
    }

    private static Listing titled(String title) {
        return new Listing(title, null, List.of(), null, null, List.of(), List.of(), null, null, null);
    }

    private static FixingEngine engine(Rule... rules) {
        RuleRegistry registry = new RuleRegistry();
        for (Rule rule : rules) {
            registry.register("TEST", rule);
        }
        return new FixingEngine(new RuleEngine(registry));
    }

    private static Finding fixerFinding(List<Finding> findings) {
        return only(findings, "fixer");
    }

    @Test
    void aFixThatCreatesNothingNewIsKept() {
        FixingEngine engine = engine(fixer("short"), guard("other"));
        RulesetConfig config = config(settings("fixer", Severity.ERROR), settings("guard", Severity.ERROR));

        assertThat(fixerFinding(engine.run(titled("long"), config)).fix()).isNotNull();
    }

    @Test
    void aFixThatCreatesAnErrorIsDropped() {
        FixingEngine engine = engine(fixer("worse"), guard("worse"));
        RulesetConfig config = config(settings("fixer", Severity.ERROR), settings("guard", Severity.ERROR));

        Finding finding = fixerFinding(engine.run(titled("long"), config));

        assertThat(finding.fix()).isNull();
        assertThat(finding.suggestion()).isEqualTo("shorten it");
    }

    @Test
    void aFixThatCreatesAWarningIsDropped() {
        FixingEngine engine = engine(fixer("worse"), guard("worse"));
        RulesetConfig config = config(settings("fixer", Severity.ERROR), settings("guard", Severity.WARNING));

        assertThat(fixerFinding(engine.run(titled("long"), config)).fix()).isNull();
    }

    @Test
    void aFixThatCreatesOnlyAnInfoIsKept() {
        FixingEngine engine = engine(fixer("noted"), guard("noted"));
        RulesetConfig config = config(settings("fixer", Severity.ERROR), settings("guard", Severity.INFO));

        assertThat(fixerFinding(engine.run(titled("long"), config)).fix()).isNotNull();
    }

    @Test
    void aFixThatLeavesItsOwnProblemIsDropped() {
        Rule stubborn = rule("fixer", (listing, config, self) -> List.of(self.finding(config, "title", listing.title(),
                "always", "always", FixSuggestion.replace("use anything", "anything"))));
        FixingEngine engine = engine(stubborn);

        assertThat(fixerFinding(engine.run(titled("long"), config(settings("fixer", Severity.ERROR)))).fix()).isNull();
    }

    @Test
    void aFixAfterWhichARuleFailsIsDropped() {
        Rule fragile = rule("guard", (listing, config, self) -> {
            if ("fragile".equals(listing.title())) {
                throw new IllegalStateException("boom");
            }
            return List.of();
        });
        FixingEngine engine = engine(fixer("fragile"), fragile);
        RulesetConfig config = config(settings("fixer", Severity.ERROR), settings("guard", Severity.ERROR));

        assertThat(fixerFinding(engine.run(titled("long"), config)).fix()).isNull();
    }

    @Test
    void aFixOnAFieldTheEngineCannotApplyIsDropped() {
        Rule other = rule("fixer", (listing, config, self) -> List.of(self.finding(config, "description", "x", "m", "s",
                FixSuggestion.replace("d", "y"))));
        FixingEngine engine = engine(other);

        assertThat(fixerFinding(engine.run(titled("long"), config(settings("fixer", Severity.ERROR)))).fix()).isNull();
    }

    @Test
    void aRuleThatAlwaysFailsDoesNotBlockTheOtherFixes() {
        FixingEngine engine = engine(fixer("short"), broken("broken"));
        RulesetConfig config = config(settings("fixer", Severity.ERROR), settings("broken", Severity.ERROR));

        RuleRun run = engine.runDetailed(titled("long"), config);

        assertThat(fixerFinding(run.findings()).fix()).isNotNull();
        assertThat(run.failedRuleIds()).containsExactly("broken");
        assertThat(run.complete()).isFalse();
    }

    @Test
    void aCompleteRunSaysSoAndNamesItsPack() {
        FixingEngine engine = engine(fixer("short"));

        RuleRun run = engine.runDetailed(titled("long"), config(settings("fixer", Severity.ERROR)));

        assertThat(run.complete()).isTrue();
        assertThat(run.failedRuleIds()).isEmpty();
        assertThat(run.packId()).isEqualTo("listing-rules");
    }

    @Test
    void runGivesTheFindingsWithoutAnyFixAndRunDetailedAddsTheVerifiedFixes() {
        Listing listing = etsy(words(TITLE_MAX), List.of("mug", "Mug", "gift " + "y".repeat(TAG_MAX)));

        List<Finding> plain = RAW_ENGINE.run(listing, ETSY);
        List<Finding> detailed = RAW_ENGINE.runDetailed(listing, ETSY).findings();

        assertThat(plain).isNotEmpty().allSatisfy(finding -> assertThat(finding.fix()).isNull());
        assertThat(detailed).anySatisfy(finding -> assertThat(finding.fix()).isNotNull());
        assertThat(detailed.stream().map(Finding::withoutFix).toList()).isEqualTo(plain);
    }

    @Test
    void runIsTheSameAsBeforeEvenWhenARuleProposesAFix() {
        FixingEngine verified = engine(fixer("short"));
        RuleEngine raw = new RuleEngine(new RuleRegistry().register("TEST", fixer("short")));
        RulesetConfig config = config(settings("fixer", Severity.ERROR));

        assertThat(raw.run(titled("long"), config)).containsExactly(
                new Finding("fixer", Severity.ERROR, "title", "long", "too long", "shorten it", "test-1"));
        assertThat(verified.run(titled("long"), config).get(0).fix()).isNotNull();
    }

    @Test
    void findingsWithoutAFixAreReturnedAsTheRuleMadeThem() {
        FixingEngine engine = engine(guard("bad"));
        List<Finding> expected = new ArrayList<>(List.of(
                new Finding("guard", Severity.ERROR, "title", "bad", "guard", "guard", "test-1")));

        assertThat(engine.run(titled("bad"), config(settings("guard", Severity.ERROR)))).isEqualTo(expected);
    }
}
