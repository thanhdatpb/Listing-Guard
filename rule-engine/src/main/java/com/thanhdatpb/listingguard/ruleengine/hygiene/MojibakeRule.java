package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text that was read in the wrong character set ({@code CafÃ©}, {@code donâ€™t}, a replacement character). The expressions are the
 * ruleset's; each is case-sensitive, because the damage is in the exact bytes. The first match of a field is reported, with how many there
 * are. No fix: the original character is a guess.
 */
public final class MojibakeRule extends TextRule {

    public static final String ID = "mojibake";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        patterns(settings);
    }

    private static List<Pattern> patterns(RuleSettings settings) {
        return settings.stringListParam("patterns").stream().map(regex -> Regexes.compile(ID, regex, false)).toList();
    }

    @Override
    List<Hit> scan(String field, String text, RuleSettings settings) {
        int count = 0;
        int start = -1;
        int end = -1;
        for (Pattern pattern : patterns(settings)) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                count++;
                if (start < 0 || matcher.start() < start) {
                    start = matcher.start();
                    end = matcher.end();
                }
            }
        }
        if (count == 0 || end <= start) {
            return List.of();
        }
        String value = text.substring(start, end);
        List<Hit> hits = new ArrayList<>();
        hits.add(new Hit(value, start, end, "Có vẻ lỗi mã hoá ký tự (mojibake) trong " + field + ": " + quote(value)
                + (count > 1 ? " và " + (count - 1) + " chỗ nữa" : "") + ".",
                "Gõ lại đúng ký tự ở chỗ này, hoặc lưu file nguồn bằng UTF-8 rồi nhập lại."));
        return hits;
    }
}
