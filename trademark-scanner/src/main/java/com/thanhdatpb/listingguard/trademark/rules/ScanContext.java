package com.thanhdatpb.listingguard.trademark.rules;

import com.thanhdatpb.listingguard.trademark.CanonicalInput;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.normalize.NormalizedField;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What the rules of one scan share: the ruleset, the catalog and the normalized fields. The catalog is asked at most
 * once per kind of lookup, however many rules read the result, and a failed lookup fails every rule that needs it.
 */
public final class ScanContext {

    private final TrademarkRuleset ruleset;
    private final TrademarkCatalog catalog;
    private final List<NormalizedField> fields;
    private final String productType;
    private final Set<Integer> productClasses;
    private final Memo<List<CatalogHits.Hit>> exact;
    private final Memo<List<CatalogHits.Hit>> fuzzy;

    private ScanContext(TrademarkRuleset ruleset, TrademarkCatalog catalog, List<NormalizedField> fields,
                        String productType, Set<Integer> productClasses) {
        this.ruleset = ruleset;
        this.catalog = catalog;
        this.fields = List.copyOf(fields);
        this.productType = productType;
        this.productClasses = productClasses == null ? null : Set.copyOf(productClasses);
        this.exact = new Memo<>(() -> CatalogHits.exact(this));
        this.fuzzy = new Memo<>(() -> CatalogHits.fuzzy(this));
    }

    /** A scan with no product type. */
    public static ScanContext create(TrademarkRuleset ruleset, TrademarkCatalog catalog, TextNormalizer normalizer,
                                     CanonicalInput canonical) {
        return create(ruleset, catalog, normalizer, canonical, null, null);
    }

    /**
     * Normalizes the fields the ruleset scans, in canonical order. {@code productType} and its Nice classes come from
     * the ruleset and are both null when the request named no product type; the caller has already checked that the
     * ruleset declares the type.
     */
    public static ScanContext create(TrademarkRuleset ruleset, TrademarkCatalog catalog, TextNormalizer normalizer,
                                     CanonicalInput canonical, String productType, Set<Integer> productClasses) {
        List<NormalizedField> fields = new ArrayList<>();
        for (CanonicalInput.Field field : canonical.fields()) {
            if (isScanned(ruleset, field.name())) {
                fields.add(new NormalizedField(field.name(), field.text(), normalizer.tokenize(field.text())));
            }
        }
        return new ScanContext(ruleset, catalog, fields, productType, productClasses);
    }

    private static boolean isScanned(TrademarkRuleset ruleset, String fieldName) {
        for (String scan : ruleset.matching().scanFields()) {
            if (scan.equals(fieldName) || fieldName.startsWith(scan + "[")) {
                return true;
            }
        }
        return false;
    }

    public TrademarkRuleset ruleset() {
        return ruleset;
    }

    public TrademarkCatalog catalog() {
        return catalog;
    }

    /** The scanned fields in canonical order. */
    public List<NormalizedField> fields() {
        return fields;
    }

    /** The product type the request named, or null. */
    public String productType() {
        return productType;
    }

    /** The Nice classes of the product type, or null when the request named none. */
    public Set<Integer> productClasses() {
        return productClasses;
    }

    public NormalizedField field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    List<CatalogHits.Hit> exactHits() {
        return exact.get();
    }

    List<CatalogHits.Hit> fuzzyHits() {
        return fuzzy.get();
    }

    /** The canonical text of a span, cut to the ruleset's evidence limit without splitting a surrogate pair. */
    public String snippet(NormalizedField field, int start, int end) {
        int limit = ruleset.matching().maxSnippetChars();
        int cut = Math.min(end, start + limit);
        if (cut < end && cut > start && Character.isHighSurrogate(field.text().charAt(cut - 1))) {
            cut--;
        }
        return field.text().substring(start, cut);
    }

    /** Runs a lookup once and, if it failed, fails again with the same exception for every caller. */
    private static final class Memo<T> {
        private final Supplier<T> supplier;
        private T value;
        private RuntimeException failure;
        private boolean done;

        Memo(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        T get() {
            if (!done) {
                done = true;
                try {
                    value = supplier.get();
                } catch (RuntimeException e) {
                    failure = e;
                }
            }
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }
}
