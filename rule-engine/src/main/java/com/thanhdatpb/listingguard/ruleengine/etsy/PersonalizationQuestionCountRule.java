package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.PersonalizationQuestion;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * A listing may have at most {@code max_count} personalization questions, and at most {@code max_upload_questions} of them
 * may be upload questions. The extra upload questions are reported one by one, each at its own position. There is no fix:
 * which question to drop is the seller's choice.
 */
public final class PersonalizationQuestionCountRule implements Rule {

    public static final String ID = "personalization_question_count";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        RuleSettings settings = config.rule(ID);
        settings.intParam("max_count");
        settings.intParam("max_upload_questions");
    }

    /** The questions as items of the cleaned list, and the upload questions among them. */
    @Override
    public List<Limit> limits(RuleSettings settings) {
        return List.of(
                Limit.maxCount(Personalization.QUESTIONS, settings.intParam("max_count")),
                Limit.maxCount(Personalization.QUESTIONS + "[type=" + PersonalizationQuestion.UPLOAD + "]",
                        settings.intParam("max_upload_questions")));
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        RuleSettings settings = config.rule(ID);
        int max = settings.intParam("max_count");
        int maxUploads = settings.intParam("max_upload_questions");
        List<PersonalizationQuestion> questions = listing.personalizationQuestions();
        List<Finding> findings = new ArrayList<>();

        if (questions.size() > max) {
            findings.add(finding(config, Personalization.QUESTIONS,
                    "Listing có " + questions.size() + " câu hỏi cá nhân hóa, tối đa " + max + ".",
                    "Bỏ bớt " + (questions.size() - max) + " câu hỏi cá nhân hóa."));
        }
        int uploads = 0;
        for (int i = 0; i < questions.size(); i++) {
            PersonalizationQuestion question = questions.get(i);
            if (!PersonalizationQuestion.UPLOAD.equals(question.type())) {
                continue;
            }
            uploads++;
            if (uploads > maxUploads) {
                findings.add(finding(config, Personalization.question(i), question.questionText(),
                        "Câu hỏi tải tệp lên thứ " + uploads + " (" + Personalization.describe(i, question) + "): mỗi listing chỉ "
                                + "được tối đa " + maxUploads + " câu hỏi tải tệp lên.",
                        "Bỏ câu hỏi tải tệp lên này, hoặc gộp ý của nó vào câu hỏi tải tệp lên còn lại."));
            }
        }
        return findings;
    }
}
