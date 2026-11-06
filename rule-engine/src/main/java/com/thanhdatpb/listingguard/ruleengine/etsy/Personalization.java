package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.listing.PersonalizationQuestion;

/**
 * Field paths and the way a question is named in a message, shared by the personalization rules. A path starts at the
 * {@code personalization} block of the input schema and counts questions and options in the cleaned lists, from zero, like
 * {@code tags[i]}.
 */
final class Personalization {

    /** The list of questions as a whole. */
    static final String QUESTIONS = "personalization.questions";

    private Personalization() {
    }

    /** {@code personalization.questions[i]}. */
    static String question(int index) {
        return QUESTIONS + "[" + index + "]";
    }

    /** {@code personalization.questions[i].name}, for example {@code question_text} or {@code options}. */
    static String field(int index, String name) {
        return question(index) + "." + name;
    }

    /** {@code personalization.questions[i].options[j]}. */
    static String option(int index, int option) {
        return field(index, "options") + "[" + option + "]";
    }

    /** How a message names a question: by its text, or by its position when it has none. */
    static String describe(int index, PersonalizationQuestion question) {
        return question.questionText() == null
                ? "câu hỏi thứ " + (index + 1)
                : "câu hỏi " + Texts.quote(question.questionText());
    }
}
