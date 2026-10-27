package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Allowed-character checks shared by the rules that restrict which characters a field may contain. */
final class Characters {

    private Characters() {
    }

    /** The rule's {@code allowed_char_class}: a regex character class describing one allowed character. */
    static Pattern allowedClass(RulesetConfig config, String ruleId) {
        String charClass = config.rule(ruleId).stringParam("allowed_char_class");
        try {
            return Pattern.compile(charClass);
        } catch (PatternSyntaxException e) {
            throw new RulesetException("Rule '" + ruleId + "': allowed_char_class is not a valid regex: " + e.getDescription());
        }
    }

    /** Distinct characters of {@code text} not matching {@code allowed}, quoted, in first-seen order. */
    static Set<String> invalid(String text, Pattern allowed) {
        Set<String> invalid = new LinkedHashSet<>();
        text.codePoints().forEach(codePoint -> {
            String character = new String(Character.toChars(codePoint));
            if (!allowed.matcher(character).matches()) {
                invalid.add("'" + character + "'");
            }
        });
        return invalid;
    }
}
