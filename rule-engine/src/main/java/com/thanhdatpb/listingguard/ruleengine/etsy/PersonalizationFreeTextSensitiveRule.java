package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.core.listing.PersonalizationQuestion;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.SensitiveKeywords;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A reminder for a free text question ({@code TEXT}) whose text contains one of the configured keywords (name, number, team,
 * player): a buyer can type a team or a player that is a trademark. It suggests a dropdown whose choices the seller sets. It
 * is a hint and no verdict on trademarks (the trademark check is separate), it has no fix, and it can never be more than
 * INFO: the rule refuses to start when the ruleset rates it higher. A keyword matches ignoring case, as a whole word.
 */
public final class PersonalizationFreeTextSensitiveRule implements Rule {

    public static final String ID = "personalization_free_text_sensitive";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        SensitiveKeywords.validate(ID, config.rule(ID));
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        List<String> keywords = config.rule(ID).stringListParam("keywords");
        List<PersonalizationQuestion> questions = listing.personalizationQuestions();
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            PersonalizationQuestion question = questions.get(i);
            if (!PersonalizationQuestion.TEXT.equals(question.type()) || question.questionText() == null) {
                continue;
            }
            List<String> found = SensitiveKeywords.found(question.questionText(), keywords);
            if (!found.isEmpty()) {
                findings.add(finding(config, Personalization.field(i, "question_text"), question.questionText(),
                        "Câu hỏi nhập tự do " + Texts.quote(question.questionText()) + " có từ "
                                + found.stream().map(Texts::quote).collect(Collectors.joining(", "))
                                + ": khách có thể nhập tên đội hoặc cầu thủ có nhãn hiệu.",
                        "Cân nhắc dùng câu hỏi dạng dropdown với các lựa chọn do bạn đặt, để hạn chế khách nhập tên đội hoặc cầu "
                                + "thủ có nhãn hiệu. Đây chỉ là gợi ý, không phải kết luận về nhãn hiệu; kết quả kiểm tra nhãn "
                                + "hiệu nằm ở mục riêng."));
            }
        }
        return findings;
    }
}
