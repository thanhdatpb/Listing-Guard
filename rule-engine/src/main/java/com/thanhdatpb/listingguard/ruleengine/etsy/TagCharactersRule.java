package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Tags may only contain characters matching the configured character class. */
public final class TagCharactersRule implements Rule {

    public static final String ID = "tag_characters";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        allowed(config);
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        Pattern allowed = allowed(config);
        List<Finding> findings = new ArrayList<>();
        List<String> tags = listing.tags();
        for (int i = 0; i < tags.size(); i++) {
            Set<String> invalid = invalidCharacters(tags.get(i), allowed);
            if (!invalid.isEmpty()) {
                findings.add(finding(config, "tags[" + i + "]",
                        "Tag " + Texts.quote(tags.get(i)) + " chứa ký tự không hợp lệ: " + String.join(" ", invalid) + ".",
                        "Xoá hoặc thay các ký tự không hợp lệ trong tag này."));
            }
        }
        return findings;
    }

    private static Set<String> invalidCharacters(String tag, Pattern allowed) {
        Set<String> invalid = new LinkedHashSet<>();
        tag.codePoints().forEach(codePoint -> {
            String character = new String(Character.toChars(codePoint));
            if (!allowed.matcher(character).matches()) {
                invalid.add("'" + character + "'");
            }
        });
        return invalid;
    }

    private static Pattern allowed(RulesetConfig config) {
        String charClass = config.rule(ID).stringParam("allowed_char_class");
        try {
            return Pattern.compile(charClass);
        } catch (PatternSyntaxException e) {
            throw new RulesetException("Rule '" + ID + "': allowed_char_class is not a valid regex: " + e.getDescription());
        }
    }
}
