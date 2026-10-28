package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Content that must not appear in the listed fields (for example web addresses and email addresses), found by the
 * configured {@code patterns} (name to regex). One finding per field and pattern, for the first match. The
 * {@code fields} are {@code title}, {@code description} and {@code bullet_points} (each bullet separately).
 */
public final class ProhibitedContentRule extends AmazonRule {

    public static final String ID = "prohibited_content";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        for (String field : settings.stringListParam("fields")) {
            if (!List.of("title", "description", "bullet_points").contains(field)) {
                throw new RulesetException("Rule '" + ID + "': unknown field '" + field + "'");
            }
        }
        patterns(settings);
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        Map<String, Pattern> patterns = patterns(settings);
        Map<String, String> texts = new LinkedHashMap<>();
        for (String field : settings.stringListParam("fields")) {
            switch (field) {
                case "title" -> addIfPresent(texts, "title", listing.title());
                case "description" -> addIfPresent(texts, "description", listing.description());
                default -> {
                    for (int i = 0; i < listing.bulletPoints().size(); i++) {
                        addIfPresent(texts, "bullet_points[" + i + "]", listing.bulletPoints().get(i));
                    }
                }
            }
        }

        List<Finding> findings = new ArrayList<>();
        texts.forEach((path, text) -> patterns.forEach((kind, pattern) -> {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                findings.add(finding(config, settings, path, matcher.group(),
                        "Phát hiện " + kind + " trong " + path + ": " + Texts.quote(matcher.group()) + ".",
                        "Xoá " + kind + " khỏi nội dung listing; không đưa liên kết ngoài hay thông tin liên hệ vào tiêu đề, bullet point hoặc mô tả."));
            }
        }));
        return findings;
    }

    private static void addIfPresent(Map<String, String> texts, String path, String text) {
        if (text != null) {
            texts.put(path, text);
        }
    }

    private static Map<String, Pattern> patterns(RuleSettings settings) {
        Map<String, Pattern> compiled = new LinkedHashMap<>();
        settings.stringMapParam("patterns").forEach((kind, regex) -> {
            try {
                compiled.put(kind, Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                throw new RulesetException("Rule '" + ID + "': pattern '" + kind + "' is not a valid regex: " + e.getDescription());
            }
        });
        return compiled;
    }
}
