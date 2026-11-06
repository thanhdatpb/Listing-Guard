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
 * The instructions of a question must not exceed {@code max_length} characters (code points). A question with no
 * instructions is not a finding: they are optional. Etsy applies this limit only after its general launch and to apps that opt
 * in, so the ruleset rates it a WARNING with a secondary source (see its note). There is no fix: the words are the seller's.
 */
public final class PersonalizationInstructionsLengthRule implements Rule {

    public static final String ID = "personalization_instructions_length";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        config.rule(ID).intParam("max_length");
    }

    /** The instructions of each question, measured in code points ({@link Texts#length}). */
    @Override
    public List<Limit> limits(RuleSettings settings) {
        return List.of(Limit.maxLength(Personalization.QUESTIONS + "[].instructions", settings.intParam("max_length")));
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        int max = config.rule(ID).intParam("max_length");
        List<PersonalizationQuestion> questions = listing.personalizationQuestions();
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            PersonalizationQuestion question = questions.get(i);
            String instructions = question.instructions();
            if (instructions == null) {
                continue;
            }
            int length = Texts.length(instructions);
            if (length > max) {
                findings.add(finding(config, Personalization.field(i, "instructions"), instructions,
                        "Phần hướng dẫn của " + Personalization.describe(i, question) + " dài " + length + " ký tự, tối đa "
                                + max + ".",
                        "Rút ngắn phần hướng dẫn ít nhất " + (length - max) + " ký tự để không bị từ chối khi Etsy áp dụng "
                                + "giới hạn này."));
            }
        }
        return findings;
    }
}
