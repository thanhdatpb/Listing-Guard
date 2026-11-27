package com.thanhdatpb.listingguard.api.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.workspace.StandardFields.Built;
import com.thanhdatpb.listingguard.api.workspace.StandardFields.ColumnUse;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.TemplateColumn;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * How the content of a stored listing becomes its standard fields, its title and its content hash (ADR-062 §1). It is the one place the
 * editor, the clone and the restore use: {@code data} (the columns of the template, by key) is the only source, and what is derived from it
 * is built by the same {@link StandardFields} and hashed by the same {@link #hash} as the import's. Nothing here reads the standard fields
 * back into {@code data}.
 */
@Component
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class ListingContent {

    private final StandardTargets targets;
    private final EtsyFields etsy;
    private final StandardFields standardFields;

    public ListingContent(StandardTargets targets, EtsyFields etsy, ObjectMapper mapper) {
        this.targets = targets;
        this.etsy = etsy;
        this.standardFields = new StandardFields(targets, mapper);
    }

    /** The identity of a listing's content: its columns (keys in code point order) and its role. The import writes the same hash. */
    public static String hash(Map<String, String> data, ListingRole role) {
        return WorkspaceJson.contentHash(new TreeMap<>(data), role.name());
    }

    /**
     * What each column of {@code data} is, in the order the columns were read when the listing was imported: an Amazon template's columns in
     * their order (the keys of a column without a value are simply absent from {@code data}); for the fixed Etsy fields, each field with its
     * repeats ({@code tags}, {@code tags~2}, ...) by number, then the columns no field was given, which are passthrough.
     */
    List<ColumnUse> uses(Template template, Map<String, String> data) {
        List<ColumnUse> uses = new ArrayList<>();
        if (template.kind() == TemplateKind.ETSY_FIELDS) {
            List<String> order = new ArrayList<>(etsy.fields());
            order.add("owner");
            List<String> keys = new ArrayList<>(data.keySet());
            keys.sort(Comparator.<String>comparingInt(k -> rank(order, baseOf(k))).thenComparingInt(ListingContent::repeatOf).thenComparing(k -> k));
            for (String key : keys) {
                String base = baseOf(key);
                uses.add(new ColumnUse(key, base, order.contains(base) ? base : null));
            }
            return uses;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (TemplateColumn column : template.columns()) {
            seen.add(column.key());
            uses.add(new ColumnUse(column.key(), column.header(), template.mapping().targets().get(column.key())));
        }
        data.keySet().stream().filter(k -> !seen.contains(k)).sorted().forEach(k -> uses.add(new ColumnUse(k, k, null)));
        return uses;
    }

    /** The standard fields and the few facts that name the row, built from {@code data} the way the import builds them. */
    public Built build(Marketplace marketplace, Template template, Map<String, String> data) {
        return standardFields.build(marketplace, uses(template, data), data, template.mapping().separators());
    }

    /**
     * The role of a row: the parent/child column if the person gave one, a parent SKU makes a child, a row other rows name as their parent is a
     * parent, anything else stands alone: the import's rule.
     */
    public static ListingRole role(Built built, boolean namedAsParent) {
        if (built.explicitRole() != null) {
            return built.explicitRole();
        }
        if (!built.parentSku().isEmpty()) {
            return ListingRole.CHILD;
        }
        return namedAsParent ? ListingRole.PARENT : ListingRole.STANDALONE;
    }

    private static String baseOf(String key) {
        int tilde = key.lastIndexOf('~');
        return tilde > 0 && key.substring(tilde + 1).chars().allMatch(Character::isDigit) ? key.substring(0, tilde) : key;
    }

    private static int repeatOf(String key) {
        int tilde = key.lastIndexOf('~');
        if (tilde > 0 && key.substring(tilde + 1).chars().allMatch(Character::isDigit) && tilde + 1 < key.length()) {
            return Integer.parseInt(key.substring(tilde + 1));
        }
        return 1;
    }

    private static int rank(List<String> order, String base) {
        int at = order.indexOf(base);
        return at < 0 ? Integer.MAX_VALUE : at;
    }

    /** The data of a listing in the order of its template's columns (the stored JSON does not keep the order), then any other key by name. */
    public static Map<String, String> ordered(Template template, Map<String, String> data) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (TemplateColumn column : template.columns()) {
            if (data.containsKey(column.key())) {
                ordered.put(column.key(), data.get(column.key()));
            }
        }
        data.keySet().stream().filter(k -> !ordered.containsKey(k)).sorted().forEach(k -> ordered.put(k, data.get(k)));
        return ordered;
    }
}
