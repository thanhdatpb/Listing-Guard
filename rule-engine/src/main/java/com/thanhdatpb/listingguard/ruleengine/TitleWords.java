package com.thanhdatpb.listingguard.ruleengine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the title rules of every marketplace split a title into words, so that Amazon and Etsy count them the same way. A word is
 * a run of letters and digits; an apostrophe inside it keeps it together ("men's" is one word), any other punctuation separates
 * words. The word lists and the numbers belong to the ruleset, not to this class (ADR-001).
 */
public final class TitleWords {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{Nd}]+(?:['’][\\p{L}\\p{Nd}]+)*");

    private TitleWords() {
    }

    /** A word that appears more often than allowed: its first form as written, and how many times it appears. */
    public record Repeat(String word, int count) {
    }

    /** The number of words of {@code text}; every word counts, including articles and prepositions. */
    public static int count(String text) {
        int words = 0;
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            words++;
        }
        return words;
    }

    /**
     * The words that appear more than {@code max} times, ignoring case, in order of first appearance. A word of
     * {@code exemptWords} (compared ignoring case) is not counted at all.
     */
    public static List<Repeat> repeated(String text, int max, Collection<String> exemptWords) {
        Set<String> exempt = new HashSet<>();
        exemptWords.forEach(word -> exempt.add(word.toLowerCase(Locale.ROOT)));

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> firstForm = new LinkedHashMap<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            String word = matcher.group();
            String key = word.toLowerCase(Locale.ROOT);
            if (exempt.contains(key)) {
                continue;
            }
            counts.merge(key, 1, Integer::sum);
            firstForm.putIfAbsent(key, word);
        }

        List<Repeat> repeats = new ArrayList<>();
        counts.forEach((key, count) -> {
            if (count > max) {
                repeats.add(new Repeat(firstForm.get(key), count));
            }
        });
        return repeats;
    }
}
