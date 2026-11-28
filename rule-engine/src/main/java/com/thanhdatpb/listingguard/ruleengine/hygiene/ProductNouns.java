package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The kind of product a text names: {@code mug}, {@code shirt}, {@code poster}. {@code groups} (a group and every noun that stands for it) and
 * {@code product_types} (a product type of the marketplace and the groups it may be called) are the ruleset's. A product type that the table does
 * not list is unknown, and an unknown type is never judged.
 */
public final class ProductNouns implements FactExtractor {

    public static final String FAMILY = "noun";

    private final Map<String, Pattern> groups;
    private final Map<String, Set<String>> accepted;

    private ProductNouns(Map<String, Pattern> groups, Map<String, Set<String>> accepted) {
        this.groups = groups;
        this.accepted = accepted;
    }

    /** Reads {@code groups} and {@code product_types} of {@code settings}; a type that names an unknown group is refused. */
    public static ProductNouns from(RuleSettings settings) {
        Map<String, Pattern> groups = new java.util.LinkedHashMap<>();
        settings.stringListMapParam("groups").forEach((group, nouns) -> groups.put(group, Regexes.compile(settings.ruleId(),
                nouns.stream().map(Regexes::phrase).collect(java.util.stream.Collectors.joining("|")), true)));
        Map<String, Set<String>> accepted = new java.util.HashMap<>();
        settings.stringListMapParam("product_types").forEach((type, names) -> {
            for (String name : names) {
                if (!groups.containsKey(name)) {
                    throw new RulesetException("Rule '" + settings.ruleId() + "': product type '" + type + "' names unknown group '" + name + "'");
                }
            }
            accepted.put(normalizeType(type), new LinkedHashSet<>(names));
        });
        return new ProductNouns(groups, accepted);
    }

    /** The product types as the ruleset spells them: the last part of a path ({@code A > B > Mugs}), upper case, other characters as {@code _}. */
    public static String normalizeType(String raw) {
        if (raw == null) {
            return "";
        }
        String last = raw;
        for (String separator : new String[] {">", "/"}) {
            last = last.substring(last.lastIndexOf(separator) + 1);
        }
        return last.strip().toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "_").replaceAll("^_+|_+$", "");
    }

    /** The groups the product type may be called, or empty when the table does not list it. */
    public Optional<Set<String>> groupsOf(String productType) {
        return Optional.ofNullable(accepted.get(normalizeType(productType)));
    }

    @Override
    public List<Mention> extract(String text) {
        List<Mention> mentions = new ArrayList<>();
        groups.forEach((group, pattern) -> {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                mentions.add(new Mention(FAMILY, group, matcher.group(), matcher.start(), matcher.end()));
            }
        });
        mentions.sort(Comparator.comparingInt(Mention::start));
        return mentions;
    }
}
