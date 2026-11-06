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
 * The text of each question must be between {@code min_length} and {@code max_length} characters (code points). A question
 * with no text is below the minimum: blank text is absent after normalization. There is no fix: the words of a question are
 * the seller's.
 */
public final class PersonalizationQuestionTextRule implements Rule {

    public static final String ID = "personalization_question_text";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        RuleSettings settings = config.rule(ID);
        settings.intParam("min_length");
        settings.intParam("max_length");
    }

    /** The text of each question, measured in code points ({@link Texts#length}). */
    @Override
    public List<Limit> limits(RuleSettings settings) {
        return List.of(Limit.maxLength(Personalization.QUESTIONS + "[].question_text", settings.intParam("max_length")));
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        RuleSettings settings = config.rule(ID);
        int min = settings.intParam("min_length");
        int max = settings.intParam("max_length");
        List<PersonalizationQuestion> questions = listing.personalizationQuestions();
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            PersonalizationQuestion question = questions.get(i);
            String text = question.questionText();
            int length = text == null ? 0 : Texts.length(text);
            String field = Personalization.field(i, "question_text");
            if (length < min) {
                findings.add(finding(config, field,
                        "Câu hỏi cá nhân hóa thứ " + (i + 1) + " chưa có nội dung, cần ít nhất " + min + " ký tự.",
                        "Nhập nội dung câu hỏi cho khách hàng."));
            } else if (length > max) {
                findings.add(finding(config, field, text,
                        "Câu hỏi " + Texts.quote(text) + " dài " + length + " ký tự, tối đa " + max + ".",
                        "Rút ngắn câu hỏi ít nhất " + (length - max) + " ký tự."));
            }
        }
        return findings;
    }
}
