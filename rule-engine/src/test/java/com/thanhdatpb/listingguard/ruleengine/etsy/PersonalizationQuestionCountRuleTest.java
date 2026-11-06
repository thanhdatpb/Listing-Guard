package com.thanhdatpb.listingguard.ruleengine.etsy;

import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.CONFIG;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.limit;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.upload;
import static com.thanhdatpb.listingguard.ruleengine.etsy.EtsyTestData.valid;
import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.PersonalizationQuestion;
import com.thanhdatpb.listingguard.ruleengine.config.SourceStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersonalizationQuestionCountRuleTest {

    private final PersonalizationQuestionCountRule rule = new PersonalizationQuestionCountRule();
    private final int max = limit("personalization_question_count", "max_count");
    private final int maxUploads = limit("personalization_question_count", "max_upload_questions");

    private static Listing withQuestions(List<PersonalizationQuestion> questions) {
        Listing base = valid();
        return new Listing(base.title(), base.description(), base.tags(), base.price(), base.category(), base.materials(),
                base.images(), base.quantity(), base.whoMade(), base.whenMade(), null, List.of(), null, null, null, questions);
    }

    private static List<PersonalizationQuestion> texts(int count) {
        List<PersonalizationQuestion> questions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            questions.add(EtsyTestData.text("Question " + i));
        }
        return questions;
    }

    private List<Finding> run(List<PersonalizationQuestion> questions) {
        return rule.evaluate(withQuestions(questions), CONFIG);
    }

    @Test
    void aListingWithoutPersonalizationHasNoFinding() {
        assertThat(rule.evaluate(valid(), CONFIG)).isEmpty();
    }

    @Test
    void exactlyTheMaximumNumberOfQuestionsIsValid() {
        assertThat(run(texts(max))).isEmpty();
    }

    @Test
    void oneQuestionOverTheMaximumIsAnErrorOnTheList() {
        List<Finding> findings = run(texts(max + 1));

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("personalization_question_count");
        assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding.field()).isEqualTo("personalization.questions");
        assertThat(finding.value()).isNull();
        assertThat(finding.message()).isEqualTo("Listing có " + (max + 1) + " câu hỏi cá nhân hóa, tối đa " + max + ".");
        assertThat(finding.suggestion()).isEqualTo("Bỏ bớt 1 câu hỏi cá nhân hóa.");
        assertThat(finding.fix()).isNull();
    }

    @Test
    void uploadQuestionsUpToTheMaximumAreValid() {
        List<PersonalizationQuestion> questions = new ArrayList<>();
        for (int i = 0; i < maxUploads; i++) {
            questions.add(upload("Photo " + i, 1));
        }

        assertThat(run(questions)).isEmpty();
    }

    @Test
    void everyUploadQuestionOverTheMaximumIsReportedAtItsOwnPosition() {
        List<PersonalizationQuestion> questions = new ArrayList<>();
        questions.add(EtsyTestData.text("Name"));
        for (int i = 0; i < maxUploads + 2; i++) {
            questions.add(upload("Photo " + i, 1));
        }

        List<Finding> findings = run(questions);

        assertThat(findings).hasSize(2);
        assertThat(findings).extracting(Finding::field).containsExactly(
                "personalization.questions[" + (maxUploads + 1) + "]", "personalization.questions[" + (maxUploads + 2) + "]");
        assertThat(findings).extracting(Finding::value).containsExactly("Photo " + maxUploads, "Photo " + (maxUploads + 1));
        assertThat(findings.get(0).severity()).isEqualTo(Severity.ERROR);
        assertThat(findings.get(0).message()).contains("thứ " + (maxUploads + 1)).contains("tối đa " + maxUploads);
    }

    @Test
    void aLabeledUploadCountsAsAnUploadToo() {
        List<PersonalizationQuestion> questions = new ArrayList<>();
        for (int i = 0; i < maxUploads; i++) {
            questions.add(upload("Photo " + i, 2, "Front", "Back"));
        }
        questions.add(upload("Extra", 1));

        assertThat(run(questions)).extracting(Finding::value).containsExactly("Extra");
    }

    @Test
    void anUploadWithoutTextIsNamedByItsPosition() {
        List<PersonalizationQuestion> questions = new ArrayList<>();
        for (int i = 0; i <= maxUploads; i++) {
            questions.add(upload(null, 1));
        }

        List<Finding> findings = run(questions);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).value()).isNull();
        assertThat(findings.get(0).message()).contains("câu hỏi thứ " + (maxUploads + 1));
    }

    @Test
    void bothFindingsComeTogetherWhenBothLimitsAreBroken() {
        List<PersonalizationQuestion> questions = new ArrayList<>(texts(max));
        for (int i = 0; i <= maxUploads; i++) {
            questions.add(upload("Photo " + i, 1));
        }

        assertThat(run(questions)).extracting(Finding::field)
                .contains("personalization.questions", "personalization.questions[" + (max + maxUploads) + "]");
    }

    @Test
    void theRulesetRatesItAnErrorWithAVerifiedSource() {
        assertThat(CONFIG.rule("personalization_question_count").severity()).isEqualTo(Severity.ERROR);
        assertThat(CONFIG.rule("personalization_question_count").source().status()).isEqualTo(SourceStatus.VERIFIED);
    }
}
