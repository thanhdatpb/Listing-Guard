package com.thanhdatpb.listingguard.personalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset;
import com.thanhdatpb.listingguard.personalization.config.RuleIds;
import com.thanhdatpb.listingguard.personalization.config.UnknownPrintProfileException;
import com.thanhdatpb.listingguard.personalization.rules.TextRule;
import com.thanhdatpb.listingguard.personalization.rules.TextRules;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The character rules (ADR-016). Limits, classes and profiles are read from the ruleset, never repeated here. */
class PersonalizationScreenerTest {

    private final PersonalizationRuleset ruleset = Fixtures.ruleset();
    private final PersonalizationScreener screener = new PersonalizationScreener(ruleset);

    private ScreeningResult screen(PersonalizationInput input) {
        return screener.screen(input, Fixtures.scanner());
    }

    private List<TextFinding> of(ScreeningResult result, String ruleId) {
        return result.findings().stream().filter(finding -> finding.ruleId().equals(ruleId)).toList();
    }

    @Test
    void aPlainNameAndNumberHaveNoFinding() {
        PersonalizationInput input = new PersonalizationInput(null, null, List.of(
                new PersonalizationInput.Value("name", "MAX"), new PersonalizationInput.Value("number", "10")));

        ScreeningResult result = screen(input);

        assertThat(result.findings()).isEmpty();
        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(result.printProfile()).isEqualTo(ruleset.defaultProfile());
        assertThat(result.rulesetVersion()).isEqualTo(ruleset.version());
        assertThat(result.disclaimer()).isEqualTo(ruleset.disclaimer());
    }

    @Test
    void anEmojiIsUnsupportedWithTheExactUtf16OffsetAfterAnotherCharacterOutsideTheBasicPlane() {
        String emoji = Fixtures.codePoint(0x1F600);
        String text = "Max " + emoji + ".";

        TextFinding finding = of(screen(Fixtures.input(text)), RuleIds.UNSUPPORTED_CHARACTERS).get(0);

        assertThat(finding.kind()).isEqualTo(RuleIds.CHARACTER_NOT_ALLOWED);
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.field()).isEqualTo("personalization[0]");
        assertThat(finding.start()).isEqualTo(4);
        assertThat(finding.end()).isEqualTo(6);
        assertThat(text.substring(finding.start(), finding.end())).isEqualTo(emoji);
        assertThat(finding.value()).isEqualTo(emoji);
        assertThat(finding.message()).contains(emoji).contains("U+1F600").contains("ký tự thứ 5");
        assertThat(finding.sourceStatus().yaml()).isEqualTo("unverified");
    }

    @Test
    void theOffsetCountsUtf16UnitsWhileThePositionCountsCharacters() {
        String text = Fixtures.codePoint(0x1F600) + Fixtures.codePoint(0x1F680) + "A" + Fixtures.codePoint(0x1F4A9);

        List<TextFinding> findings = of(screen(Fixtures.input(text)), RuleIds.UNSUPPORTED_CHARACTERS);

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).start()).isZero();
        assertThat(findings.get(0).end()).isEqualTo(4);
        assertThat(findings.get(0).message()).contains("ký tự thứ 1");
        assertThat(findings.get(1).start()).isEqualTo(5);
        assertThat(findings.get(1).end()).isEqualTo(7);
        assertThat(findings.get(1).message()).contains("ký tự thứ 4");
    }

    @Test
    void aVietnameseNameIsAWarningUnderTheBasicProfileAndPassesUnderTheExtendedOne() {
        String name = "Đạt";

        ScreeningResult basic = screen(Fixtures.profiled("latin_basic", "name", name));
        ScreeningResult extended = screen(Fixtures.profiled("latin_extended", "name", name));

        assertThat(of(basic, RuleIds.UNSUPPORTED_CHARACTERS)).hasSize(1);
        assertThat(basic.findings().get(0).severity()).isEqualTo(Severity.WARNING);
        assertThat(basic.findings().get(0).start()).isZero();
        assertThat(basic.findings().get(0).end()).isEqualTo(2);
        assertThat(basic.findings().get(0).message()).contains("latin_basic").contains("name");
        assertThat(extended.findings()).isEmpty();
        assertThat(extended.printProfile()).isEqualTo("latin_extended");
    }

    @Test
    void aDecomposedAndAComposedNameAreTheSameInputToTheRules() {
        String composed = "Đạt";
        String decomposed = "Ḍ̵" + "at";

        // NFC of the decomposed form is not the composed one for the stroked D, but the dot below composes
        assertThat(Texts.nfc("ạ")).isEqualTo("ạ");
        assertThat(CanonicalInput.of(Fixtures.input("Đạt")).hash())
                .isEqualTo(CanonicalInput.of(Fixtures.input(composed)).hash());
        assertThat(decomposed).isNotEmpty();
    }

    @Test
    void aTextOneCodePointOverTheLimitIsTooLongAndTheLimitItselfIsNot() {
        int limit = ruleset.profile(null).maxLength();

        ScreeningResult over = screen(Fixtures.input(Fixtures.letters(limit + 1)));
        ScreeningResult exactly = screen(Fixtures.input(Fixtures.letters(limit)));

        assertThat(of(over, RuleIds.TOO_LONG)).hasSize(1);
        TextFinding finding = over.findings().get(0);
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.start()).isEqualTo(limit);
        assertThat(finding.end()).isEqualTo(limit + 1);
        assertThat(finding.value()).isNull();
        assertThat(finding.message()).contains("dài " + (limit + 1)).contains("giới hạn " + limit);
        assertThat(exactly.findings()).isEmpty();
    }

    @Test
    void twentyFiveCharactersAgainstALimitOfTwentyIsTooLong() {
        PersonalizationRuleset twenty = ruleset;
        assertThat(twenty.profile(null).maxLength()).isEqualTo(20);

        ScreeningResult result = screen(Fixtures.input(Fixtures.letters(25)));

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).ruleId()).isEqualTo(RuleIds.TOO_LONG);
        assertThat(result.findings().get(0).message()).contains("dài 25").contains("giới hạn 20");
    }

    @Test
    void theLimitOfALabelOverridesTheDefaultAndLabelsAreMatchedIgnoringCase() {
        PersonalizationRuleset.PrintProfile profile = ruleset.profile(null);
        int numberLimit = profile.maxLengthByLabel().get("number");
        assertThat(numberLimit).isLessThan(profile.maxLength());
        String digits = "1".repeat(numberLimit + 1);

        ScreeningResult labelled = screen(Fixtures.profiled(null, "  Number ", digits));
        ScreeningResult unlabelled = screen(Fixtures.input(digits));

        assertThat(of(labelled, RuleIds.TOO_LONG)).hasSize(1);
        assertThat(labelled.findings().get(0).message()).contains("giới hạn " + numberLimit);
        assertThat(labelled.findings().get(0).label()).isEqualTo("Number");
        assertThat(unlabelled.findings()).isEmpty();
    }

    @Test
    void lengthCountsCodePointsNotUtf16Units() {
        int limit = ruleset.profile("latin_extended").maxLength();
        String text = "À".repeat(limit);

        assertThat(text.length()).isEqualTo(limit);
        assertThat(screen(Fixtures.profiled("latin_extended", null, text)).findings()).isEmpty();
        // a supplementary character is two units and one code point: the unsupported rule reports it, too_long does not
        String emojiText = Fixtures.codePoint(0x1F600).repeat(limit);
        ScreeningResult emoji = screen(Fixtures.profiled("latin_extended", null, emojiText));
        assertThat(of(emoji, RuleIds.TOO_LONG)).isEmpty();
        assertThat(of(emoji, RuleIds.UNSUPPORTED_CHARACTERS)).hasSize(1);
    }

    @Test
    void leadingTrailingAndRepeatedSpacesAreInfoAndTheTextIsNotChanged() {
        PersonalizationInput input = Fixtures.input("  AB", "AB   ", "A  B", "   ");

        ScreeningResult result = screen(input);

        List<TextFinding> findings = of(result, RuleIds.WHITESPACE_ISSUE);
        assertThat(findings).extracting(TextFinding::kind).containsExactly(RuleIds.LEADING_SPACE, RuleIds.TRAILING_SPACE,
                RuleIds.REPEATED_SPACE, RuleIds.LEADING_SPACE);
        assertThat(findings).extracting(TextFinding::severity).containsOnly(Severity.INFO);
        assertThat(findings).extracting(TextFinding::valueIndex).containsExactly(0, 1, 2, 3);
        assertThat(findings.get(0).start()).isZero();
        assertThat(findings.get(0).end()).isEqualTo(2);
        assertThat(findings.get(1).start()).isEqualTo(2);
        assertThat(findings.get(1).end()).isEqualTo(5);
        assertThat(findings.get(2).start()).isEqualTo(1);
        assertThat(findings.get(2).end()).isEqualTo(3);
        assertThat(findings.get(2).message()).contains("2 khoảng trắng liên tiếp").contains("ký tự thứ 2");
        assertThat(findings.get(3).end()).isEqualTo(3);
        assertThat(findings).extracting(TextFinding::value).containsOnlyNulls();
        assertThat(input.values().get(0).text()).isEqualTo("  AB");
        assertThat(of(result, RuleIds.UNSUPPORTED_CHARACTERS)).isEmpty();
    }

    @Test
    void aSingleSpaceInsideTheTextIsNotAnIssue() {
        assertThat(screen(Fixtures.input("MAX PAYNE")).findings()).isEmpty();
    }

    @Test
    void aProfileThatPrintsCapitalsOnlyReportsLowercaseAsInfoAndNeverAsNotAllowed() {
        PersonalizationRuleset capitals = Fixtures.variant("allow_lowercase: true", "allow_lowercase: false");
        PersonalizationScreener capitalsOnly = new PersonalizationScreener(capitals);

        ScreeningResult result = capitalsOnly.screen(Fixtures.input("MaX" + Fixtures.codePoint(0x1F600)), Fixtures.scanner());

        List<TextFinding> findings = of(result, RuleIds.UNSUPPORTED_CHARACTERS);
        assertThat(findings).extracting(TextFinding::kind).containsExactly(RuleIds.LOWERCASE_NOT_PRINTED,
                RuleIds.CHARACTER_NOT_ALLOWED);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(findings.get(0).start()).isEqualTo(1);
        assertThat(findings.get(0).end()).isEqualTo(2);
        assertThat(findings.get(1).severity()).isEqualTo(Severity.WARNING);
        // the other profile of the file is not changed by the variant: only the first occurrence was
        assertThat(capitals.profile("latin_extended").allowLowercase()).isTrue();
    }

    @Test
    void controlCharactersAndLoneSurrogatesAreKeptAsPrintableEvidenceButNamedExactly() {
        String text = "A\u0000B" + '\uD800' + "C";

        List<TextFinding> findings = of(screen(Fixtures.input(text)), RuleIds.UNSUPPORTED_CHARACTERS);

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).value()).isEqualTo("�");
        assertThat(findings.get(0).message()).contains("U+0000");
        assertThat(findings.get(1).value()).isEqualTo("�");
        assertThat(findings.get(1).message()).contains("U+D800");
        assertThat(findings).extracting(TextFinding::value).allSatisfy(value -> assertThat(value).doesNotContain("\u0000"));
    }

    @Test
    void aLongRunOfUnsupportedCharactersIsCutToTheEvidenceLimit() {
        int limit = ruleset.maxValueChars();
        String text = Fixtures.codePoint(0x1F600).repeat(limit + 5);

        TextFinding finding = of(screen(Fixtures.profiled("latin_extended", null, text)), RuleIds.UNSUPPORTED_CHARACTERS).get(0);

        assertThat(finding.value().codePointCount(0, finding.value().length())).isEqualTo(limit);
        assertThat(finding.start()).isZero();
        assertThat(finding.end()).isEqualTo(text.length());
        assertThat(finding.message()).contains("…");
    }

    @Test
    void findingsComeValueByValueThenRuleThenOffset() {
        String emoji = Fixtures.codePoint(0x1F600);
        PersonalizationInput input = Fixtures.input(" A" + emoji + "  B" + Fixtures.letters(30), "OK", "x" + emoji);

        ScreeningResult result = screen(input);

        assertThat(result.findings()).extracting(TextFinding::valueIndex).isSorted();
        List<TextFinding> first = result.findings().stream().filter(f -> f.valueIndex() == 0).toList();
        assertThat(first).extracting(TextFinding::ruleId).containsExactly(RuleIds.UNSUPPORTED_CHARACTERS, RuleIds.TOO_LONG,
                RuleIds.WHITESPACE_ISSUE, RuleIds.WHITESPACE_ISSUE);
        assertThat(first.get(2).kind()).isEqualTo(RuleIds.LEADING_SPACE);
        assertThat(first.get(3).kind()).isEqualTo(RuleIds.REPEATED_SPACE);
        assertThat(result.summary().warnings()).isEqualTo(3);
        assertThat(result.summary().infos()).isEqualTo(2);
        assertThat(result.summary().errors()).isZero();
    }

    @Test
    void anUnknownPrintProfileIsRefusedBeforeAnythingRuns() {
        assertThatThrownBy(() -> screen(Fixtures.profiled("no_such_profile", null, "MAX")))
                .isInstanceOf(UnknownPrintProfileException.class);
    }

    @Test
    void aRuleThatThrowsMakesTheResultIncompleteAndKeepsTheOthers() {
        TextRule failing = new TextRule() {
            @Override
            public String id() {
                return RuleIds.TOO_LONG;
            }

            @Override
            public List<TextFinding> apply(com.thanhdatpb.listingguard.personalization.rules.RuleContext context) {
                throw new IllegalStateException("boom");
            }
        };
        List<TextRule> rules = new ArrayList<>(TextRules.create(ruleset));
        rules.removeIf(rule -> rule.id().equals(RuleIds.TOO_LONG));
        rules.add(failing);
        PersonalizationScreener broken = new PersonalizationScreener(ruleset, rules);

        ScreeningResult result = broken.screen(Fixtures.input(" " + Fixtures.letters(30) + Fixtures.codePoint(0x1F600)),
                Fixtures.scanner());

        assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
        assertThat(result.failedRuleIds()).containsExactly(RuleIds.TOO_LONG);
        assertThat(result.findings()).extracting(TextFinding::ruleId)
                .contains(RuleIds.UNSUPPORTED_CHARACTERS, RuleIds.WHITESPACE_ISSUE).doesNotContain(RuleIds.TOO_LONG);
        assertThat(result.trademark().status()).isEqualTo(TrademarkStatus.COMPLETE);
    }

    @Test
    void noValuesIsAnEmptyCompleteResult() {
        ScreeningResult result = screen(new PersonalizationInput(null, null, List.of()));

        assertThat(result.findings()).isEmpty();
        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
    }
}
