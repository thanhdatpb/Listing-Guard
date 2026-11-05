package com.thanhdatpb.listingguard.api.console.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts a suggested fix into the editor form, and nothing else (ADR-014, part B3). It changes one field of the text the seller
 * typed, with the value the verified suggestion proposed; it checks nothing, stores nothing and writes to no marketplace. The
 * seller then presses "Check" to see the effect, because a fix is verified alone against the listing it was computed from
 * (ADR-014 A4) and several applied together are not.
 *
 * <p>Like the engine's own applier it refuses when the field no longer holds the value the finding was about: the seller has
 * typed since the check, and a proposal for the old text must not overwrite the new. A tag is found by its value (the finding's
 * index counts the cleaned list, without blank lines, and is tried first); when the index no longer fits, the tag is used only
 * if exactly one line holds that value.
 */
final class EditorFixApplier {

    /** The form after the attempt (unchanged when {@code applied} is false) and a sentence for the seller. */
    record Outcome(EditorForm form, boolean applied, String message) {
    }

    private static final Pattern TAG_FIELD = Pattern.compile("tags\\[(\\d+)]");
    private static final String CHECK_AGAIN = " Nothing was checked: press Check to see the result for the changed listing.";

    private EditorFixApplier() {
    }

    static Outcome apply(EditorForm form, String field, String value, String action, String proposedValue) {
        if (field == null || value == null || action == null) {
            return refused(form, "That suggestion is no longer available. Press Check again.");
        }
        boolean replace = "REPLACE".equals(action);
        boolean remove = "REMOVE".equals(action);
        if (!replace && !remove) {
            return refused(form, "That suggestion is no longer available. Press Check again.");
        }
        if (replace && (proposedValue == null || proposedValue.isBlank() || proposedValue.contains("\n")
                || proposedValue.contains("\r"))) {
            return refused(form, "That suggestion has no value that can be put in the field.");
        }
        if (field.equals("title") && replace) {
            return title(form, value, proposedValue);
        }
        Matcher tag = TAG_FIELD.matcher(field);
        if (tag.matches()) {
            return tag(form, Integer.parseInt(tag.group(1)), value, replace ? proposedValue : null);
        }
        return refused(form, "This suggestion cannot be applied to " + field + " here.");
    }

    private static Outcome title(EditorForm form, String value, String proposed) {
        if (!form.title().trim().equals(value)) {
            return changedSinceTheCheck(form);
        }
        return new Outcome(form.withTitle(proposed), true, "Replaced the title with the suggested value." + CHECK_AGAIN);
    }

    /** {@code replacement} is null to remove the tag. */
    private static Outcome tag(EditorForm form, int cleanedIndex, String value, String replacement) {
        List<String> lines = new ArrayList<>(EditorListingMapper.lines(form.tags()));
        List<Integer> cleaned = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                cleaned.add(i);
            }
        }
        Integer line = null;
        if (cleanedIndex < cleaned.size() && lines.get(cleaned.get(cleanedIndex)).trim().equals(value)) {
            line = cleaned.get(cleanedIndex);
        } else {
            List<Integer> matches = cleaned.stream().filter(i -> lines.get(i).trim().equals(value)).toList();
            if (matches.size() == 1) {
                line = matches.get(0);
            }
        }
        if (line == null) {
            return changedSinceTheCheck(form);
        }
        if (replacement == null) {
            lines.remove((int) line);
            return new Outcome(form.withTags(String.join("\n", lines)), true,
                    "Removed the tag \"" + value + "\"." + CHECK_AGAIN);
        }
        lines.set(line, replacement);
        return new Outcome(form.withTags(String.join("\n", lines)), true,
                "Replaced the tag \"" + value + "\" with \"" + replacement + "\"." + CHECK_AGAIN);
    }

    private static Outcome changedSinceTheCheck(EditorForm form) {
        return refused(form, "The field changed since the check, so the suggestion was not applied. Press Check again.");
    }

    private static Outcome refused(EditorForm form, String message) {
        return new Outcome(form, false, message);
    }
}
