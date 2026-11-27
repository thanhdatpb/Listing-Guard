package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.workspace.StandardTargets.Kind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.HistoryEntry;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.HistorySnapshot;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.TemplateColumn;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ChangeKind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The history of a listing (ADR-062 §6): the versions that are kept, what changed column by column between two of them, and the restore of
 * an earlier version. A restore never overwrites: it writes the old columns as a new version (kind RESTORE) on top of the current one, so
 * the version that was current stays in the history. The responsible person is not restored (it is an ADMIN's decision, kept by its own
 * entries), nor the SKU (it names the listing); a column the listing's template no longer has is not restored and is counted.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class ListingHistory {

    /** One column that is not the same in two versions; {@code before} or {@code after} is null where the column was empty. */
    public record ColumnChange(String key, String header, String target, String before, String after) {
    }

    /** The outcome of a restore: the listing as it is now and how many columns of the old version the template does not have any more. */
    public record Restored(Listing listing, int dropped) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceAccounts accounts;
    private final WorkspaceTemplates templates;
    private final WorkspaceListings listings;
    private final ListingEditor editor;
    private final ListingContent content;
    private final StandardTargets targets;

    public ListingHistory(WorkspaceAccess access, WorkspaceAccounts accounts, WorkspaceTemplates templates, WorkspaceListings listings,
                          ListingEditor editor, ListingContent content, StandardTargets targets) {
        this.access = access;
        this.accounts = accounts;
        this.templates = templates;
        this.listings = listings;
        this.editor = editor;
        this.content = content;
        this.targets = targets;
    }

    public List<HistoryEntry> versions(UUID clientId, UUID listingId) {
        return listings.history(clientId, listingId);
    }

    public Optional<HistorySnapshot> snapshot(UUID clientId, UUID listingId, long version) {
        return listings.historyEntry(clientId, listingId, version);
    }

    /** The columns that differ, in the order of the template, then any other by key. */
    public static List<ColumnChange> diff(Template template, Map<String, String> before, Map<String, String> after) {
        Set<String> keys = new LinkedHashSet<>();
        Map<String, TemplateColumn> columns = new LinkedHashMap<>();
        for (TemplateColumn column : template.columns()) {
            columns.put(column.key(), column);
            keys.add(column.key());
        }
        before.keySet().stream().filter(k -> !keys.contains(k)).sorted().forEach(keys::add);
        after.keySet().stream().filter(k -> !keys.contains(k)).sorted().forEach(keys::add);
        List<ColumnChange> changes = new ArrayList<>();
        for (String key : keys) {
            String a = before.get(key);
            String b = after.get(key);
            if (java.util.Objects.equals(a, b)) {
                continue;
            }
            TemplateColumn column = columns.get(key);
            String header = column == null || column.header().isBlank() ? key : column.header();
            changes.add(new ColumnChange(key, header, template.mapping().targets().get(key), a, b));
        }
        return changes;
    }

    /**
     * Puts an earlier version's columns back as a new version.
     *
     * @param version         the version to put back
     * @param expectedVersion the version the person saw as the current one
     * @throws ListingEditor.VersionConflict when the listing is not at {@code expectedVersion}
     */
    @Transactional
    public Restored restore(Actor actor, UUID listingId, long version, long expectedVersion) {
        Listing first = listings.find(actor.clientId(), listingId).orElseThrow(() -> new WorkspaceException("listing.unknown"));
        if (!access.canEdit(actor, first.ownerAccountId())) {
            throw new WorkspaceException("forbidden");
        }
        listings.lockAccount(first.accountId());
        Listing listing = listings.find(actor.clientId(), listingId).orElseThrow(() -> new WorkspaceException("listing.unknown"));
        if (listing.version() != expectedVersion) {
            throw new ListingEditor.VersionConflict(listing);
        }
        HistorySnapshot old = listings.historyEntry(actor.clientId(), listingId, version).orElseThrow(() -> new WorkspaceException("history.unknown"));
        Template template = templates.find(actor.clientId(), listing.templateId()).orElseThrow(() -> new WorkspaceException("template.none"));
        SellerAccount account = accounts.find(actor.clientId(), listing.accountId()).orElseThrow(() -> new WorkspaceException("account.unknown"));
        Set<String> known = new LinkedHashSet<>();
        template.columns().forEach(c -> known.add(c.key()));
        Map<String, String> data = new LinkedHashMap<>();
        int dropped = 0;
        for (Map.Entry<String, String> entry : old.data().entrySet()) {
            if (known.contains(entry.getKey()) || template.kind() == TemplateKind.ETSY_FIELDS && etsyKey(entry.getKey(), known)) {
                data.put(entry.getKey(), entry.getValue());
            } else {
                dropped++;
            }
        }
        // what names the listing and who is responsible for it are not part of what is restored
        for (StandardFields.ColumnUse use : content.uses(template, listing.data())) {
            Kind kind = use.target() == null ? null : targets.find(account.marketplace(), use.target()).map(StandardTargets.Target::kind).orElse(null);
            if (kind == Kind.SKU || kind == Kind.OWNER) {
                if (listing.data().containsKey(use.dataKey())) {
                    data.put(use.dataKey(), listing.data().get(use.dataKey()));
                } else {
                    data.remove(use.dataKey());
                }
            }
        }
        Map<String, String> ordered = ListingContent.ordered(template, data);
        if (ordered.equals(listing.data())) {
            throw new WorkspaceException("restore.same");
        }
        ListingEditor.Saved saved = editor.write(actor, listing, template, account, ordered, ChangeKind.RESTORE, expectedVersion);
        return new Restored(saved.listing(), dropped);
    }

    private static boolean etsyKey(String key, Set<String> known) {
        if (key.startsWith("extra:")) {
            return true;
        }
        int tilde = key.lastIndexOf('~');
        return tilde > 0 && known.contains(key.substring(0, tilde)) || key.equals("owner");
    }
}
