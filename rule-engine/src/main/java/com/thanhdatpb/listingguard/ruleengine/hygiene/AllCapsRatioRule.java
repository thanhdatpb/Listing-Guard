package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A text written mostly in capitals. Words of at least {@code min_word_letters} letters that have case are counted; a word whose letters are all
 * capitals is a capital word unless it is in {@code exempt_words} (an acronym). A text is reported when it has at least {@code min_words} counted
 * words and {@code caps_share_from} or more of them are capital words. A short brand in capitals next to ordinary words ({@code BMW Racing Mug}) stays
 * under the ratio, and a text with fewer counted words than {@code min_words} is never judged. The span runs from the first to the last capital
 * word. No fix: re-casing a title changes brand names.
 */
public final class AllCapsRatioRule extends TextRule {

    public static final String ID = "all_caps_ratio";

    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}'’]*");

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.intParam("min_word_letters");
        settings.intParam("min_words");
        settings.decimalParam("caps_share_from");
        settings.stringListParam("exempt_words");
    }

    @Override
    List<Hit> scan(String field, String text, RuleSettings settings) {
        int minLetters = settings.intParam("min_word_letters");
        int minWords = settings.intParam("min_words");
        BigDecimal maxRatio = settings.decimalParam("caps_share_from");
        Set<String> exempt = new java.util.HashSet<>();
        settings.stringListParam("exempt_words").forEach(word -> exempt.add(word.toLowerCase(Locale.ROOT)));
        int counted = 0;
        int caps = 0;
        int first = -1;
        int last = -1;
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            String word = matcher.group();
            int letters = 0;
            boolean cased = false;
            boolean allUpper = true;
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                if (!Character.isLetter(c)) {
                    continue;
                }
                letters++;
                if (Character.isUpperCase(c) || Character.isLowerCase(c)) {
                    cased = true;
                    allUpper &= Character.isUpperCase(c);
                }
            }
            if (letters < minLetters || !cased) {
                continue;
            }
            counted++;
            if (allUpper && !exempt.contains(word.toLowerCase(Locale.ROOT))) {
                caps++;
                if (first < 0) {
                    first = matcher.start();
                }
                last = matcher.end();
            }
        }
        if (counted < minWords || caps == 0 || BigDecimal.valueOf(caps).compareTo(maxRatio.multiply(BigDecimal.valueOf(counted))) < 0) {
            return List.of();
        }
        return List.of(new Hit(text.substring(first, last), first, last, caps + " trên " + counted + " từ trong " + field
                + " viết hoa toàn bộ.", "Viết hoa chữ cái đầu của từng từ thay vì cả từ; giữ nguyên chữ viết tắt và tên thương hiệu."));
    }
}
