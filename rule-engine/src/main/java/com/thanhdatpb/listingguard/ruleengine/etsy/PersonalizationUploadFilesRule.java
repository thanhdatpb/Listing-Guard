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
 * An upload question accepts at most {@code max_files} files, and a labeled one (an upload question with options, one label per
 * file) at least {@code labeled_min_files}. A question with no {@code max_files} is not checked here. There is no fix: how many
 * files to ask for is the seller's choice.
 */
public final class PersonalizationUploadFilesRule implements Rule {

    public static final String ID = "personalization_upload_files";

    private static final String FIELD = Personalization.QUESTIONS + "[type=" + PersonalizationQuestion.UPLOAD + "].max_files";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        RuleSettings settings = config.rule(ID);
        settings.intParam("max_files");
        settings.intParam("labeled_min_files");
    }

    /** The number of files an upload question accepts, counted in files ({@link Limit.Unit#ITEMS}). */
    @Override
    public List<Limit> limits(RuleSettings settings) {
        return List.of(Limit.maxCount(FIELD, settings.intParam("max_files")));
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        RuleSettings settings = config.rule(ID);
        int max = settings.intParam("max_files");
        int labeledMin = settings.intParam("labeled_min_files");
        List<PersonalizationQuestion> questions = listing.personalizationQuestions();
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            PersonalizationQuestion question = questions.get(i);
            Integer files = question.maxFiles();
            if (!PersonalizationQuestion.UPLOAD.equals(question.type()) || files == null) {
                continue;
            }
            String name = Personalization.describe(i, question);
            String field = Personalization.field(i, "max_files");
            if (files > max) {
                findings.add(finding(config, field, String.valueOf(files),
                        "Câu hỏi tải tệp lên (" + name + ") cho phép " + files + " tệp, tối đa " + max + ".",
                        "Giảm số tệp xuống tối đa " + max + "."));
            } else if (!question.options().isEmpty() && files < labeledMin) {
                findings.add(finding(config, field, String.valueOf(files),
                        "Câu hỏi tải tệp lên có nhãn (" + name + ") cho phép " + files + " tệp, cần ít nhất " + labeledMin + ".",
                        "Tăng số tệp lên ít nhất " + labeledMin + ", hoặc bỏ các nhãn để dùng câu hỏi tải tệp lên không có nhãn."));
            }
        }
        return findings;
    }
}
