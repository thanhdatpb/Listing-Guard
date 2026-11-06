package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.dropdown;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.text;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.upload;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.valid;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.withQuestions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.PersonalizationQuestion;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The personalization rules other than the question count, one nested class each. Every number is read from the ruleset. */
class PersonalizationRulesTest {

    private static PersonalizationQuestion with(String type, String question, String instructions, Integer maxFiles,
                                                List<String> options) {
        return new PersonalizationQuestion(type, question, instructions, null, null, options, maxFiles);
    }

    @Nested
    class QuestionText {

        private final PersonalizationQuestionTextRule rule = new PersonalizationQuestionTextRule();
        private final int min = limit("personalization_question_text", "min_length");
        private final int max = limit("personalization_question_text", "max_length");

        @Test
        void textsFromTheMinimumToTheMaximumAreValid() {
            assertThat(rule.evaluate(withQuestions(text("x".repeat(min)), text("y".repeat(max))), CONFIG)).isEmpty();
        }

        @Test
        void aTextOneOverTheMaximumIsAnErrorThatCountsCodePoints() {
            String tooLong = "x".repeat(max + 1);

            List<Finding> findings = rule.evaluate(withQuestions(text("Name"), text(tooLong)), CONFIG);

            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.ruleId()).isEqualTo("personalization_question_text");
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.field()).isEqualTo("personalization.questions[1].question_text");
            assertThat(finding.value()).isEqualTo(tooLong);
            assertThat(finding.message()).contains("dài " + (max + 1) + " ký tự, tối đa " + max + ".");
            assertThat(finding.suggestion()).isEqualTo("Rút ngắn câu hỏi ít nhất 1 ký tự.");
            assertThat(finding.fix()).isNull();
        }

        @Test
        void anEmojiCountsOnce() {
            String emoji = new String(Character.toChars(0x1F600));

            assertThat(rule.evaluate(withQuestions(text(emoji.repeat(max))), CONFIG)).isEmpty();
            assertThat(rule.evaluate(withQuestions(text(emoji.repeat(max + 1))), CONFIG)).hasSize(1);
        }

        @Test
        void aQuestionWithNoTextIsBelowTheMinimum() {
            List<Finding> findings = rule.evaluate(withQuestions(text("Name"), text(null)), CONFIG);

            assertThat(findings).hasSize(1);
            assertThat(findings.get(0).field()).isEqualTo("personalization.questions[1].question_text");
            assertThat(findings.get(0).value()).isNull();
            assertThat(findings.get(0).message())
                    .isEqualTo("Câu hỏi cá nhân hóa thứ 2 chưa có nội dung, cần ít nhất " + min + " ký tự.");
            assertThat(findings.get(0).severity()).isEqualTo(Severity.ERROR);
        }

        @Test
        void aListingWithoutPersonalizationHasNoFinding() {
            assertThat(rule.evaluate(valid(), CONFIG)).isEmpty();
        }

        @Test
        void bothBoundsAreReadFromTheRuleset() {
            RulesetConfig broken = RulesetLoader.parse("""
                    marketplace: ETSY
                    version: t
                    checked_at: 2026-11-06
                    rules:
                      personalization_question_text:
                        severity: ERROR
                        max_length: 45
                        source:
                          status: unverified
                    """);

            assertThatThrownBy(() -> rule.validate(broken)).isInstanceOf(RulesetException.class)
                    .hasMessageContaining("min_length");
        }
    }

    @Nested
    class DropdownOptions {

        private final PersonalizationDropdownOptionsRule rule = new PersonalizationDropdownOptionsRule();
        private final int min = limit("personalization_dropdown_options", "min_count");
        private final int max = limit("personalization_dropdown_options", "max_count");
        private final int maxLength = limit("personalization_dropdown_options", "max_length");

        private String[] options(int count) {
            return IntStream.range(0, count).mapToObj(i -> "Option " + i).toArray(String[]::new);
        }

        @Test
        void aDropdownFromTheMinimumToTheMaximumOptionsIsValid() {
            assertThat(rule.evaluate(withQuestions(dropdown("Few", options(min)), dropdown("Many", options(max))), CONFIG))
                    .isEmpty();
        }

        @Test
        void oneOptionOverTheMaximumIsAnErrorOnTheOptionList() {
            List<Finding> findings = rule.evaluate(withQuestions(dropdown("Font", options(max + 1))), CONFIG);

            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.ruleId()).isEqualTo("personalization_dropdown_options");
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.field()).isEqualTo("personalization.questions[0].options");
            assertThat(finding.value()).isNull();
            assertThat(finding.message()).isEqualTo("Dropdown câu hỏi 'Font' có " + (max + 1) + " lựa chọn, tối đa " + max + ".");
            assertThat(finding.suggestion()).isEqualTo("Bỏ bớt 1 lựa chọn.");
            assertThat(finding.fix()).isNull();
        }

        @Test
        void aDropdownWithTooFewOptionsIsAnError() {
            List<Finding> findings = rule.evaluate(withQuestions(dropdown("Font", options(min - 1))), CONFIG);

            assertThat(findings).hasSize(1);
            assertThat(findings.get(0).message())
                    .isEqualTo("Dropdown câu hỏi 'Font' có " + (min - 1) + " lựa chọn, cần ít nhất " + min + ".");
        }

        @Test
        void anOptionOfTheMaximumLengthIsValidAndOneMoreIsAnErrorAtItsPosition() {
            String atLimit = "x".repeat(maxLength);
            String tooLong = "y".repeat(maxLength + 1);

            List<Finding> findings = rule.evaluate(withQuestions(dropdown("Font", "A", atLimit, tooLong)), CONFIG);

            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.field()).isEqualTo("personalization.questions[0].options[2]");
            assertThat(finding.value()).isEqualTo(tooLong);
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.message()).contains("dài " + (maxLength + 1) + " ký tự, tối đa " + maxLength + ".");
            assertThat(finding.suggestion()).isEqualTo("Rút ngắn lựa chọn này ít nhất 1 ký tự.");
        }

        @Test
        void aLabelThatRepeatsAnEarlierOneIsReportedAtTheRepeatNamingTheFirst() {
            List<Finding> findings = rule.evaluate(withQuestions(dropdown("Font", "Serif", "Script", "Serif")), CONFIG);

            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.field()).isEqualTo("personalization.questions[0].options[2]");
            assertThat(finding.value()).isEqualTo("Serif");
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.message()).contains("trùng với lựa chọn ở vị trí 1");
        }

        @Test
        void twoDropdownsMayShareALabel() {
            assertThat(rule.evaluate(withQuestions(dropdown("A", "Red", "Blue"), dropdown("B", "Red", "Green")), CONFIG))
                    .isEmpty();
        }

        @Test
        void caseDecidesWhetherLabelsAreTheSameOnlyThroughTheRuleset() {
            assertThat(CONFIG.rule("personalization_dropdown_options").boolParam("case_insensitive")).isFalse();
            assertThat(rule.evaluate(withQuestions(dropdown("Font", "Serif", "serif")), CONFIG)).isEmpty();

            RulesetConfig ignoringCase = RulesetLoader.parse("""
                    marketplace: ETSY
                    version: t
                    checked_at: 2026-11-06
                    rules:
                      personalization_dropdown_options:
                        severity: ERROR
                        min_count: 1
                        max_count: 30
                        max_length: 20
                        case_insensitive: true
                        source:
                          status: unverified
                    """);
            assertThat(rule.evaluate(withQuestions(dropdown("Font", "Serif", "serif")), ignoringCase)).hasSize(1);
        }

        @Test
        void onlyDropdownQuestionsAreChecked() {
            List<String> tooMany = List.of(options(max + 5));
            PersonalizationQuestion labeledUpload = with("UPLOAD", "Photo", null, 2, tooMany);
            PersonalizationQuestion textWithOptions = with("TEXT", "Name", null, null, tooMany);

            assertThat(rule.evaluate(withQuestions(labeledUpload, textWithOptions), CONFIG)).isEmpty();
        }

        @Test
        void allTheNumbersAreReadFromTheRuleset() {
            RulesetConfig broken = RulesetLoader.parse("""
                    marketplace: ETSY
                    version: t
                    checked_at: 2026-11-06
                    rules:
                      personalization_dropdown_options:
                        severity: ERROR
                        min_count: 1
                        max_count: 30
                        max_length: 20
                        source:
                          status: unverified
                    """);

            assertThatThrownBy(() -> rule.validate(broken)).isInstanceOf(RulesetException.class)
                    .hasMessageContaining("case_insensitive");
        }
    }

    @Nested
    class UploadFiles {

        private final PersonalizationUploadFilesRule rule = new PersonalizationUploadFilesRule();
        private final int max = limit("personalization_upload_files", "max_files");
        private final int labeledMin = limit("personalization_upload_files", "labeled_min_files");

        @Test
        void anUploadUpToTheMaximumFilesIsValid() {
            assertThat(rule.evaluate(withQuestions(upload("Photo", max)), CONFIG)).isEmpty();
        }

        @Test
        void oneFileOverTheMaximumIsAnError() {
            List<Finding> findings = rule.evaluate(withQuestions(text("Name"), upload("Photo", max + 1)), CONFIG);

            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.ruleId()).isEqualTo("personalization_upload_files");
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.field()).isEqualTo("personalization.questions[1].max_files");
            assertThat(finding.value()).isEqualTo(String.valueOf(max + 1));
            assertThat(finding.message()).isEqualTo("Câu hỏi tải tệp lên (câu hỏi 'Photo') cho phép " + (max + 1)
                    + " tệp, tối đa " + max + ".");
            assertThat(finding.suggestion()).isEqualTo("Giảm số tệp xuống tối đa " + max + ".");
            assertThat(finding.fix()).isNull();
        }

        @Test
        void aLabeledUploadNeedsAtLeastTheLabeledMinimum() {
            String[] labels = {"Front", "Back"};

            assertThat(rule.evaluate(withQuestions(upload("Photo", labeledMin, labels)), CONFIG)).isEmpty();

            List<Finding> findings = rule.evaluate(withQuestions(upload("Photo", labeledMin - 1, labels)), CONFIG);
            assertThat(findings).hasSize(1);
            assertThat(findings.get(0).severity()).isEqualTo(Severity.ERROR);
            assertThat(findings.get(0).field()).isEqualTo("personalization.questions[0].max_files");
            assertThat(findings.get(0).value()).isEqualTo(String.valueOf(labeledMin - 1));
            assertThat(findings.get(0).message()).contains("có nhãn").contains("cần ít nhất " + labeledMin);
        }

        @Test
        void anUnlabeledUploadMayAskForASingleFile() {
            assertThat(rule.evaluate(withQuestions(upload("Photo", labeledMin - 1)), CONFIG)).isEmpty();
        }

        @Test
        void anUploadWithNoFileCountIsNotChecked() {
            assertThat(rule.evaluate(withQuestions(upload("Photo", null), upload("Photo B", null, "Front")), CONFIG)).isEmpty();
        }

        @Test
        void onlyUploadQuestionsAreChecked() {
            PersonalizationQuestion text = with("TEXT", "Name", null, max + 5, List.of());

            assertThat(rule.evaluate(withQuestions(text), CONFIG)).isEmpty();
        }

        @Test
        void aLabeledUploadOverTheMaximumIsReportedOnlyOnce() {
            assertThat(rule.evaluate(withQuestions(upload("Photo", max + 1, "Front", "Back")), CONFIG)).hasSize(1);
        }
    }

    @Nested
    class InstructionsLength {

        private final PersonalizationInstructionsLengthRule rule = new PersonalizationInstructionsLengthRule();
        private final int max = limit("personalization_instructions_length", "max_length");

        private PersonalizationQuestion textWith(String instructions) {
            return with("TEXT", "Name", instructions, null, List.of());
        }

        @Test
        void instructionsUpToTheMaximumAreValid() {
            assertThat(rule.evaluate(withQuestions(textWith("x".repeat(max))), CONFIG)).isEmpty();
        }

        @Test
        void noInstructionsIsNotAFinding() {
            assertThat(rule.evaluate(withQuestions(textWith(null)), CONFIG)).isEmpty();
            assertThat(rule.evaluate(valid(), CONFIG)).isEmpty();
        }

        @Test
        void instructionsOneOverTheMaximumAreAWarning() {
            String tooLong = "x".repeat(max + 1);

            List<Finding> findings = rule.evaluate(withQuestions(text("Date"), textWith(tooLong)), CONFIG);

            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.ruleId()).isEqualTo("personalization_instructions_length");
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.field()).isEqualTo("personalization.questions[1].instructions");
            assertThat(finding.value()).isEqualTo(tooLong);
            assertThat(finding.message()).isEqualTo("Phần hướng dẫn của câu hỏi 'Name' dài " + (max + 1) + " ký tự, tối đa "
                    + max + ".");
            assertThat(finding.suggestion()).startsWith("Rút ngắn phần hướng dẫn ít nhất 1 ký tự");
            assertThat(finding.fix()).isNull();
        }

        @Test
        void anEmojiCountsOnce() {
            String emoji = new String(Character.toChars(0x1F600));

            assertThat(rule.evaluate(withQuestions(textWith(emoji.repeat(max))), CONFIG)).isEmpty();
            assertThat(rule.evaluate(withQuestions(textWith(emoji.repeat(max + 1))), CONFIG)).hasSize(1);
        }

        @Test
        void everyTypeOfQuestionIsChecked() {
            String tooLong = "x".repeat(max + 1);

            List<Finding> findings = rule.evaluate(withQuestions(
                    with("DROPDOWN", "Font", tooLong, null, List.of("A")),
                    with("UPLOAD", "Photo", tooLong, 1, List.of())), CONFIG);

            assertThat(findings).extracting(Finding::field).containsExactly(
                    "personalization.questions[0].instructions", "personalization.questions[1].instructions");
        }

        @Test
        void theRulesetExplainsWhyItIsOnlyASecondaryWarning() {
            assertThat(CONFIG.rule("personalization_instructions_length").severity()).isEqualTo(Severity.WARNING);
            assertThat(CONFIG.rule("personalization_instructions_length").source().status()).isEqualTo(SourceStatus.SECONDARY);
            assertThat(CONFIG.rule("personalization_instructions_length").source().note())
                    .contains("256").contains("general launch").contains("supports_multiple_personalization_questions");
        }
    }

    @Nested
    class FreeTextSensitive {

        private final PersonalizationFreeTextSensitiveRule rule = new PersonalizationFreeTextSensitiveRule();
        private final List<String> keywords = CONFIG.rule("personalization_free_text_sensitive").stringListParam("keywords");

        @Test
        void aTextQuestionWithAKeywordIsAnInfoSuggestingADropdown() {
            List<Finding> findings = rule.evaluate(withQuestions(text("Player name")), CONFIG);

            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.ruleId()).isEqualTo("personalization_free_text_sensitive");
            assertThat(finding.severity()).isEqualTo(Severity.INFO);
            assertThat(finding.field()).isEqualTo("personalization.questions[0].question_text");
            assertThat(finding.value()).isEqualTo("Player name");
            assertThat(finding.message()).contains("'Player name'").contains("'name'").contains("'player'");
            assertThat(finding.suggestion()).contains("dropdown").contains("không phải kết luận về nhãn hiệu");
            assertThat(finding.fix()).as("no rule proposes replacement words").isNull();
        }

        @Test
        void everyConfiguredKeywordIsFoundInATextQuestion() {
            for (String keyword : keywords) {
                assertThat(rule.evaluate(withQuestions(text("Your " + keyword.toUpperCase() + " here")), CONFIG))
                        .as("keyword '%s'", keyword).hasSize(1);
            }
        }

        @Test
        void aKeywordIsAWholeWord() {
            assertThat(rule.evaluate(withQuestions(text("Teammate"), text("Numbered"), text("Playlist")), CONFIG)).isEmpty();
        }

        @Test
        void onlyFreeTextQuestionsAreChecked() {
            assertThat(rule.evaluate(withQuestions(dropdown("Team", "Red", "Blue"), upload("Player photo", 1)), CONFIG))
                    .isEmpty();
        }

        @Test
        void aTextQuestionWithoutAKeywordOrWithoutTextHasNoFinding() {
            assertThat(rule.evaluate(withQuestions(text("Message"), text(null)), CONFIG)).isEmpty();
            assertThat(rule.evaluate(valid(), CONFIG)).isEmpty();
        }

        @Test
        void oneFindingPerQuestionNoMatterHowManyKeywords() {
            assertThat(rule.evaluate(withQuestions(text("Team name and player number")), CONFIG)).hasSize(1);
        }

        @Test
        void itCanNeverBeMoreThanInfo() {
            assertThatThrownBy(() -> rule.validate(config("WARNING", "[name]"))).isInstanceOf(RulesetException.class)
                    .hasMessageContaining("INFO");
            assertThatThrownBy(() -> rule.validate(config("ERROR", "[name]"))).isInstanceOf(RulesetException.class);
            assertThat(CONFIG.rule("personalization_free_text_sensitive").severity()).isEqualTo(Severity.INFO);
            assertThat(CONFIG.rule("personalization_free_text_sensitive").source().status()).isEqualTo(SourceStatus.UNVERIFIED);
        }

        @Test
        void theKeywordsComeFromTheRulesetAndAreValidated() {
            RulesetConfig custom = config("INFO", "[size]");

            assertThat(rule.evaluate(withQuestions(text("Player name"), text("Size")), custom))
                    .extracting(Finding::value).containsExactly("Size");
            assertThatThrownBy(() -> rule.validate(config("INFO", "[]"))).isInstanceOf(RulesetException.class)
                    .hasMessageContaining("keywords");
            assertThatThrownBy(() -> rule.validate(config("INFO", "['  ']"))).isInstanceOf(RulesetException.class);
        }

        private RulesetConfig config(String severity, String keywordsYaml) {
            return RulesetLoader.parse("""
                    marketplace: ETSY
                    version: t
                    checked_at: 2026-11-06
                    rules:
                      personalization_free_text_sensitive:
                        severity: %s
                        keywords: %s
                        source:
                          status: unverified
                    """.formatted(severity, keywordsYaml));
        }
    }

    @Test
    void anEtsyListingWithAGoodPersonalizationBlockHasNoPersonalizationFinding() {
        List<PersonalizationQuestion> good = new ArrayList<>(List.of(
                with("TEXT", "Gift message", "Up to 20 letters", null, List.of()),
                dropdown("Font", "Serif", "Script"),
                upload("Photo", 2, "Front", "Back")));

        var listing = withQuestions(good.toArray(PersonalizationQuestion[]::new));

        assertThat(new PersonalizationQuestionCountRule().evaluate(listing, CONFIG)).isEmpty();
        assertThat(new PersonalizationQuestionTextRule().evaluate(listing, CONFIG)).isEmpty();
        assertThat(new PersonalizationDropdownOptionsRule().evaluate(listing, CONFIG)).isEmpty();
        assertThat(new PersonalizationUploadFilesRule().evaluate(listing, CONFIG)).isEmpty();
        assertThat(new PersonalizationInstructionsLengthRule().evaluate(listing, CONFIG)).isEmpty();
        assertThat(new PersonalizationFreeTextSensitiveRule().evaluate(listing, CONFIG)).isEmpty();
    }
}
