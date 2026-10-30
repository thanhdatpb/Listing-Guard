package com.thanhdatpb.listingguard.trademark.normalize;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns canonical text (NFC, trimmed) into match tokens and keeps, for every token, the span it came from in that
 * canonical text, so evidence can point at what the seller actually wrote.
 *
 * <p>Order for every code point: configured deletions, lower case, Unicode compatibility decomposition with the
 * combining marks dropped (diacritics), the fold map, the homoglyph map. Then, word by word, the leet map (see below).
 * Every other character that is not a letter or a digit separates tokens. All the tables are ruleset keys
 * (ADR-001); this class holds only the order of the steps. A change to the steps is a new {@link #VERSION}.
 *
 * <p>A leet character (a digit or symbol that can stand for a letter) is mapped only inside a word, with at least
 * the configured number of letters before and after it in that word: {@code z0rvex} becomes {@code zorvex} but
 * {@code 3d}, {@code 2xl} and {@code 100%} are left alone. A leet symbol that is not mapped acts as a separator.
 *
 * <p>Nothing here removes or corrects words: rules must see what the user wrote.
 */
public final class TextNormalizer {

    /** Bump when the steps or their order change; it is part of the analysis fingerprint (ADR-010). */
    public static final String VERSION = "normalization-1";

    private final NormalizationSettings settings;

    public TextNormalizer(NormalizationSettings settings) {
        this.settings = settings;
    }

    /** A code point of the normalized stream together with the raw span it came from. */
    private static final class Unit {
        final int codePoint;
        final int start;
        int end;
        final boolean leetCandidate;
        boolean separator;

        Unit(int codePoint, int start, int end, boolean separator, boolean leetCandidate) {
            this.codePoint = codePoint;
            this.start = start;
            this.end = end;
            this.separator = separator;
            this.leetCandidate = leetCandidate;
        }
    }

    /** The normalized tokens of {@code text}, in order, each with its span in {@code text}. */
    public List<Token> tokenize(String text) {
        List<Unit> mapped = applyLeet(foldedUnits(text));

        List<Token> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int start = -1;
        int end = -1;
        for (Unit unit : mapped) {
            if (unit.separator) {
                if (current.length() > 0) {
                    tokens.add(new Token(tokens.size(), current.toString(), start, end));
                    current.setLength(0);
                }
                continue;
            }
            if (current.length() == 0) {
                start = unit.start;
            }
            current.appendCodePoint(unit.codePoint);
            end = unit.end;
        }
        if (current.length() > 0) {
            tokens.add(new Token(tokens.size(), current.toString(), start, end));
        }
        return tokens;
    }

    /** The normalized token texts of {@code text}; used for catalog marks and ruleset word lists. */
    public List<String> tokenTexts(String text) {
        return tokenize(text).stream().map(Token::text).toList();
    }

    /** The normalized form of a catalog mark: its tokens joined by one space. */
    public String normalizeMark(String mark) {
        return String.join(" ", tokenTexts(mark));
    }

    private List<Unit> foldedUnits(String text) {
        List<Unit> units = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int next = i + Character.charCount(codePoint);
            if (settings.deleteChars().contains(codePoint)) {
                i = next;
                continue;
            }
            String lower = new String(Character.toChars(codePoint)).toLowerCase(Locale.ROOT);
            String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFKD);
            int j = 0;
            while (j < decomposed.length()) {
                int part = decomposed.codePointAt(j);
                j += Character.charCount(part);
                if (isCombiningMark(part)) {
                    // a mark that follows its letter belongs to that letter's span
                    if (!units.isEmpty() && !units.get(units.size() - 1).separator) {
                        units.get(units.size() - 1).end = next;
                    }
                    continue;
                }
                String folded = settings.foldMap().get(part);
                if (folded == null) {
                    folded = new String(Character.toChars(part));
                }
                for (int k = 0; k < folded.length(); ) {
                    int letter = folded.codePointAt(k);
                    k += Character.charCount(letter);
                    String imitated = settings.homoglyphs().get(letter);
                    if (imitated == null) {
                        units.add(unit(letter, i, next));
                        continue;
                    }
                    for (int m = 0; m < imitated.length(); ) {
                        int target = imitated.codePointAt(m);
                        m += Character.charCount(target);
                        units.add(unit(target, i, next));
                    }
                }
            }
            i = next;
        }
        return units;
    }

    private Unit unit(int codePoint, int start, int end) {
        boolean alnum = Character.isLetterOrDigit(codePoint);
        boolean leet = settings.leetMap().containsKey(codePoint);
        return new Unit(codePoint, start, end, !alnum && !leet, leet);
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    /** Maps leet characters inside words; a leet symbol left unmapped becomes a separator. */
    private List<Unit> applyLeet(List<Unit> units) {
        List<Unit> result = new ArrayList<>(units.size());
        int min = settings.leetMinLettersAround();
        int i = 0;
        while (i < units.size()) {
            if (units.get(i).separator) {
                result.add(units.get(i));
                i++;
                continue;
            }
            int runEnd = i;
            int letters = 0;
            while (runEnd < units.size() && !units.get(runEnd).separator) {
                if (Character.isLetter(units.get(runEnd).codePoint)) {
                    letters++;
                }
                runEnd++;
            }
            int before = 0;
            for (int k = i; k < runEnd; k++) {
                Unit unit = units.get(k);
                if (Character.isLetter(unit.codePoint)) {
                    before++;
                    result.add(unit);
                } else if (!unit.leetCandidate) {
                    result.add(unit);
                } else if (before >= min && letters - before >= min) {
                    String letter = settings.leetMap().get(unit.codePoint);
                    for (int m = 0; m < letter.length(); ) {
                        int target = letter.codePointAt(m);
                        m += Character.charCount(target);
                        result.add(new Unit(target, unit.start, unit.end, false, false));
                    }
                } else {
                    if (!Character.isLetterOrDigit(unit.codePoint)) {
                        unit.separator = true;
                    }
                    result.add(unit);
                }
            }
            i = runEnd;
        }
        return result;
    }
}
