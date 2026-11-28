package com.thanhdatpb.listingguard.api.workspace;

import com.thanhdatpb.listingguard.api.workspace.StandardFields.Built;
import com.thanhdatpb.listingguard.api.workspace.StandardTargets.Kind;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.CustomizationState;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.CustomizationView;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.CustomizationWrite;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.Existing;
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
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.TemplateKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cloning listings (ADR-062 §4): one listing, a parent with its children, or a selection, into the same seller account or another account of
 * the same marketplace. A clone is a draft of the person who clones it (source CLONE, {@code cloned_from} the original), with new SKUs. The
 * whole batch is written in one transaction under the target account's lock, after the SKUs were checked against the account and against
 * each other, and the background audit is told through the event the import uses.
 *
 * <p>Into another account the columns are paired by what they are: the n-th column of the source given the standard field X goes to the
 * n-th column of the target given X; a column no field was given goes to the column of the target with the same header; whatever has no
 * partner and holds a value in the listings is listed, and the person chooses a target column for it or leaves it out. The fixed Etsy
 * fields have the same columns everywhere, so an Etsy listing is copied as it is. The standard fields are built again from the new
 * columns by {@link ListingContent}, like the editor's.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
public class ListingCloner {

    private static final Logger log = LoggerFactory.getLogger(ListingCloner.class);
    /** The most listings one clone writes. */
    public static final int MAX = 500;

    public record Request(UUID targetAccountId, List<UUID> listingIds, boolean withChildren, SkuPlan sku, Map<String, String> unmapped,
                          boolean copyCustomization) {

        public Request {
            listingIds = List.copyOf(listingIds);
            sku = sku == null ? SkuPlan.same() : sku;
            unmapped = unmapped == null ? Map.of() : Map.copyOf(unmapped);
        }
    }

    /** One listing of the clone. {@code problems} are codes of {@code workspace.clone.problem.<code>}. */
    public record Item(UUID sourceId, String sourceSku, String newSku, String title, String role, String parentSku, List<String> problems) {
    }

    /** A source column and the target column it goes to. */
    public record Pair(String sourceKey, String sourceHeader, String targetKey, String targetHeader, String target) {
    }

    /** A source column with values and no partner; {@code chosen} is the target column the person gave it ("" leaves it out). */
    public record Unmapped(String sourceKey, String sourceHeader, String target, int filled, String chosen) {
    }

    public record TargetColumn(String key, String header, String target) {
    }

    /** Something the person should know before writing: a code of {@code workspace.clone.notice.<code>} and how many listings it is about. */
    public record Notice(String code, int count) {
    }

    public record Preview(SellerAccount source, SellerAccount target, boolean sameAccount, Template sourceTemplate, Template targetTemplate,
                          List<Item> items, List<Pair> pairs, List<Unmapped> unmapped, List<TargetColumn> free, int customizations,
                          List<Notice> notices, List<String> problems) {

        public boolean canWrite() {
            return problems.isEmpty() && !items.isEmpty() && items.stream().allMatch(i -> i.problems().isEmpty());
        }
    }

    private final WorkspaceAccounts accounts;
    private final WorkspaceTemplates templates;
    private final WorkspaceListings listings;
    private final TemplateService templateService;
    private final ListingContent content;
    private final StandardTargets targets;
    private final ApplicationEventPublisher events;

    public ListingCloner(WorkspaceAccounts accounts, WorkspaceTemplates templates, WorkspaceListings listings, TemplateService templateService,
                         ListingContent content, StandardTargets targets, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.templates = templates;
        this.listings = listings;
        this.templateService = templateService;
        this.content = content;
        this.targets = targets;
        this.events = events;
    }

    // ------------------------------------------------------------------ preview

    /** What the clone would write, with every problem; nothing is written. */
    public Preview preview(Actor actor, Request request) {
        return prepare(actor, request).preview;
    }

    /** The working state of one reading of a request. */
    private static final class Prepared {
        Preview preview;
        List<Listing> sources = List.of();
        Map<UUID, String> newSkus = new HashMap<>();
        Map<UUID, String> newParents = new HashMap<>();
        /** source column key to target column key, for every column that goes somewhere */
        Map<String, String> columnMap = new LinkedHashMap<>();
        boolean etsy;
    }

    private Prepared prepare(Actor actor, Request request) {
        Prepared prep = new Prepared();
        List<String> problems = new ArrayList<>();
        Map<UUID, Listing> loaded = listings.findMany(actor.clientId(), new LinkedHashSet<>(request.listingIds()));
        if (request.listingIds().isEmpty() || loaded.size() != new HashSet<>(request.listingIds()).size()) {
            throw new WorkspaceException("clone.listing.unknown");
        }
        List<Listing> sources = new ArrayList<>(loaded.values());
        UUID sourceAccountId = sources.get(0).accountId();
        UUID sourceTemplateId = sources.get(0).templateId();
        if (sources.stream().anyMatch(l -> !l.accountId().equals(sourceAccountId))) {
            throw new WorkspaceException("clone.mixed.account");
        }
        if (request.withChildren()) {
            Set<UUID> have = new HashSet<>(loaded.keySet());
            for (Listing parent : new ArrayList<>(sources)) {
                for (Listing child : listings.childrenWhole(actor.clientId(), sourceAccountId, parent.sku())) {
                    if (have.add(child.id())) {
                        sources.add(child);
                    }
                }
            }
        }
        if (sources.size() > MAX) {
            throw new WorkspaceException("clone.too_many");
        }
        if (sources.stream().anyMatch(l -> !l.templateId().equals(sourceTemplateId))) {
            throw new WorkspaceException("clone.mixed.template");
        }
        // the family stays together: a parent, then its children, by SKU
        sources.sort(Comparator.<Listing, String>comparing(l -> l.parentSku() != null ? l.parentSku() : l.sku())
                .thenComparing(l -> l.parentSku() != null).thenComparing(Listing::sku));
        prep.sources = sources;

        SellerAccount source = accounts.find(actor.clientId(), sourceAccountId).orElseThrow(() -> new WorkspaceException("account.unknown"));
        SellerAccount target = accounts.find(actor.clientId(), request.targetAccountId()).orElseThrow(() -> new WorkspaceException("account.unknown"));
        if (!target.enabled()) {
            throw new WorkspaceException("account.disabled");
        }
        if (target.marketplace() != source.marketplace()) {
            throw new WorkspaceException("clone.marketplace");
        }
        boolean same = source.id().equals(target.id());
        Template sourceTemplate = templates.find(actor.clientId(), sourceTemplateId).orElseThrow(() -> new WorkspaceException("template.none"));
        Template targetTemplate;
        if (same) {
            targetTemplate = sourceTemplate;
        } else if (sourceTemplate.kind() == TemplateKind.ETSY_FIELDS) {
            targetTemplate = templates.current(actor.clientId(), target.id(), TemplateKind.ETSY_FIELDS).orElse(sourceTemplate);
        } else {
            targetTemplate = templates.current(actor.clientId(), target.id(), sourceTemplate.kind()).orElse(null);
            if (targetTemplate == null) {
                throw new WorkspaceException("clone.target.no_template");
            }
        }
        prep.etsy = sourceTemplate.kind() == TemplateKind.ETSY_FIELDS;

        // the columns
        List<Pair> pairs = new ArrayList<>();
        List<Unmapped> unmapped = new ArrayList<>();
        List<TargetColumn> free = new ArrayList<>();
        if (same || prep.etsy) {
            for (TemplateColumn column : sourceTemplate.columns()) {
                pairs.add(new Pair(column.key(), column.header(), column.key(), column.header(), sourceTemplate.mapping().targets().get(column.key())));
            }
        } else {
            pairColumns(sourceTemplate, targetTemplate, sources, request.unmapped(), pairs, unmapped, free, problems);
        }
        for (Pair pair : pairs) {
            prep.columnMap.put(pair.sourceKey(), pair.targetKey());
        }
        for (Unmapped u : unmapped) {
            if (!u.chosen().isEmpty()) {
                prep.columnMap.put(u.sourceKey(), u.chosen());
            }
        }

        // the SKUs
        List<Item> items = new ArrayList<>();
        List<String> skus = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            skus.add(request.sku().apply(sources.get(i).id(), sources.get(i).sku(), i));
        }
        Map<String, Existing> taken = listings.existing(actor.clientId(), target.id(), skus.stream().filter(s -> !s.isEmpty()).toList());
        Map<String, Integer> times = new HashMap<>();
        skus.forEach(s -> times.merge(s, 1, Integer::sum));
        Map<String, UUID> newBySource = new HashMap<>();
        Set<String> sourceSkus = new HashSet<>();
        for (int i = 0; i < sources.size(); i++) {
            sourceSkus.add(sources.get(i).sku());
            newBySource.put(sources.get(i).sku(), sources.get(i).id());
        }
        int dropped = 0;
        for (int i = 0; i < sources.size(); i++) {
            Listing l = sources.get(i);
            String sku = skus.get(i);
            List<String> itemProblems = new ArrayList<>();
            if (sku.isEmpty()) {
                itemProblems.add("sku.empty");
            } else {
                if (sku.codePointCount(0, sku.length()) > ListingImportService.SKU_MAX) {
                    itemProblems.add("sku.long");
                }
                if (taken.containsKey(sku)) {
                    itemProblems.add("sku.taken");
                }
                if (times.get(sku) > 1) {
                    itemProblems.add("sku.repeated");
                }
            }
            prep.newSkus.put(l.id(), sku);
            // the parent: the new parent when it is cloned too; the same parent when it stays in the same account; else the link is dropped
            String parent = null;
            if (l.parentSku() != null) {
                if (sourceSkus.contains(l.parentSku())) {
                    parent = skus.get(indexOfSku(sources, l.parentSku()));
                } else if (same && listings.exists(actor.clientId(), source.id(), l.parentSku())) {
                    parent = l.parentSku();
                } else {
                    dropped++;
                }
            }
            if (parent != null) {
                prep.newParents.put(l.id(), parent);
            }
            items.add(new Item(l.id(), l.sku(), sku, l.title(), l.role().name(), parent, List.copyOf(itemProblems)));
        }
        List<Notice> notices = new ArrayList<>();
        if (dropped > 0) {
            notices.add(new Notice("parent_dropped", dropped));
        }
        int customizations = 0;
        if (request.copyCustomization() && source.marketplace() == Marketplace.AMAZON) {
            for (Listing l : sources) {
                if (listings.customization(actor.clientId(), source.id(), l.sku()).isPresent()) {
                    customizations++;
                }
            }
            if (!same && customizations > 0 && templates.current(actor.clientId(), target.id(), TemplateKind.AMAZON_CUSTOM).isEmpty()) {
                notices.add(new Notice("customization_skipped", customizations));
                customizations = 0;
            }
        }
        prep.preview = new Preview(source, target, same, sourceTemplate, targetTemplate, List.copyOf(items), List.copyOf(pairs), List.copyOf(unmapped),
                List.copyOf(free), customizations, List.copyOf(notices), List.copyOf(problems));
        return prep;
    }

    private static int indexOfSku(List<Listing> sources, String sku) {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).sku().equals(sku)) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, List<TemplateColumn>> byTarget(Template template) {
        Map<String, List<TemplateColumn>> by = new LinkedHashMap<>();
        for (TemplateColumn column : template.columns()) {
            String target = template.mapping().targets().get(column.key());
            if (target != null && !target.isEmpty()) {
                by.computeIfAbsent(target, k -> new ArrayList<>()).add(column);
            }
        }
        return by;
    }

    /**
     * Pairs the columns of two Amazon templates by the standard field they were given (the n-th with the n-th), then the columns no field
     * was given by header (ignoring case and the blanks at both ends).
     */
    public static List<Pair> pairs(Template source, Template target) {
        List<Pair> pairs = new ArrayList<>();
        Set<String> targetDone = new HashSet<>();
        Map<String, List<TemplateColumn>> from = byTarget(source);
        Map<String, List<TemplateColumn>> to = byTarget(target);
        Set<String> sourceDone = new HashSet<>();
        from.forEach((field, columns) -> {
            List<TemplateColumn> partners = to.getOrDefault(field, List.of());
            for (int n = 0; n < Math.min(columns.size(), partners.size()); n++) {
                TemplateColumn s = columns.get(n);
                TemplateColumn t = partners.get(n);
                pairs.add(new Pair(s.key(), s.header(), t.key(), t.header(), field));
                sourceDone.add(s.key());
                targetDone.add(t.key());
            }
        });
        for (TemplateColumn s : source.columns()) {
            if (sourceDone.contains(s.key()) || source.mapping().targets().get(s.key()) != null || s.header().isBlank()) {
                continue;
            }
            for (TemplateColumn t : target.columns()) {
                if (!targetDone.contains(t.key()) && target.mapping().targets().get(t.key()) == null
                        && t.header().strip().equalsIgnoreCase(s.header().strip())) {
                    pairs.add(new Pair(s.key(), s.header(), t.key(), t.header(), null));
                    sourceDone.add(s.key());
                    targetDone.add(t.key());
                    break;
                }
            }
        }
        return pairs;
    }

    /**
     * The pairs of two templates, then the source columns that hold a value in some listing and have no partner ({@code unmapped}, with the
     * target column the person chose for each), and the target columns nobody goes to ({@code free}).
     */
    private void pairColumns(Template source, Template target, List<Listing> listings, Map<String, String> choices, List<Pair> pairs,
                             List<Unmapped> unmapped, List<TargetColumn> free, List<String> problems) {
        pairs.addAll(pairs(source, target));
        Set<String> sourceDone = new HashSet<>();
        Set<String> targetDone = new HashSet<>();
        pairs.forEach(p -> {
            sourceDone.add(p.sourceKey());
            targetDone.add(p.targetKey());
        });
        List<TargetColumn> open = new ArrayList<>();
        for (TemplateColumn t : target.columns()) {
            if (!targetDone.contains(t.key())) {
                open.add(new TargetColumn(t.key(), t.header().isBlank() ? t.key() : t.header(), target.mapping().targets().get(t.key())));
            }
        }
        Set<String> chosenTargets = new HashSet<>();
        for (TemplateColumn s : source.columns()) {
            if (sourceDone.contains(s.key())) {
                continue;
            }
            int filled = (int) listings.stream().filter(l -> l.data().containsKey(s.key())).count();
            if (filled == 0) {
                continue;
            }
            String wanted = choices.getOrDefault(s.key(), "");
            boolean usable = wanted.isEmpty() || open.stream().anyMatch(o -> o.key().equals(wanted)) && chosenTargets.add(wanted);
            if (!usable) {
                problems.add("choice.invalid");
            }
            unmapped.add(new Unmapped(s.key(), s.header().isBlank() ? s.key() : s.header(), source.mapping().targets().get(s.key()), filled,
                    usable ? wanted : ""));
        }
        free.addAll(open);
    }

    // ------------------------------------------------------------------ write

    /**
     * Writes the clone: every listing a draft of {@code actor}, with the standard fields built again, in one transaction under the lock of
     * the target account. The SKUs and the columns are checked again under the lock.
     */
    @Transactional
    public List<Listing> clone(Actor actor, Request request) {
        SellerAccount targetAccount = accounts.find(actor.clientId(), request.targetAccountId()).orElseThrow(() -> new WorkspaceException("account.unknown"));
        listings.lockAccount(targetAccount.id());
        Prepared prep = prepare(actor, request);
        if (!prep.preview.canWrite()) {
            throw new WorkspaceException("clone.invalid");
        }
        Preview preview = prep.preview;
        Template template = preview.targetTemplate();
        if (prep.etsy && !preview.sameAccount()) {
            template = templateService.ensureEtsy(actor.clientId(), preview.target().id());
        }
        Template destination = template;
        Marketplace marketplace = preview.target().marketplace();
        Set<String> newParentSkus = new HashSet<>(prep.newParents.values());
        List<Write> writes = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        Map<UUID, String> newSkuOfSource = prep.newSkus;
        for (Listing source : prep.sources) {
            String sku = newSkuOfSource.get(source.id());
            Map<String, String> data = translate(preview, destination, source, prep, sku);
            Built built = content.build(marketplace, destination, data);
            ListingRole role = ListingContent.role(built, newParentSkus.contains(sku));
            UUID id = UUID.randomUUID();
            Listing state = new Listing(id, actor.clientId(), preview.target().id(), destination.id(), sku,
                    built.parentSku().isEmpty() ? null : built.parentSku(), role, built.title(), data, built.fields(), ListingContent.hash(data, role),
                    actor.accountId(), ListingStatus.DRAFT, ListingSource.CLONE, source.id(), 0, null, null, null);
            writes.add(new Write(id, state, ChangeKind.CLONE, actor.accountId(), 0));
            ids.add(id);
        }
        listings.insertAll(actor.clientId(), writes);
        int custom = preview.customizations() > 0 ? copyCustomizations(actor, preview, prep, destination) : 0;
        events.publishEvent(new WorkspaceEvents.ListingsImported(actor.clientId(), preview.target().id(), ids, actor.accountId()));
        log.info("Workspace clone into account {} of client {}: {} listings, {} customization rows", preview.target().id(), actor.clientId(),
                ids.size(), custom);
        return new ArrayList<>(listings.findMany(actor.clientId(), ids).values());
    }

    /** The columns of a source listing in the target template: paired and chosen columns moved, the SKU and the parent set, the owner left out. */
    private Map<String, String> translate(Preview preview, Template destination, Listing source, Prepared prep, String newSku) {
        Map<String, String> data = new LinkedHashMap<>();
        if (prep.etsy) {
            data.putAll(source.data());
        } else {
            source.data().forEach((key, value) -> {
                String to = prep.columnMap.get(key);
                if (to != null) {
                    data.put(to, value);
                }
            });
        }
        Marketplace marketplace = preview.target().marketplace();
        String skuKey = null;
        String parentKey = null;
        String parentChildKey = null;
        List<String> ownerKeys = new ArrayList<>();
        for (StandardFields.ColumnUse use : content.uses(destination, data)) {
            Kind kind = use.target() == null ? null : targets.find(marketplace, use.target()).map(StandardTargets.Target::kind).orElse(null);
            if (kind == Kind.SKU) {
                skuKey = use.dataKey();
            } else if (kind == Kind.PARENT_SKU) {
                parentKey = use.dataKey();
            } else if (kind == Kind.PARENT_CHILD) {
                parentChildKey = use.dataKey();
            } else if (kind == Kind.OWNER) {
                ownerKeys.add(use.dataKey());
            }
        }
        if (skuKey == null) {
            skuKey = prep.etsy ? "sku" : skuKeyOf(destination);
        }
        data.put(skuKey, newSku);
        ownerKeys.forEach(data::remove);
        String parent = prep.newParents.get(source.id());
        if (source.parentSku() != null) {
            if (parent != null && (parentKey != null || prep.etsy)) {
                data.put(parentKey == null ? "parent_sku" : parentKey, parent);
            } else {
                if (parentKey != null) {
                    data.remove(parentKey);
                }
                if (parentChildKey != null && targets.isChildValue(data.get(parentChildKey))) {
                    data.remove(parentChildKey);
                }
            }
        }
        return ListingContent.ordered(destination, data);
    }

    private String skuKeyOf(Template template) {
        return template.mapping().targets().entrySet().stream().filter(e -> "sku".equals(e.getValue())).map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> new WorkspaceException("mapping.sku.required"));
    }

    /** Copies the Amazon Custom row of each source SKU to its new SKU; into another account by header, to the account's own custom template. */
    private int copyCustomizations(Actor actor, Preview preview, Prepared prep, Template destination) {
        UUID clientId = actor.clientId();
        Template targetCustom = null;
        if (!preview.sameAccount()) {
            targetCustom = templates.current(clientId, preview.target().id(), TemplateKind.AMAZON_CUSTOM).orElse(null);
            if (targetCustom == null) {
                return 0;
            }
        }
        List<CustomizationWrite> writes = new ArrayList<>();
        Map<String, CustomizationState> current = listings.customizations(clientId, preview.target().id(),
                new ArrayList<>(prep.newSkus.values()));
        for (Listing source : prep.sources) {
            CustomizationView row = listings.customization(clientId, preview.source().id(), source.sku()).orElse(null);
            if (row == null) {
                continue;
            }
            Template rowTemplate = templates.find(clientId, row.templateId()).orElseThrow();
            Template into = targetCustom != null ? targetCustom : rowTemplate;
            Map<String, String> data = new LinkedHashMap<>();
            if (targetCustom == null) {
                data.putAll(row.data());
            } else {
                for (TemplateColumn from : rowTemplate.columns()) {
                    String value = row.data().get(from.key());
                    if (value == null) {
                        continue;
                    }
                    boolean sku = "sku".equals(rowTemplate.mapping().targets().get(from.key()));
                    for (TemplateColumn to : into.columns()) {
                        boolean toSku = "sku".equals(into.mapping().targets().get(to.key()));
                        if (sku ? toSku : !toSku && to.header().strip().equalsIgnoreCase(from.header().strip())) {
                            data.put(to.key(), value);
                            break;
                        }
                    }
                }
            }
            String skuKey = into.mapping().targets().entrySet().stream().filter(e -> "sku".equals(e.getValue())).map(Map.Entry::getKey).findFirst()
                    .orElse(null);
            if (skuKey == null) {
                continue;
            }
            String sku = prep.newSkus.get(source.id());
            data.put(skuKey, sku);
            writes.add(new CustomizationWrite(sku, into.id(), data, WorkspaceJson.contentHash(new TreeMap<>(data), "CUSTOM"), current.get(sku)));
        }
        listings.writeCustomizations(clientId, preview.target().id(), writes);
        return writes.size();
    }

    /** The accounts a selection can be cloned into: enabled accounts of the same marketplace, in the order the workspace lists them. */
    public List<SellerAccount> targetsFor(UUID clientId, Marketplace marketplace) {
        return accounts.list(clientId).stream().filter(a -> a.enabled() && a.marketplace() == marketplace)
                .sorted(Comparator.comparing(a -> a.name().toLowerCase(Locale.ROOT))).toList();
    }
}
