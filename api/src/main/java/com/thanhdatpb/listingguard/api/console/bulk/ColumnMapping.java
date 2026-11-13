package com.thanhdatpb.listingguard.api.console.bulk;

import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.bulk.BulkTargets.Target;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What every column of a table is, by position: a key of {@link BulkTargets} or {@link BulkTargets#SKIP}, and for each list field
 * the separator that cuts a single column into entries (ADR-029). It is what the seller confirms on the mapping step; a guess or a
 * remembered mapping only fills it in beforehand.
 */
record ColumnMapping(List<String> targets, Map<String, String> separators) {

    ColumnMapping {
        targets = List.copyOf(targets);
        separators = Map.copyOf(separators);
    }

    String targetOf(int column) {
        return column < targets.size() ? targets.get(column) : BulkTargets.SKIP;
    }

    /** The separator key chosen for a list target, or its default. */
    String separatorOf(Target target) {
        return separators.getOrDefault(target.key(), target.defaultSeparator() == null ? "comma" : target.defaultSeparator());
    }

    /**
     * Why this mapping cannot be run, in Vietnamese; empty when it can. A target that takes one column given to several, and a
     * mapping that reads no listing field at all, are refused rather than guessed at.
     */
    List<String> problems(ConsoleMessages messages, EditorMarketplace marketplace, List<String> labels) {
        List<String> problems = new ArrayList<>();
        boolean anyListingField = false;
        for (Target target : BulkTargets.of(marketplace)) {
            List<String> columns = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                if (target.key().equals(targets.get(i))) {
                    columns.add(i < labels.size() ? labels.get(i) : BulkTable.letters(i));
                }
            }
            if (!target.multiple() && columns.size() > 1) {
                problems.add(messages.get("bulk.problem.single", target.label(messages), columns.size(), String.join(", ", columns)));
            }
            if (target.listingField() && !columns.isEmpty()) {
                anyListingField = true;
            }
        }
        if (!anyListingField) {
            problems.add(messages.get("bulk.problem.nolistingfield"));
        }
        return problems;
    }

    /**
     * The mapping of a submitted form: {@code col.<i>} is the target of column {@code i}, {@code sep.<key>} the separator of a list
     * target. An unknown target or separator is a refused request, never a default: it can only come from a form that was edited by
     * hand.
     */
    static ColumnMapping fromParams(ConsoleMessages messages, EditorMarketplace marketplace, int columns, Map<String, String> params) {
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < columns; i++) {
            String value = params.get("col." + i);
            if (value == null || value.isBlank()) {
                targets.add(BulkTargets.SKIP);
            } else if (BulkTargets.find(marketplace, value.trim()).isPresent()) {
                targets.add(value.trim());
            } else {
                throw new BulkTable.Refused(400, messages.get("bulk.error.badcolumn", BulkTable.letters(i)));
            }
        }
        Map<String, String> separators = new LinkedHashMap<>();
        for (Target target : BulkTargets.of(marketplace)) {
            if (!target.separable()) {
                continue;
            }
            String value = params.get("sep." + target.key());
            if (value == null || value.isBlank()) {
                continue;
            }
            if (BulkTargets.separator(value.trim()).isEmpty()) {
                throw new BulkTable.Refused(400, messages.get("bulk.error.badseparator", target.label(messages)));
            }
            separators.put(target.key(), value.trim());
        }
        return new ColumnMapping(targets, separators);
    }

    /**
     * The starting point of the mapping step: for each column the target the account used for that header last time, else the one
     * its header names, else none (a table of one column with no recognizable header is read as the titles). A target that takes one
     * column goes to the first column that claims it. Nothing here is run until the seller confirms.
     */
    static ColumnMapping suggest(EditorMarketplace marketplace, List<String> labels, BulkMappingStore.Stored stored) {
        List<String> targets = new ArrayList<>();
        java.util.Set<String> claimed = new java.util.HashSet<>();
        for (String label : labels) {
            String key = BulkTargets.SKIP;
            String remembered = stored == null ? null : stored.columns().get(label);
            if (remembered != null && BulkTargets.find(marketplace, remembered).isPresent()) {
                key = remembered;
            } else {
                key = BulkTargets.guess(marketplace, label).map(Target::key).orElse(BulkTargets.SKIP);
            }
            Target target = key.isEmpty() ? null : BulkTargets.find(marketplace, key).orElse(null);
            if (target != null && !target.multiple() && !claimed.add(key)) {
                key = BulkTargets.SKIP;
            }
            targets.add(key);
        }
        if (labels.size() == 1 && targets.get(0).isEmpty()) {
            targets.set(0, "title");
        }
        Map<String, String> separators = new LinkedHashMap<>();
        if (stored != null) {
            stored.separators().forEach((key, separator) -> {
                if (BulkTargets.find(marketplace, key).filter(Target::separable).isPresent() && BulkTargets.separator(separator).isPresent()) {
                    separators.put(key, separator);
                }
            });
        }
        return new ColumnMapping(targets, separators);
    }

    /** What to remember: the label of every mapped column with its target, and the separator of each list target in use. */
    BulkMappingStore.Stored toStored(EditorMarketplace marketplace, List<String> labels) {
        Map<String, String> columns = new LinkedHashMap<>();
        Map<String, String> used = new LinkedHashMap<>();
        for (int i = 0; i < targets.size() && i < labels.size(); i++) {
            String key = targets.get(i);
            if (key.isEmpty()) {
                continue;
            }
            columns.putIfAbsent(labels.get(i), key);
            BulkTargets.find(marketplace, key).filter(Target::separable)
                    .ifPresent(target -> used.put(target.key(), separatorOf(target)));
        }
        return new BulkMappingStore.Stored(columns, used);
    }
}
