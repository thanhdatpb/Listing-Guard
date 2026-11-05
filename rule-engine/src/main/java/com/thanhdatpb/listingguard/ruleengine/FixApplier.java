package com.thanhdatpb.listingguard.ruleengine;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.FixAction;
import com.thanhdatpb.listingguard.core.finding.FixSuggestion;
import com.thanhdatpb.listingguard.core.listing.Listing;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a candidate fix to an in-memory copy of a listing so {@link RuleEngine} can check it (ADR-014). It is
 * package-private on purpose: nothing outside the engine applies a fix, and the copy is thrown away.
 */
final class FixApplier {

    private static final Pattern TAG_FIELD = Pattern.compile("tags\\[(\\d+)]");

    private FixApplier() {
    }

    /**
     * @return the listing with the fix applied, or empty when the fix cannot be applied: no fix, an unknown field, an
     *         action the field does not support, an index out of range, or a stored value that is no longer the
     *         finding's {@code value}
     */
    static Optional<Listing> apply(Listing listing, Finding finding) {
        FixSuggestion fix = finding.fix();
        String field = finding.field();
        if (fix == null || field == null) {
            return Optional.empty();
        }
        if (field.equals("title")) {
            if (fix.action() != FixAction.REPLACE || listing.title() == null || !listing.title().equals(finding.value())) {
                return Optional.empty();
            }
            return Optional.of(listing.withTitle(fix.proposedValue()));
        }
        Matcher tag = TAG_FIELD.matcher(field);
        if (!tag.matches()) {
            return Optional.empty();
        }
        int index;
        try {
            index = Integer.parseInt(tag.group(1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        List<String> tags = listing.tags();
        if (index >= tags.size() || !tags.get(index).equals(finding.value())) {
            return Optional.empty();
        }
        List<String> changed = new ArrayList<>(tags);
        switch (fix.action()) {
            case REPLACE -> changed.set(index, fix.proposedValue());
            case REMOVE -> changed.remove(index);
        }
        return Optional.of(listing.withTags(changed));
    }
}
