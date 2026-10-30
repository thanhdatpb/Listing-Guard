package com.thanhdatpb.listingguard.trademark.match;

/**
 * The final fuzzy score (ADR-009, scoring version {@code osa-similarity-1}): optimal string alignment distance, the
 * edit distance that counts an insertion, a deletion, a substitution and the swap of two neighbouring characters as
 * one edit each, turned into {@code (longer - distance) / longer} on code points.
 *
 * <p>Chosen because it is symmetric, deterministic and needs no tuning, a single typo in a short mark and a swapped
 * pair of letters (the two usual misspellings) cost one edit, and the length of the longer text keeps a short gram
 * from matching a long mark. Jaro-Winkler favors a shared prefix, and the trigram measure that retrieves candidates
 * is set-based and ignores order, so the two measures check each other. The quotient is computed in integers before
 * one division, so equal inputs give the same double on every JVM and a score can be compared with a decimal
 * threshold without rounding surprises.
 */
public final class Similarity {

    private Similarity() {
    }

    /** Number of edits (insert, delete, substitute, swap neighbours) to turn {@code a} into {@code b}. */
    public static int osaDistance(String a, String b) {
        int[] x = a.codePoints().toArray();
        int[] y = b.codePoints().toArray();
        int[][] d = new int[x.length + 1][y.length + 1];
        for (int i = 0; i <= x.length; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= y.length; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= x.length; i++) {
            for (int j = 1; j <= y.length; j++) {
                int cost = x[i - 1] == y[j - 1] ? 0 : 1;
                int best = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && x[i - 1] == y[j - 2] && x[i - 2] == y[j - 1]) {
                    best = Math.min(best, d[i - 2][j - 2] + 1);
                }
                d[i][j] = best;
            }
        }
        return d[x.length][y.length];
    }

    /** 1 for equal texts, 0 for texts with nothing in common, otherwise {@code (longer - distance) / longer}. */
    public static double score(String a, String b) {
        int longer = Math.max(a.codePointCount(0, a.length()), b.codePointCount(0, b.length()));
        if (longer == 0) {
            return 1.0;
        }
        return (double) (longer - osaDistance(a, b)) / longer;
    }
}
