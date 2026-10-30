package com.thanhdatpb.listingguard.trademark.catalog;

import java.util.HashSet;
import java.util.Set;

/**
 * Trigram similarity the way PostgreSQL {@code pg_trgm} defines it: each word is padded with two spaces in front and
 * one behind, its three-character windows are the trigrams, and similarity is shared trigrams divided by all
 * distinct trigrams. Used by {@link InMemoryTrademarkCatalog} so a test catalog returns the candidates the database
 * would.
 */
public final class Trigrams {

    private Trigrams() {
    }

    public static Set<String> of(String text) {
        Set<String> result = new HashSet<>();
        for (String word : text.split("[^\\p{L}\\p{Nd}]+")) {
            if (word.isEmpty()) {
                continue;
            }
            String padded = "  " + word + " ";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                result.add(padded.substring(i, i + 3));
            }
        }
        return result;
    }

    public static double similarity(String a, String b) {
        Set<String> left = of(a);
        Set<String> right = of(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int shared = 0;
        for (String trigram : left) {
            if (right.contains(trigram)) {
                shared++;
            }
        }
        return (double) shared / (left.size() + right.size() - shared);
    }
}
