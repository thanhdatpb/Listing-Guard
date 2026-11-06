package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.PersonalizationQuestion;
import com.thanhdatpb.listingguard.ruleengine.Limit;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The options of a dropdown question: between {@code min_count} and {@code max_count} of them, each at most
 * {@code max_length} characters (code points), and no label twice in the same question. Two labels are the same when they
 * are equal, ignoring case only if {@code case_insensitive} is set. The labels of an upload question are not checked here.
 * A blank option is dropped by the normalizer, so it can never be reported as too short. There is no fix: which option to drop
 * or rename is the seller's choice.
 */
public final class PersonalizationDropdownOptionsRule implements Rule {

    public static final String ID = "personalization_dropdown_options";

    private static final String FIELD = Personalization.QUESTIONS + "[type=" + PersonalizationQuestion.DROPDOWN + "].options";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        RuleSettings settings = config.rule(ID);
        settings.intParam("min_count");
        settings.intParam("max_count");
        settings.intParam("max_length");
        settings.boolParam("case_insensitive");
    }

    /** The options of a dropdown as items of the cleaned list, and the length of each label in code points. */
    @Override
    public List<Limit> limits(RuleSettings settings) {
        return List.of(Limit.maxCount(FIELD, settings.intParam("max_count")),
                Limit.maxLength(FIELD, settings.intParam("max_length")));
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        RuleSettings settings = config.rule(ID);
        int min = settings.intParam("min_count");
        int max = settings.intParam("max_count");
        int maxLength = settings.intParam("max_length");
        boolean ignoreCase = settings.boolParam("case_insensitive");
        List<PersonalizationQuestion> questions = listing.personalizationQuestions();
        List<Finding> findings = new ArrayList<>();

        for (int i = 0; i < questions.size(); i++) {
            PersonalizationQuestion question = questions.get(i);
            if (!PersonalizationQuestion.DROPDOWN.equals(question.type())) {
                continue;
            }
            String name = Personalization.describe(i, question);
            List<String> options = question.options();
            if (options.size() < min) {
                findings.add(finding(config, Personalization.field(i, "options"),
                        "Dropdown " + name + " có " + options.size() + " lựa chọn, cần ít nhất " + min + ".",
                        "Thêm lựa chọn cho khách hàng."));
            } else if (options.size() > max) {
                findings.add(finding(config, Personalization.field(i, "options"),
                        "Dropdown " + name + " có " + options.size() + " lựa chọn, tối đa " + max + ".",
                        "Bỏ bớt " + (options.size() - max) + " lựa chọn."));
            }
            Map<String, Integer> firstSeenAt = new HashMap<>();
            for (int j = 0; j < options.size(); j++) {
                String option = options.get(j);
                int length = Texts.length(option);
                if (length > maxLength) {
                    findings.add(finding(config, Personalization.option(i, j), option,
                            "Lựa chọn " + Texts.quote(option) + " của dropdown " + name + " dài " + length + " ký tự, tối đa "
                                    + maxLength + ".",
                            "Rút ngắn lựa chọn này ít nhất " + (length - maxLength) + " ký tự."));
                }
                Integer first = firstSeenAt.putIfAbsent(ignoreCase ? option.toLowerCase(Locale.ROOT) : option, j);
                if (first != null) {
                    findings.add(finding(config, Personalization.option(i, j), option,
                            "Lựa chọn " + Texts.quote(option) + " của dropdown " + name + " trùng với lựa chọn ở vị trí "
                                    + (first + 1) + ", các lựa chọn của một câu hỏi phải khác nhau.",
                            "Xoá lựa chọn trùng hoặc đổi nhãn để các lựa chọn khác nhau."));
                }
            }
        }
        return findings;
    }
}
