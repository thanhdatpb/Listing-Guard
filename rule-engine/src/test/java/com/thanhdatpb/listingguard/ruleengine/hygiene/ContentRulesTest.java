package com.thanhdatpb.listingguard.ruleengine.hygiene;

import static com.thanhdatpb.listingguard.ruleengine.hygiene.ContentFixtures.AMAZON;
import static com.thanhdatpb.listingguard.ruleengine.hygiene.ContentFixtures.ETSY;
import static com.thanhdatpb.listingguard.ruleengine.hygiene.ContentFixtures.amazon;
import static com.thanhdatpb.listingguard.ruleengine.hygiene.ContentFixtures.etsy;
import static com.thanhdatpb.listingguard.ruleengine.hygiene.ContentFixtures.findings;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each content rule of ADR-039 against labelled texts: at least five that must be reported and five that must not, the second kind chosen
 * among the texts that look like the first ("Mom and Dad gift", "2 pack of 11oz mugs", a short brand in capitals). A text in a table is one
 * listing on a real marketplace, built from the valid fixture with only that text changed, so a rule that fires on something else fails too.
 */
class ContentRulesTest {

    /** A labelled case: a listing and whether the rule must report it. */
    record Case(String name, Listing listing, boolean flagged) {
    }

    private static final List<Case> ALL = new ArrayList<>();

    private static Case pos(String name, Listing listing) {
        return new Case(name, listing, true);
    }

    private static Case neg(String name, Listing listing) {
        return new Case(name, listing, false);
    }

    /** Runs the cases of a rule on the ruleset the listing belongs to and checks each. */
    private static void check(String ruleId, List<Case> cases) {
        long positives = cases.stream().filter(Case::flagged).count();
        long negatives = cases.size() - positives;
        assertThat(positives).as("%s has at least five positive cases", ruleId).isGreaterThanOrEqualTo(5);
        assertThat(negatives).as("%s has at least five negative cases", ruleId).isGreaterThanOrEqualTo(5);
        for (Case c : cases) {
            var config = c.listing().brand() == null ? ETSY : AMAZON;
            assertThat(ContentFixtures.ENGINE.runDetailed(c.listing(), config).failedRuleIds()).as("%s: %s", ruleId, c.name()).isEmpty();
            List<Finding> found = findings(c.listing(), config, ruleId);
            assertThat(!found.isEmpty()).as("%s: %s -> %s", ruleId, c.name(), found).isEqualTo(c.flagged());
            found.forEach(f -> {
                assertThat(f.severity()).isEqualTo(Severity.WARNING);
                assertThat(f.rulesetVersion()).isEqualTo(config.version());
            });
            ALL.add(c);
        }
    }

    private static Listing t(String title) {
        return etsy(title, "A nice mug for tea.");
    }

    private static Listing d(String description) {
        return etsy("Handmade ceramic mug", description);
    }

    private static Listing tags(String... tags) {
        return etsy("Handmade ceramic mug", "A nice mug for tea.", List.of(tags), "Home > Mugs");
    }

    private static Listing am(String title, String... bullets) {
        return amazon(title, List.of(bullets), "A durable suitcase.", "LUGGAGE");
    }

    private static Listing amType(String title, String type) {
        return amazon(title, List.of("Lightweight shell"), "A durable suitcase.", type);
    }

    @Test
    void bothNewRulesetsPassTheEngineValidationAndNoRuleFailsOnAnyCase() {
        ContentFixtures.ENGINE.validate(ETSY);
        ContentFixtures.ENGINE.validate(AMAZON);
    }

    @Test
    void mojibake() {
        check("mojibake", List.of(
                pos("e acute read as latin1", t("CafÃ© Mug")),
                pos("apostrophe", t("Donâ€™t Stop Mug")),
                pos("replacement character", t("Funny � Mug")),
                pos("copyright sign", d("Great design Â© 2024 by me")),
                pos("vietnamese", d("Ly Ä‘áº¹p cho báº¡n")),
                pos("in a tag", tags("cafÃ¨ mug")),
                pos("amazon bullet", am("Carry-on luggage", "SoftshellÂ® exterior")),
                neg("e acute", t("Café Mug")),
                neg("n tilde", t("Piñata Party Mug")),
                neg("portuguese capital A tilde before a letter", t("NÃO Mug")),
                neg("diaeresis", t("Noël Ornament")),
                neg("vietnamese", d("Ly đẹp cho bạn, tặng mẹ")),
                neg("ring above", t("Ångström Mug")),
                neg("trademark sign", t("Roadster™ Mug"))));
    }

    @Test
    void placeholderLeftover() {
        check("placeholder_leftover", List.of(
                pos("bracketed name", t("[NAME] Coffee Mug")),
                pos("braces name", d("Made for {name} with love")),
                pos("double braces", d("Hello {{first_name}}")),
                pos("lorem ipsum", d("Lorem ipsum dolor sit amet")),
                pos("TBD", d("Size: TBD")),
                pos("run of X", t("Mug XXXXX")),
                pos("insert text here", d("Insert text here")),
                pos("angle brackets", t("Mug for <your name>")),
                pos("amazon bullet", am("Carry-on luggage", "[YOUR TEXT HERE] shell")),
                neg("XXXL is a size", t("Funny Mom Shirt XXXL")),
                neg("the word name", t("Name Necklace Gift")),
                neg("brackets with a real word", t("Mug [Limited] Edition")),
                neg("braces around a phrase", t("Mug {Set of 2}")),
                neg("question marks", d("What? Really??")),
                neg("XOXO", d("Hugs and XOXO")),
                neg("lower case tbd inside a word", d("Trebled tbdx"))));
    }

    @Test
    void whitespace() {
        check("whitespace", List.of(
                pos("double space in the title", t("Funny  Mug")),
                pos("no-break space", t("Funny Mug")),
                pos("zero-width space", t("Funny​ Mug")),
                pos("space before a comma", t("Funny Mug , Gift")),
                pos("tab", t("Funny\tMug")),
                pos("double space in a tag", tags("coffee  mug")),
                pos("double space in a description", d("A nice mug  for tea.")),
                neg("single spaces", t("Funny Mug - 11oz, Gift")),
                neg("blank line in a description", d("Line one\n\nLine two")),
                neg("indented line", d("Line one\n    indented line")),
                neg("trailing space before a line break", d("Line one \nLine two")),
                neg("comma followed by a space", t("Mug, Gift")),
                neg("one space after a full stop", d("A nice mug. For tea."))));
        Finding title = findings(t("Funny  Mug  Gift"), ETSY, "whitespace").get(0);
        assertThat(title.fix()).as("a title has a certain fix").isNotNull();
        assertThat(title.fix().proposedValue()).isEqualTo("Funny Mug Gift");
        assertThat(title.matchStart()).isEqualTo(5);
        assertThat(findings(d("A nice mug  for tea."), ETSY, "whitespace").get(0).fix()).as("no fix for a description").isNull();
    }

    @Test
    void unbalancedBrackets() {
        check("unbalanced_brackets", List.of(
                pos("open round", t("Mug (11oz")),
                pos("closing round", t("Mug 11oz)")),
                pos("open square", t("Mug [Pink")),
                pos("open curly", t("Mug {Set")),
                pos("mismatched pair", t("Mug (Set of 2]")),
                pos("in a description", d("Holds (about 11oz of tea.")),
                pos("amazon bullet", am("Carry-on luggage", "Hard shell (ABS")),
                neg("balanced", t("Mug (Set of 2)")),
                neg("smiley", t("Funny Mug :)")),
                neg("smiley with a nose", t("Great Mug :-)")),
                neg("list markers", d("1) Fast 2) Cheap")),
                neg("two groups", t("Mug [Pink] (Large)")),
                neg("nested", t("Mug (a (b))"))));
    }

    @Test
    void repeatedWord() {
        check("repeated_word", List.of(
                pos("mug mug", t("Funny Mug mug Gift")),
                pos("the the", d("This is the the best mug")),
                pos("same case", t("Gift Gift Mug")),
                pos("a longer word", t("Personalized Personalized Mug")),
                pos("in a description", d("A nice nice mug for tea.")),
                pos("amazon bullet", am("Carry-on luggage", "Strong strong shell")),
                neg("Mom Mom", t("Mom Mom Gift Mug")),
                neg("no no no", t("No No No Sign")),
                neg("comma between", t("Mug, mug")),
                neg("hyphen", t("Ho-Ho-Ho Mug")),
                neg("a number between", t("Mug 11 11oz")),
                neg("bye bye", t("Bye Bye Mug")),
                neg("a line break between", d("A nice\nnice mug"))));
    }

    @Test
    void allCapsRatio() {
        check("all_caps_ratio", List.of(
                pos("whole title", t("VINTAGE RETRO FUNNY COFFEE MUG")),
                pos("short words", t("BEST GIFT FOR MOM")),
                pos("three of four", t("FUNNY COFFEE MUG Gift")),
                pos("description", d("PERFECT GIFT FOR ANYONE WHO LOVES TEA")),
                pos("amazon bullet", am("Carry-on luggage", "STRONG HARD SHELL LUGGAGE")),
                neg("short brand in capitals", t("BMW Racing Mug")),
                neg("NASA", t("NASA Space Mug")),
                neg("one capital phrase among words", t("Best Mom Ever Mug")),
                neg("acronym", t("DIY Craft Kit Mug")),
                neg("country code", t("USA Flag Mug")),
                neg("two words", t("LEGO Brick Mug")),
                neg("sentence case", d("A nice mug for tea. Dishwasher safe."))));
    }

    @Test
    void sizeUnitConflict() {
        check("size_unit_conflict", List.of(
                pos("ounces", etsy("Funny 11oz Mug", "This 15 oz mug holds coffee.")),
                pos("print size", etsy("Poster 12x16 inch", "Print size: 16x20 inches.")),
                pos("a tag", etsy("Mug 11oz", "A nice mug.", List.of("15oz mug"), "Home > Mugs")),
                pos("millilitres", etsy("Bottle 500 ml", "Holds 750ml of water.")),
                pos("amazon bullet", amazon("Tumbler 20 oz", List.of("Holds 30 oz of liquid"), "A durable tumbler.", "TUMBLER")),
                neg("a count and a size", etsy("2 pack of 11oz mugs", "Each 11oz mug is dishwasher safe.")),
                neg("options are listed", etsy("Mug 11oz", "Available in 11oz and 15oz.")),
                neg("a weight is not a size", etsy("Mug 11oz", "The mug weighs 8 oz empty.")),
                neg("another unit", etsy("Mug 11oz", "The handle is 4 inches long.")),
                neg("one text only", etsy("Mug 11oz", "A nice mug for tea.")),
                neg("the same size", etsy("Mug 11oz", "An 11 oz ceramic mug.")),
                neg("options with or", etsy("Mug 11oz", "Choose 15oz or 11oz at checkout."))));
        Finding f = findings(etsy("Funny 11oz Mug", "This 15 oz mug holds coffee."), ETSY, "size_unit_conflict").get(0);
        assertThat(f.field()).isEqualTo("description");
        assertThat(f.value()).isEqualTo("15 oz");
        assertThat("This 15 oz mug holds coffee.".substring(f.matchStart(), f.matchEnd())).isEqualTo("15 oz");
        assertThat(f.message()).contains("11oz").contains("15 oz").contains("title").contains("description");
    }

    @Test
    void quantityConflict() {
        check("quantity_conflict", List.of(
                pos("pack and pieces", etsy("Set of 2 Mugs", "Includes 4 pieces.")),
                pos("pack of", etsy("Pack of 3 Stickers", "Each pack of 6 stickers.")),
                pos("number word", etsy("Set of two Mugs", "Sold as a pack of 4.")),
                pos("quantity label", etsy("Pack of 2 Mugs", "Qty: 1")),
                pos("amazon bullet", amazon("Cups, Pack of 12", List.of("Includes 6 pieces"), "Cups.", "DRINKING_CUP")),
                neg("a count and a size", etsy("2 pack of 11oz mugs", "A 2 pack of mugs.")),
                neg("number word equals digit", etsy("Set of 2 Mugs", "Set of two ceramic mugs.")),
                neg("options are listed", etsy("Pack of 2 Mugs", "Available in a 4 pack or 2 pack.")),
                neg("no quantity in the title", etsy("Funny Mug", "Includes 4 pieces.")),
                neg("a set of instructions", etsy("Set of 2 Mugs", "Comes with 1 set of instructions.")),
                neg("a size after pack of", etsy("Pack of 11oz Mugs", "A pack of 11oz mugs.")),
                neg("one text only", etsy("Set of 2 Mugs", "A nice mug for tea."))));
    }

    @Test
    void recipientConflict() {
        check("recipient_conflict", List.of(
                pos("mom and dad", etsy("Gift for Mom Mug", "Perfect for dad.")),
                pos("him and her", etsy("Funny Mug For Him", "Great gift for her.")),
                pos("grandma and grandpa", etsy("Grandma Mug", "Grandpa will love it.")),
                pos("girl and boy", etsy("Girl Mug", "Perfect for boys.")),
                pos("wife and husband", etsy("Wife Mug", "A gift for your husband.")),
                pos("amazon bullet", amazon("Mother's Day Mug", List.of("Perfect for Father's Day"), "A mug.", "MUG")),
                neg("Mom and Dad gift", etsy("Mom and Dad Gift Mug", "Perfect for dad.")),
                neg("same recipient", etsy("Mom Mug", "Mom will love this.")),
                neg("also great for", etsy("Gift Mug for Mom", "Also great for dad.")),
                neg("only one text", etsy("Mom Mug", "A nice mug for tea.")),
                neg("same axis same value", etsy("Mom Mug", "A Mother's Day gift.")),
                neg("him and her in the description", etsy("Gift for him", "Great gift for him and her.")),
                neg("pronoun alone", etsy("Mug for him", "Her favourite colour is blue."))));
    }

    @Test
    void productNounConflict() {
        check("product_noun_conflict", List.of(
                pos("mug on a shirt", amType("Funny Cat Mug", "SHIRT")),
                pos("poster on a mug", amType("Mom Poster", "MUG")),
                pos("hoodie on a mug", amType("Cozy Hoodie", "MUG")),
                pos("tumbler on a shirt", amType("Steel Tumbler", "SHIRT")),
                pos("sticker on a pillow", amType("Sticker Pack", "PILLOW")),
                pos("etsy category", etsy("Funny Cat T-Shirt", "A nice shirt.", List.of("shirt"), "Home > Mugs")),
                neg("the type's noun among others", amType("Mug and Tee Set", "MUG")),
                neg("same", amType("Funny Cat Mug", "MUG")),
                neg("a type the table does not list", amType("Funny Cat Mug", "LUGGAGE")),
                neg("a title with no noun", amType("Funny Gift", "SHIRT")),
                neg("no type", amType("Funny Cat Mug", null)),
                neg("t-shirt on a shirt", amType("Cat T-Shirt", "SHIRT")),
                neg("sweatshirt is a shirt too", amType("Cat Sweatshirt", "SHIRT")),
                neg("type in lower case", amType("Funny Cat Mug", "mug")),
                neg("etsy category matches", etsy("Funny Cat Mug", "A nice mug."))));
    }

    @Test
    void aTextThatMatchesCarriesTheSpanOfItsValue() {
        record Matching(String ruleId, Listing listing, String field, String text) {
        }
        List<Matching> matching = List.of(
                new Matching("mojibake", t("CafÃ© Mug"), "title", "CafÃ© Mug"),
                new Matching("placeholder_leftover", t("Mug [NAME] Gift"), "title", "Mug [NAME] Gift"),
                new Matching("unbalanced_brackets", t("Mug (11oz"), "title", "Mug (11oz"),
                new Matching("repeated_word", t("Funny Mug mug Gift"), "title", "Funny Mug mug Gift"),
                new Matching("all_caps_ratio", t("VINTAGE RETRO FUNNY COFFEE MUG"), "title", "VINTAGE RETRO FUNNY COFFEE MUG"),
                new Matching("recipient_conflict", etsy("Gift for Mom Mug", "Perfect for dad."), "description", "Perfect for dad."),
                new Matching("quantity_conflict", etsy("Set of 2 Mugs", "Includes 4 pieces."), "description", "Includes 4 pieces."),
                new Matching("product_noun_conflict", amType("Funny Cat Mug", "SHIRT"), "title", "Funny Cat Mug"));
        for (Matching m : matching) {
            var config = m.listing().brand() == null ? ETSY : AMAZON;
            Finding f = findings(m.listing(), config, m.ruleId()).get(0);
            assertThat(f.field()).as(m.ruleId()).isEqualTo(m.field());
            assertThat(f.matchStart()).as(m.ruleId()).isNotNull();
            assertThat(m.text().substring(f.matchStart(), f.matchEnd())).as(m.ruleId()).isEqualTo(f.value());
        }
    }

    @Test
    void theValidListingsOfBothMarketplacesHaveNoFindingOfTheContentRules() {
        List<String> ids = ContentRules.all().stream().map(com.thanhdatpb.listingguard.ruleengine.Rule::id).toList();
        assertThat(ids).hasSize(10);
        for (Listing listing : List.of(t("Handmade ceramic mug"), am("Hardside carry-on luggage with spinner wheels", "Lightweight hardside shell"))) {
            var config = listing.brand() == null ? ETSY : AMAZON;
            assertThat(ContentFixtures.ENGINE.run(listing, config)).extracting(Finding::ruleId).doesNotContainAnyElementsOf(ids);
        }
    }

    @Test
    void everyContentRuleIsOptionalAndSaysItIsAnInternalQualityRule() {
        for (var config : List.of(ETSY, AMAZON)) {
            for (var rule : ContentRules.all()) {
                assertThat(rule.optional()).isTrue();
                var settings = config.rule(rule.id());
                assertThat(settings.severity()).isEqualTo(Severity.WARNING);
                assertThat(settings.source().status().name()).isEqualTo("UNVERIFIED");
                assertThat(settings.source().note()).contains("nội bộ").contains("không phải chính sách của sàn");
            }
        }
    }
}
