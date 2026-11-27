package com.thanhdatpb.listingguard.api.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The standard fields a workspace column can be given, read from {@code workspace/standard-targets.yaml} (ADR-059). The names in the file
 * only fill in a guess; nothing here is a marketplace's own column list, and no column name is written in Java.
 */
public final class StandardTargets {

    /** How a target is read when a listing's standard fields are built. */
    public enum Kind {
        /** names the row; not a listing field */
        SKU,
        PARENT_SKU,
        /** {@code parent} / {@code child} */
        PARENT_CHILD,
        THEME,
        /** the account responsible for the row, by e-mail */
        OWNER,
        TEXT,
        /** one entry per cell, a cell cut at the separator when the target has one */
        LIST,
        /** one column is text, several are joined with a space */
        TEXT_OR_LIST,
        PRICE,
        INTEGER,
        /** a variation attribute named by the header of its column */
        ATTRIBUTE
    }

    public record Target(String key, Kind kind, Set<Marketplace> marketplaces, String defaultSeparator, Set<String> names,
                         List<String> prefixes) {

        public boolean multiple() {
            return kind == Kind.LIST || kind == Kind.TEXT_OR_LIST || kind == Kind.ATTRIBUTE;
        }

        /** True for the targets that become a field of the listing's standard fields. */
        public boolean listingField() {
            return switch (kind) {
                case TEXT, LIST, TEXT_OR_LIST, PRICE, INTEGER, THEME, ATTRIBUTE -> true;
                default -> false;
            };
        }

        public boolean appliesTo(Marketplace marketplace) {
            return marketplaces.isEmpty() || marketplaces.contains(marketplace);
        }
    }

    public static final String RESOURCE = "workspace/standard-targets.yaml";
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]");

    private final List<Target> targets;
    private final Map<String, String> separators;
    private final String version;
    private final Set<String> parentValues;
    private final Set<String> childValues;

    public StandardTargets(List<Target> targets, Map<String, String> separators, String version, Set<String> parentValues,
                           Set<String> childValues) {
        this.parentValues = Set.copyOf(parentValues);
        this.childValues = Set.copyOf(childValues);
        this.targets = List.copyOf(targets);
        this.separators = Map.copyOf(separators);
        this.version = version;
    }

    public static StandardTargets load() {
        try (InputStream in = StandardTargets.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing.");
            }
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(in);
            if (!"unverified".equals(root.path("status").asText())) {
                throw new IllegalStateException(RESOURCE + ": status must stay unverified until a real template has been compared.");
            }
            Map<String, String> separators = new LinkedHashMap<>();
            root.path("separators").fields().forEachRemaining(e -> separators.put(e.getKey(), e.getValue().asText()));
            List<Target> list = new ArrayList<>();
            for (JsonNode node : root.path("targets")) {
                Set<Marketplace> marketplaces = new java.util.LinkedHashSet<>();
                node.path("marketplaces").forEach(m -> marketplaces.add(Marketplace.valueOf(m.asText())));
                Set<String> names = new java.util.LinkedHashSet<>();
                node.path("names").forEach(n -> names.add(n.asText()));
                List<String> prefixes = new ArrayList<>();
                node.path("prefixes").forEach(p -> prefixes.add(p.asText()));
                String separator = node.hasNonNull("default_separator") ? node.get("default_separator").asText() : null;
                if (separator != null && !separators.containsKey(separator)) {
                    throw new IllegalStateException(RESOURCE + ": unknown separator " + separator);
                }
                list.add(new Target(node.path("key").asText(), Kind.valueOf(node.path("kind").asText()), marketplaces, separator, names,
                        prefixes));
            }
            Set<String> parents = new java.util.LinkedHashSet<>();
            root.path("parent_values").forEach(v -> parents.add(v.asText().toLowerCase(Locale.ROOT)));
            Set<String> children = new java.util.LinkedHashSet<>();
            root.path("child_values").forEach(v -> children.add(v.asText().toLowerCase(Locale.ROOT)));
            return new StandardTargets(list, separators, root.path("version").asText(), parents, children);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }

    public boolean isParentValue(String cell) {
        return cell != null && parentValues.contains(cell.strip().toLowerCase(Locale.ROOT));
    }

    public boolean isChildValue(String cell) {
        return cell != null && childValues.contains(cell.strip().toLowerCase(Locale.ROOT));
    }

    public String version() {
        return version;
    }

    public List<Target> of(Marketplace marketplace) {
        return targets.stream().filter(t -> t.appliesTo(marketplace)).toList();
    }

    public Optional<Target> find(Marketplace marketplace, String key) {
        return of(marketplace).stream().filter(t -> t.key().equals(key)).findFirst();
    }

    /** The text a separator key stands for, or empty for an unknown key. */
    public Optional<String> separator(String key) {
        return Optional.ofNullable(separators.get(key));
    }

    public java.util.Set<String> separatorKeys() {
        return separators.keySet();
    }

    /** The target a header names, by the lists of the file: the first target whose names contain it, else the first whose prefix starts it. */
    public Optional<Target> guess(Marketplace marketplace, String header) {
        String word = normalize(header);
        if (word.isEmpty()) {
            return Optional.empty();
        }
        List<Target> candidates = of(marketplace);
        for (Target target : candidates) {
            if (target.names().contains(word)) {
                return Optional.of(target);
            }
        }
        for (Target target : candidates) {
            for (String prefix : target.prefixes()) {
                if (word.startsWith(prefix)) {
                    return Optional.of(target);
                }
            }
        }
        return Optional.empty();
    }

    /** The header lower-cased, stripped of accents and of everything but letters and digits. */
    public static String normalize(String header) {
        if (header == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(header.strip().toLowerCase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD);
        return NON_ALNUM.matcher(decomposed.replaceAll("\\p{M}+", "")).replaceAll("");
    }
}
