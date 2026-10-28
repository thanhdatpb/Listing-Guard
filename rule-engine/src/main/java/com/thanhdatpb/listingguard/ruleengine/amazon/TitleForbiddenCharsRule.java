package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The configured characters must not appear in the title, unless the brand contains them (the characters are
 * allowed when they are part of the brand name). One finding per distinct character, in order of appearance.
 */
public final class TitleForbiddenCharsRule extends AmazonRule {

    public static final String ID = "title_forbidden_chars";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        for (String character : settings.stringListParam("chars")) {
            if (character.codePointCount(0, character.length()) != 1) {
                throw new RulesetException("Rule '" + ID + "': every entry of 'chars' must be one character, got '" + character + "'");
            }
        }
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        if (listing.title() == null) {
            return List.of();
        }
        Set<String> forbidden = new LinkedHashSet<>(settings.stringListParam("chars"));
        String brand = listing.brand() == null ? "" : listing.brand();

        Set<String> seen = new LinkedHashSet<>();
        listing.title().codePoints().forEach(codePoint -> {
            String character = new String(Character.toChars(codePoint));
            if (forbidden.contains(character) && !brand.contains(character)) {
                seen.add(character);
            }
        });

        List<Finding> findings = new ArrayList<>();
        for (String character : seen) {
            findings.add(finding(config, settings, "title", character,
                    "Tiêu đề chứa ký tự không được phép: '" + character + "'.",
                    "Xoá ký tự '" + character + "' khỏi tiêu đề (chỉ được giữ khi nó là một phần của tên brand)."));
        }
        return findings;
    }
}
