package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.workspace.StandardFields.Built;
import com.thanhdatpb.listingguard.api.workspace.StandardTargets.Kind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.Write;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.TemplateColumn;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ChangeKind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingSource;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The editor of the workspace (ADR-062): it changes the columns of a listing by the template's columns and builds everything else from them
 * again with the import's own function ({@link ListingContent}). A save takes the seller account's lock like an import, is guarded by the
 * listing's {@code version}, writes a history entry and says {@link WorkspaceEvents.ListingSaved} (the audit hears it after the commit). A
 * save that changes no column writes nothing: no new version, no history entry, no event. The log carries ids and counts, never a cell.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class ListingEditor {

    private static final Logger log = LoggerFactory.getLogger(ListingEditor.class);
    /** The longest cell a sheet holds (Excel's limit), so a value that long is a mistake and not a listing. */
    static final int CELL_MAX = 32_767;

    /** A column of the form: the key its value is kept under, the header as the file wrote it, and what the column is. */
    public record Field(String key, String header, String target, Kind kind, boolean readOnly, boolean multiline, String value) {

        public boolean passthrough() {
            return target == null;
        }
    }

    /** The columns that are the same standard field, side by side (the five bullets), in the order of the template. */
    public record Group(String target, List<Field> fields) {
    }

    public record Layout(List<Group> groups, List<Field> passthrough) {

        public List<Field> all() {
            List<Field> all = new ArrayList<>();
            groups.forEach(g -> all.addAll(g.fields()));
            all.addAll(passthrough);
            return all;
        }
    }

    /** Someone saved the listing after the page was opened; {@code current} is what is stored now. */
    public static final class VersionConflict extends RuntimeException {

        private final transient Listing current;

        public VersionConflict(Listing current) {
            super("version");
            this.current = current;
        }

        public Listing current() {
            return current;
        }
    }

    public record Saved(Listing listing, boolean changed, boolean readyReverted) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceAccounts accounts;
    private final WorkspaceTemplates templates;
    private final WorkspaceListings listings;
    private final TemplateService templateService;
    private final ListingContent content;
    private final StandardTargets targets;
    private final EtsyFields etsy;
    private final ApplicationEventPublisher events;

    public ListingEditor(WorkspaceAccess access, WorkspaceAccounts accounts, WorkspaceTemplates templates, WorkspaceListings listings,
                         TemplateService templateService, ListingContent content, StandardTargets targets, EtsyFields etsy,
                         ApplicationEventPublisher events) {
        this.access = access;
        this.accounts = accounts;
        this.templates = templates;
        this.listings = listings;
        this.templateService = templateService;
        this.content = content;
        this.targets = targets;
        this.etsy = etsy;
        this.events = events;
    }

    // ------------------------------------------------------------------ the form

    /**
     * The columns of the form for a template: grouped by the standard field they were given, a group in the order its first column has in
     * the template, the columns no field was given as passthrough. When {@code creating} the SKU is typed and the responsible-person column
     * is left out (the person who creates is responsible); when editing the SKU and that column are shown and cannot be changed.
     */
    public Layout layout(Template template, SellerAccount account, Map<String, String> data, boolean creating) {
        Map<String, List<Field>> groups = new LinkedHashMap<>();
        List<Field> passthrough = new ArrayList<>();
        for (Slot slot : slots(template, data)) {
            Kind kind = slot.target() == null ? null : targets.find(account.marketplace(), slot.target()).map(StandardTargets.Target::kind).orElse(null);
            if (kind == Kind.OWNER && creating) {
                continue;
            }
            boolean readOnly = kind == Kind.OWNER || kind == Kind.SKU && !creating;
            String value = data.getOrDefault(slot.key(), "");
            boolean multiline = kind == Kind.LIST || kind == Kind.TEXT_OR_LIST || "description".equals(slot.target()) || value.indexOf('\n') >= 0;
            Field field = new Field(slot.key(), slot.header(), slot.target(), kind, readOnly, multiline, value);
            if (slot.target() == null) {
                passthrough.add(field);
            } else {
                groups.computeIfAbsent(slot.target(), k -> new ArrayList<>()).add(field);
            }
        }
        List<Group> list = new ArrayList<>();
        groups.forEach((target, fields) -> list.add(new Group(target, List.copyOf(fields))));
        return new Layout(List.copyOf(list), List.copyOf(passthrough));
    }

    private record Slot(String key, String header, String target) {
    }

    private List<Slot> slots(Template template, Map<String, String> data) {
        List<Slot> slots = new ArrayList<>();
        if (template.kind() == TemplateKind.ETSY_FIELDS) {
            for (TemplateColumn column : template.columns()) {
                List<String> keys = new ArrayList<>();
                for (StandardFields.ColumnUse use : content.uses(template, data)) {
                    if (column.key().equals(use.target())) {
                        keys.add(use.dataKey());
                    }
                }
                if (keys.isEmpty()) {
                    keys.add(column.key());
                }
                for (String key : keys) {
                    slots.add(new Slot(key, column.header(), column.key()));
                }
            }
            for (StandardFields.ColumnUse use : content.uses(template, data)) {
                if (use.target() == null || "owner".equals(use.target())) {
                    slots.add(new Slot(use.dataKey(), use.dataKey().startsWith("extra:") ? use.dataKey().substring(6) : use.dataKey(), use.target()));
                }
            }
            return slots;
        }
        for (StandardFields.ColumnUse use : content.uses(template, data)) {
            slots.add(new Slot(use.dataKey(), use.header().isBlank() ? use.dataKey() : use.header(), use.target()));
        }
        return slots;
    }

    // ------------------------------------------------------------------ save

    /**
     * Saves what a person typed into the columns of a listing.
     *
     * @param posted the columns that were sent, by key; a column that was not sent is left as it is
     * @throws VersionConflict when the listing is not at {@code expectedVersion} any more
     */
    @Transactional
    public Saved save(Actor actor, UUID listingId, long expectedVersion, Map<String, String> posted) {
        Listing first = listings.find(actor.clientId(), listingId).orElseThrow(() -> new WorkspaceException("listing.unknown"));
        if (!access.canEdit(actor, first.ownerAccountId())) {
            throw new WorkspaceException("forbidden");
        }
        listings.lockAccount(first.accountId());
        Listing listing = listings.find(actor.clientId(), listingId).orElseThrow(() -> new WorkspaceException("listing.unknown"));
        if (listing.version() != expectedVersion) {
            throw new VersionConflict(listing);
        }
        Template template = templates.find(actor.clientId(), listing.templateId()).orElseThrow(() -> new WorkspaceException("template.none"));
        SellerAccount account = accounts.find(actor.clientId(), listing.accountId()).orElseThrow(() -> new WorkspaceException("account.unknown"));
        Layout layout = layout(template, account, listing.data(), false);
        Map<String, String> data = merge(template, listing.data(), layout, posted);
        if (data.equals(listing.data())) {
            return new Saved(listing, false, false);
        }
        return write(actor, listing, template, account, data, ChangeKind.EDIT, expectedVersion);
    }

    /**
     * Writes new columns into a listing the caller holds the lock of and has checked the version of: the standard fields, the title, the role
     * and the hash are built from the columns, a listing that was "ready" goes back to a draft (what was ready is not what is there now), the
     * history entry is written and the audit is told. Used by the editor's save and by the restore of an earlier version.
     */
    Saved write(Actor actor, Listing listing, Template template, SellerAccount account, Map<String, String> data, ChangeKind kind,
                long expectedVersion) {
        Built built = content.build(account.marketplace(), template, data);
        ListingRole role = checkedRole(actor, account, listing.sku(), built);
        boolean wasReady = listing.status() == ListingStatus.READY_TO_EXPORT;
        ListingStatus status = wasReady ? ListingStatus.DRAFT : listing.status();
        Listing state = new Listing(listing.id(), listing.clientId(), listing.accountId(), listing.templateId(), listing.sku(),
                built.parentSku().isEmpty() ? null : built.parentSku(), role, built.title(), data, built.fields(), ListingContent.hash(data, role),
                listing.ownerAccountId(), status, listing.source(), listing.clonedFrom(), listing.version(), listing.createdAt(),
                listing.updatedAt(), null);
        if (!listings.updateContent(actor.clientId(), new Write(listing.id(), state, kind, actor.accountId(), expectedVersion))) {
            throw new VersionConflict(listings.find(actor.clientId(), listing.id()).orElseThrow(() -> new WorkspaceException("listing.unknown")));
        }
        events.publishEvent(new WorkspaceEvents.ListingSaved(actor.clientId(), listing.id()));
        log.info("Workspace listing {} of client {} written as {} (version {})", listing.id(), actor.clientId(), kind, expectedVersion + 1);
        return new Saved(listings.find(actor.clientId(), listing.id()).orElseThrow(), true, wasReady);
    }

    /**
     * The role of a row and the checks of a parent: it is not its own parent, the parent is a listing of the account, a child names one.
     * The SKU the standard fields name must be the listing's own (the SKU column is not editable).
     */
    private ListingRole checkedRole(Actor actor, SellerAccount account, String sku, Built built) {
        if (!sku.equals(built.sku())) {
            throw new WorkspaceException("sku.fixed");
        }
        if (!built.parentSku().isEmpty()) {
            if (built.parentSku().equals(sku)) {
                throw new WorkspaceException("parent.self");
            }
            if (!listings.exists(actor.clientId(), account.id(), built.parentSku())) {
                throw new WorkspaceException("parent.missing");
            }
        } else if (built.explicitRole() == ListingRole.CHILD) {
            throw new WorkspaceException("parent.required");
        }
        return ListingContent.role(built, listings.parentsReferenced(actor.clientId(), account.id(), List.of(sku)).contains(sku));
    }

    /**
     * The columns after a form was posted: a column that was posted and is not read only takes the posted value (a blank one is dropped, as
     * the import drops a blank cell); a value that only differs by the line break a browser sends (CR LF for LF) is the stored value; every
     * other column is as it was. The result is in the order of the template.
     */
    static Map<String, String> merge(Template template, Map<String, String> old, Layout layout, Map<String, String> posted) {
        Map<String, String> data = new LinkedHashMap<>(old);
        for (Field field : layout.all()) {
            if (field.readOnly() || !posted.containsKey(field.key())) {
                continue;
            }
            String value = lineBreaks(posted.get(field.key()));
            if (value.isBlank()) {
                data.remove(field.key());
            } else if (!old.containsKey(field.key()) || !lineBreaks(old.get(field.key())).equals(value)) {
                if (value.codePointCount(0, value.length()) > CELL_MAX) {
                    throw new WorkspaceException("value.too_long");
                }
                data.put(field.key(), value);
            }
        }
        return ListingContent.ordered(template, data);
    }

    private static String lineBreaks(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ------------------------------------------------------------------ create

    /** The template a new listing of this account is written in: the Etsy fields, or the newest Amazon bulk template. */
    public Template templateForNew(UUID clientId, SellerAccount account) {
        if (account.marketplace() == WorkspaceTypes.Marketplace.ETSY) {
            return templateService.ensureEtsy(clientId, account.id());
        }
        return templates.current(clientId, account.id(), TemplateKind.AMAZON_BULK).orElseThrow(() -> new WorkspaceException("template.none"));
    }

    /**
     * Creates a listing from the typed columns (source NEW, draft, the creator responsible). The SKU is required and unique in the account;
     * the lock of the account is taken, so two people typing the same SKU cannot both succeed.
     */
    @Transactional
    public Listing create(Actor actor, UUID accountId, Map<String, String> posted) {
        SellerAccount account = accounts.find(actor.clientId(), accountId).orElseThrow(() -> new WorkspaceException("account.unknown"));
        if (!account.enabled()) {
            throw new WorkspaceException("account.disabled");
        }
        listings.lockAccount(accountId);
        Template template = templateForNew(actor.clientId(), account);
        Layout layout = layout(template, account, Map.of(), true);
        Map<String, String> data = merge(template, Map.of(), layout, posted);
        Built built = content.build(account.marketplace(), template, data);
        String sku = built.sku();
        if (sku.isEmpty()) {
            throw new WorkspaceException("sku.required");
        }
        if (sku.codePointCount(0, sku.length()) > ListingImportService.SKU_MAX) {
            throw new WorkspaceException("sku.long");
        }
        if (listings.exists(actor.clientId(), accountId, sku)) {
            throw new WorkspaceException("sku.duplicate");
        }
        if (!built.parentSku().isEmpty()) {
            if (built.parentSku().equals(sku)) {
                throw new WorkspaceException("parent.self");
            }
            if (!listings.exists(actor.clientId(), accountId, built.parentSku())) {
                throw new WorkspaceException("parent.missing");
            }
        } else if (built.explicitRole() == ListingRole.CHILD) {
            throw new WorkspaceException("parent.required");
        }
        ListingRole role = ListingContent.role(built, false);
        UUID id = UUID.randomUUID();
        Listing state = new Listing(id, actor.clientId(), accountId, template.id(), sku, built.parentSku().isEmpty() ? null : built.parentSku(), role,
                built.title(), data, built.fields(), ListingContent.hash(data, role), actor.accountId(), ListingStatus.DRAFT, ListingSource.NEW,
                null, 0, null, null, null);
        listings.insertAll(actor.clientId(), List.of(new Write(id, state, ChangeKind.NEW, actor.accountId(), 0)));
        events.publishEvent(new WorkspaceEvents.ListingSaved(actor.clientId(), id));
        log.info("Workspace listing {} of client {} created in account {}", id, actor.clientId(), accountId);
        return listings.find(actor.clientId(), id).orElseThrow();
    }
}
