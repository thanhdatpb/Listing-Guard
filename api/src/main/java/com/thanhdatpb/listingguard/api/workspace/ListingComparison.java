package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.workspace.StandardTargets.Kind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.TitleMatch;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * "Compared with the original" (ADR-062 §5): for a clone whose original still exists, which columns are still exactly the original's ("not
 * edited yet", the usual mistake of a copy), and whether the title is the original's or another listing's of the same account. It is worked
 * out when a page is shown, from what is stored: nothing is saved and no audit result changes. A repeated title is the workspace's own
 * observation; no marketplace policy is said about it.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class ListingComparison {

    /**
     * @param original      the listing this one was cloned from
     * @param untouched     the keys of this listing's columns whose value is the original's (a column the original has no partner for is not here)
     * @param sameAsOriginal the title is the original's, ignoring case and the blanks at both ends
     * @param sameAccount   other listings of the account with this title (at most 5; not the original)
     */
    public record Comparison(Listing original, Set<String> untouched, boolean sameAsOriginal, List<TitleMatch> sameAccount) {

        public boolean titleWarning() {
            return sameAsOriginal || !sameAccount.isEmpty();
        }
    }

    private final WorkspaceTemplates templates;
    private final WorkspaceListings listings;
    private final ListingCloner cloner;
    private final StandardTargets targets;

    public ListingComparison(WorkspaceTemplates templates, WorkspaceListings listings, ListingCloner cloner, StandardTargets targets) {
        this.templates = templates;
        this.listings = listings;
        this.cloner = cloner;
        this.targets = targets;
    }

    /**
     * A clone's original and the columns of the clone paired with the columns of the original, by the one pairing of ADR-062 §4: the same keys when both
     * listings use one template or the fixed Etsy fields, otherwise {@link ListingCloner#pairs}. Empty when the listing is no clone, or its original is
     * deleted or its template is gone. Nothing else in the workspace pairs columns, so the audit and the page cannot disagree.
     *
     * @param original         the listing this one was cloned from
     * @param originalTemplate the template of {@code original}
     * @param pairs            {@code sourceKey} is a column of the original, {@code targetKey} the column of the clone it goes to
     */
    public record Alignment(Listing original, Template originalTemplate, List<ListingCloner.Pair> pairs) {
    }

    public Optional<Alignment> aligned(UUID clientId, Listing listing, Template template) {
        if (listing.clonedFrom() == null) {
            return Optional.empty();
        }
        Listing original = listings.find(clientId, listing.clonedFrom()).orElse(null);
        if (original == null) {
            return Optional.empty();
        }
        Template originalTemplate = original.templateId().equals(template.id()) ? template : templates.find(clientId, original.templateId()).orElse(null);
        if (originalTemplate == null) {
            return Optional.empty();
        }
        List<ListingCloner.Pair> pairs = originalTemplate.id().equals(template.id()) || template.kind() == TemplateKind.ETSY_FIELDS
                ? identity(original, template) : cloner.pairs(originalTemplate, template);
        return Optional.of(new Alignment(original, originalTemplate, pairs));
    }

    public Optional<Comparison> of(UUID clientId, Listing listing, Template template, SellerAccount account) {
        Alignment alignment = aligned(clientId, listing, template).orElse(null);
        if (alignment == null) {
            return Optional.empty();
        }
        Listing original = alignment.original();
        Set<String> untouched = new HashSet<>();
        for (ListingCloner.Pair pair : alignment.pairs()) {
            String value = listing.data().get(pair.targetKey());
            Kind kind = pair.target() == null ? null : targets.find(account.marketplace(), pair.target()).map(StandardTargets.Target::kind).orElse(null);
            if (value == null || value.isBlank() || kind == Kind.SKU || kind == Kind.OWNER) {
                continue;
            }
            String was = original.data().get(pair.sourceKey());
            if (was != null && line(was).equals(line(value))) {
                untouched.add(pair.targetKey());
            }
        }
        boolean same = same(listing.title(), original.title());
        List<TitleMatch> others = listings.sameTitle(clientId, listing.accountId(), listing.id(), listing.title()).stream()
                .filter(m -> !m.id().equals(original.id())).toList();
        return Optional.of(new Comparison(original, Set.copyOf(untouched), same, others));
    }

    /** The same keys on both sides: both listings use one template, or the fixed Etsy fields. */
    private static List<ListingCloner.Pair> identity(Listing original, Template template) {
        return original.data().keySet().stream().map(k -> new ListingCloner.Pair(k, k, k, k,
                template.kind() == TemplateKind.ETSY_FIELDS ? baseTarget(k) : template.mapping().targets().get(k))).toList();
    }

    private static String baseTarget(String key) {
        int tilde = key.lastIndexOf('~');
        return tilde > 0 ? key.substring(0, tilde) : key;
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && !a.isBlank() && a.strip().equalsIgnoreCase(b.strip());
    }

    private static String line(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
