package com.thanhdatpb.listingguard.api.console.workspace;

import com.thanhdatpb.listingguard.api.console.ConsoleAccountRoles;
import com.thanhdatpb.listingguard.api.console.ConsoleDates;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.console.editor.EditorLimits;
import com.thanhdatpb.listingguard.api.ruleset.RulesetLimitsService;
import com.thanhdatpb.listingguard.api.ruleset.RulesetNotFoundException;
import com.thanhdatpb.listingguard.api.workspace.ListingCloner;
import com.thanhdatpb.listingguard.api.workspace.ListingComparison;
import com.thanhdatpb.listingguard.api.workspace.ListingEditor;
import com.thanhdatpb.listingguard.api.workspace.ListingHistory;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService;
import com.thanhdatpb.listingguard.api.workspace.SkuPlan;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccounts;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.HistoryEntry;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings.HistorySnapshot;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Template;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.WorkspaceException;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceOverrides;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceProperties;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTemplates;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The editor, the new-listing form, the clone, the history and the change of status of the workspace (ADR-062). Like the other workspace
 * pages it exists only while {@code listing-guard.workspace.enabled} is true (without it every path below is a 404), reads the role from the
 * database at each request, answers 404 for a listing or an account of another client, is {@code no-store} and prints every value with
 * {@code th:text}. Every page works without a script; the counters of the editor are the console's {@code editor.js}.
 */
@Controller
@RequestMapping("/console/workspace")
@ConditionalOnExpression("${listing-guard.console.enabled:false} && ${listing-guard.workspace.enabled:false}")
public class ConsoleWorkspaceEditorController {

    /** One input of the editor. {@code body} is what goes between the tags of a textarea (a first line break is added: a browser drops one). */
    public record FieldView(String key, String name, String id, String header, String value, String body, boolean readOnly, boolean multiline,
                            boolean untouched, String counterMode, Integer limit, String limitText) {
    }

    public record GroupView(String target, String label, String hint, List<FieldView> fields) {
    }

    /** A row of the table of a version conflict: what the column was when the page was opened, what the person typed and what is stored now. */
    public record ConflictRow(String header, String base, String yours, String theirs, boolean yoursChanged, boolean theirsChanged) {
    }

    public record VersionRow(long version, String kind, String status, String by, String when, boolean current, boolean restorable) {
    }

    public record ChangeRow(String header, String target, String before, String after) {
    }

    public record CloneItemRow(UUID id, String sku, String newSku, String title, String role, List<String> problems, String parent) {
    }

    public record StatusRow(UUID id, String sku, String title, String status, List<String> blocks, String state, String openedReason,
                            String openedBy, String result, boolean mayGo, boolean trademarkUnchecked, String trademarkWhy,
                            boolean catalogUnchecked) {
    }

    private final WorkspaceAccess access;
    private final WorkspaceAccounts accounts;
    private final WorkspaceTemplates templates;
    private final WorkspaceListings listings;
    private final ListingEditor editor;
    private final ListingCloner cloner;
    private final ListingHistory history;
    private final ListingComparison comparison;
    private final ListingStatusService statusService;
    private final WorkspaceOverrides overrides;
    private final ConsoleAccountRoles roles;
    private final ConsoleMessages messages;
    private final RulesetLimitsService rulesetLimits;
    private final ObjectProvider<WorkspaceAuditViews> auditViews;
    private final WorkspaceProperties properties;
    private WorkspaceReleaseViews releaseViews;

    @org.springframework.beans.factory.annotation.Autowired
    void setRelease(WorkspaceReleaseViews views) {
        this.releaseViews = views;
    }

    public ConsoleWorkspaceEditorController(WorkspaceAccess access, WorkspaceAccounts accounts, WorkspaceTemplates templates,
                                            WorkspaceListings listings, ListingEditor editor, ListingCloner cloner, ListingHistory history,
                                            ListingComparison comparison, ListingStatusService statusService, WorkspaceOverrides overrides,
                                            ConsoleAccountRoles roles, ConsoleMessages messages, RulesetLimitsService rulesetLimits,
                                            ObjectProvider<WorkspaceAuditViews> auditViews, WorkspaceProperties properties) {
        this.access = access;
        this.accounts = accounts;
        this.templates = templates;
        this.listings = listings;
        this.editor = editor;
        this.cloner = cloner;
        this.history = history;
        this.comparison = comparison;
        this.statusService = statusService;
        this.overrides = overrides;
        this.roles = roles;
        this.messages = messages;
        this.rulesetLimits = rulesetLimits;
        this.auditViews = auditViews;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ helpers

    private Actor actor(ConsoleUser user) {
        return access.actor(user).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private static UUID uuid(String text) {
        try {
            return text == null || text.isBlank() ? null : UUID.fromString(text.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Listing listing(Actor actor, UUID id) {
        return listings.find(actor.clientId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private SellerAccount account(Actor actor, UUID id) {
        return accounts.find(actor.clientId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The values of the form, by column key: the parameters {@code v.<key>}. */
    private static Map<String, String> posted(Map<String, String> params) {
        Map<String, String> posted = new LinkedHashMap<>();
        params.forEach((name, value) -> {
            if (name.startsWith("v.") && name.length() > 2 && value != null) {
                posted.put(name.substring(2), value);
            }
        });
        return posted;
    }

    private static long version(Map<String, String> params) {
        try {
            return Long.parseLong(params.getOrDefault("version", "").strip());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    // ------------------------------------------------------------------ the editor

    @GetMapping("/listings/{id}/edit")
    public String edit(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, Model model, HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        Listing listing = listing(actor, id);
        fill(model, actor, listing, Map.of(), null, null);
        return "console/workspace-edit";
    }

    @PostMapping("/listings/{id}/edit")
    public String save(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, @RequestParam Map<String, String> params, Model model,
                       RedirectAttributes redirect, HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        Listing listing = listing(actor, id);
        Map<String, String> typed = posted(params);
        try {
            ListingEditor.Saved saved = editor.save(actor, id, version(params), typed);
            boolean released = saved.listing().status() == com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus.EXPORTED || saved.listing().status() == com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus.LIVE;
            redirect.addFlashAttribute("notice", saved.changed() ? messages.get(saved.readyReverted() ? "workspace.edit.saved.draft"
                    : released ? "workspace.edit.saved.reexport" : "workspace.edit.saved")
                    : messages.get("workspace.edit.unchanged"));
            return "redirect:/console/workspace/listings/" + id + "/edit";
        } catch (ListingEditor.VersionConflict conflict) {
            response.setStatus(HttpStatus.CONFLICT.value());
            fill(model, actor, conflict.current(), typed, null, conflictRows(actor, conflict.current(), version(params), typed));
            return "console/workspace-edit";
        } catch (WorkspaceException e) {
            if ("forbidden".equals(e.code())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            fill(model, actor, listing, typed, new WorkspaceLabels(messages).error(e.code()), null);
            return "console/workspace-edit";
        }
    }

    private List<ConflictRow> conflictRows(Actor actor, Listing current, long base, Map<String, String> typed) {
        Template template = templates.find(actor.clientId(), current.templateId()).orElseThrow();
        SellerAccount account = account(actor, current.accountId());
        Map<String, String> was = history.snapshot(actor.clientId(), current.id(), base).map(HistorySnapshot::data).orElse(null);
        ListingEditor.Layout layout = editor.layout(template, account, current.data(), false);
        List<ConflictRow> rows = new ArrayList<>();
        for (ListingEditor.Field field : layout.all()) {
            if (field.readOnly()) {
                continue;
            }
            String theirs = current.data().getOrDefault(field.key(), "");
            String yours = typed.containsKey(field.key()) ? typed.get(field.key()).replace("\r\n", "\n") : theirs;
            String before = was == null ? null : was.getOrDefault(field.key(), "");
            boolean theirsChanged = before != null && !before.equals(theirs);
            boolean yoursChanged = before == null ? !yours.equals(theirs) : !yours.equals(before);
            if (theirsChanged || yoursChanged) {
                rows.add(new ConflictRow(field.header().isBlank() ? field.key() : field.header(), before, yours, theirs, yoursChanged, theirsChanged));
            }
        }
        return rows;
    }

    /** What the editor page needs. {@code typed} are values the person typed that the page shows instead of the stored ones. */
    private void fill(Model model, Actor actor, Listing listing, Map<String, String> typed, String error, List<ConflictRow> conflict) {
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        Template template = templates.find(actor.clientId(), listing.templateId()).orElseThrow();
        SellerAccount account = account(actor, listing.accountId());
        Map<UUID, String> emails = roles.emails(actor.clientId());
        boolean canEdit = access.canEdit(actor, listing.ownerAccountId());
        ListingComparison.Comparison compared = comparison.of(actor.clientId(), listing, template, account).orElse(null);
        ListingEditor.Layout layout = editor.layout(template, account, listing.data(), false);
        EditorLimits limits = limits(account, listing);
        List<GroupView> groups = groups(layout, typed, compared, limits, labels, account, canEdit);
        List<FieldView> passthrough = fieldViews("p", layout.passthrough(), typed, compared, null, canEdit, 0);
        model.addAttribute("creating", false);
        model.addAttribute("listing", listing);
        model.addAttribute("account", account);
        model.addAttribute("marketplaceLabel", labels.marketplace(account.marketplace()));
        model.addAttribute("statusLabel", labels.status(listing.status()));
        model.addAttribute("roleLabel", labels.role(listing.role()));
        model.addAttribute("ownerEmail", listing.ownerAccountId() == null ? null : emails.get(listing.ownerAccountId()));
        model.addAttribute("groups", groups);
        model.addAttribute("passthrough", passthrough);
        model.addAttribute("canEdit", canEdit);
        model.addAttribute("admin", actor.admin());
        releaseViews.fill(model, actor, listing, account);
        model.addAttribute("error", error != null ? error : model.getAttribute("error"));
        model.addAttribute("conflict", conflict);
        model.addAttribute("compared", compared);
        model.addAttribute("comparedFrom", compared == null ? null : compared.original());
        model.addAttribute("sameTitleOthers", compared == null ? List.of() : compared.sameAccount());
        model.addAttribute("isParent", listing.role() == ListingRole.PARENT);
        model.addAttribute("hasChildren", !listings.children(actor.clientId(), listing.accountId(), listing.sku()).isEmpty());
        model.addAttribute("newerTemplate", templates.current(actor.clientId(), listing.accountId(), template.kind())
                .filter(t -> t.version() > template.version()).isPresent());
        WorkspaceAuditViews audit = auditViews.getIfAvailable();
        if (audit != null) {
            model.addAttribute("auditOn", true);
            model.addAttribute("audit", audit.detail(actor.clientId(), listing).orElse(null));
        }
        model.addAttribute("limitsKnown", limits != null);
    }

    private EditorLimits limits(SellerAccount account, Listing listing) {
        try {
            String productType = listing.fields().path("product_type").asText("");
            return EditorLimits.of(rulesetLimits.limits(account.marketplace().name(), productType).limits());
        } catch (RulesetNotFoundException e) {
            return null;
        }
    }

    private List<GroupView> groups(ListingEditor.Layout layout, Map<String, String> typed, ListingComparison.Comparison compared, EditorLimits limits,
                                   WorkspaceLabels labels, SellerAccount account, boolean canEdit) {
        List<GroupView> views = new ArrayList<>();
        int offset = 0;
        for (ListingEditor.Group group : layout.groups()) {
            String hint = hint(group.target(), limits);
            views.add(new GroupView(group.target(), labels.target(group.target()), hint,
                    fieldViews("g" + views.size(), group.fields(), typed, compared, new Counter(group.target(), limits), canEdit, offset)));
            offset += group.fields().size();
        }
        return views;
    }

    private record Counter(String target, EditorLimits limits) {
    }

    /** What the ruleset says about a field as a whole (how many bullets, how long a tag), or null. */
    private String hint(String target, EditorLimits limits) {
        if (limits == null) {
            return null;
        }
        return switch (target) {
            case "bullet_points" -> limits.bulletCountMax() == null ? null : messages.get("editor.bullets.counter.count", limits.bulletCountMax());
            case "tags" -> limits.tagCountMax() == null && limits.tagLengthMax() == null ? null
                    : messages.get("workspace.edit.tags.hint", limits.tagCountMax() == null ? "-" : limits.tagCountMax(),
                    limits.tagLengthMax() == null ? "-" : limits.tagLengthMax());
            default -> null;
        };
    }

    private List<FieldView> fieldViews(String prefix, List<ListingEditor.Field> fields, Map<String, String> typed, ListingComparison.Comparison compared,
                                       Counter counter, boolean canEdit, int offset) {
        List<FieldView> views = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            ListingEditor.Field f = fields.get(i);
            String value = typed.containsKey(f.key()) && !f.readOnly() ? typed.get(f.key()) : f.value();
            String mode = "";
            Integer limit = null;
            String text = null;
            if (counter != null) {
                switch (counter.target()) {
                    case "title" -> {
                        mode = "chars";
                        limit = counter.limits() == null ? null : counter.limits().titleMax();
                    }
                    case "bullet_points" -> {
                        mode = "chars";
                        limit = counter.limits() == null ? null : counter.limits().bulletLengthMax();
                    }
                    case "search_terms" -> {
                        mode = "bytes";
                        limit = counter.limits() == null ? null : counter.limits().searchTermsMaxBytes();
                    }
                    case "description" -> mode = "chars";
                    default -> mode = "";
                }
                if (!mode.isEmpty() && limit != null) {
                    text = messages.get("bytes".equals(mode) ? "editor.counter.limit.bytes" : "editor.counter.limit.chars", limit);
                }
            }
            boolean untouched = compared != null && compared.untouched().contains(f.key());
            views.add(new FieldView(f.key(), "v." + f.key(), prefix + "-" + (offset + i), f.header().isBlank() ? f.key() : f.header(), value,
                    "\n" + value, f.readOnly() || !canEdit, f.multiline() || value.indexOf('\n') >= 0, untouched, mode, limit, text));
        }
        return views;
    }

    // ------------------------------------------------------------------ new listing

    @GetMapping("/new")
    public String start(@AuthenticationPrincipal ConsoleUser user, @RequestParam(name = "account", required = false) String accountId, Model model,
                        HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        UUID id = uuid(accountId);
        if (id == null) {
            model.addAttribute("choosing", true);
            model.addAttribute("sellerAccounts", accounts.list(actor.clientId()).stream().filter(SellerAccount::enabled).toList());
            return "console/workspace-new";
        }
        fillNew(model, actor, id, Map.of(), null);
        return "console/workspace-edit";
    }

    @PostMapping("/new")
    public String create(@AuthenticationPrincipal ConsoleUser user, @RequestParam Map<String, String> params, Model model, RedirectAttributes redirect,
                         HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        UUID accountId = uuid(params.get("account"));
        if (accountId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        account(actor, accountId);
        Map<String, String> typed = posted(params);
        try {
            Listing created = editor.create(actor, accountId, typed);
            redirect.addFlashAttribute("notice", messages.get("workspace.new.created"));
            return "redirect:/console/workspace/listings/" + created.id() + "/edit";
        } catch (WorkspaceException e) {
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            fillNew(model, actor, accountId, typed, new WorkspaceLabels(messages).error(e.code()));
            return "console/workspace-edit";
        }
    }

    private void fillNew(Model model, Actor actor, UUID accountId, Map<String, String> typed, String error) {
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        SellerAccount account = account(actor, accountId);
        if (!account.enabled()) {
            error = labels.error("account.disabled");
        }
        List<GroupView> groups = List.of();
        List<FieldView> passthrough = List.of();
        try {
            Template template = editor.templateForNew(actor.clientId(), account);
            ListingEditor.Layout layout = editor.layout(template, account, Map.of(), true);
            EditorLimits limits;
            try {
                limits = EditorLimits.of(rulesetLimits.limits(account.marketplace().name(), "").limits());
            } catch (RulesetNotFoundException e) {
                limits = null;
            }
            groups = groups(layout, typed, null, limits, labels, account, true);
            passthrough = fieldViews("p", layout.passthrough(), typed, null, null, true, 0);
            model.addAttribute("template", template);
        } catch (WorkspaceException e) {
            error = labels.error(e.code());
        }
        model.addAttribute("creating", true);
        model.addAttribute("account", account);
        model.addAttribute("marketplaceLabel", labels.marketplace(account.marketplace()));
        model.addAttribute("groups", groups);
        model.addAttribute("passthrough", passthrough);
        model.addAttribute("canEdit", error == null || !groups.isEmpty());
        model.addAttribute("error", error);
        model.addAttribute("conflict", null);
        model.addAttribute("compared", null);
        model.addAttribute("sameTitleOthers", List.of());
    }

    // ------------------------------------------------------------------ clone

    /** The clone page: from the list (several listings), the editor or the detail page (one). It writes nothing; it shows what would be written. */
    @PostMapping({"/clone", "/clone/preview"})
    public String clonePage(@AuthenticationPrincipal ConsoleUser user, @RequestParam MultiValueMap<String, String> params, Model model,
                            RedirectAttributes redirect, HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        List<UUID> ids = new ArrayList<>();
        params.getOrDefault("listing", List.of()).forEach(text -> {
            UUID id = uuid(text);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        });
        if (ids.isEmpty()) {
            redirect.addFlashAttribute("error", messages.get("workspace.clone.none"));
            return "redirect:/console/workspace";
        }
        try {
            ListingCloner.Request request = request(actor, ids, params);
            ListingCloner.Preview preview = cloner.preview(actor, request);
            fillClone(model, actor, ids, params, request, preview, labels);
            return "console/workspace-clone";
        } catch (WorkspaceException e) {
            if ("clone.listing.unknown".equals(e.code())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            redirect.addFlashAttribute("error", labels.error(e.code()));
            return "redirect:/console/workspace";
        }
    }

    @PostMapping("/clone/commit")
    public String cloneCommit(@AuthenticationPrincipal ConsoleUser user, @RequestParam MultiValueMap<String, String> params, Model model,
                              RedirectAttributes redirect, HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        List<UUID> ids = new ArrayList<>();
        params.getOrDefault("listing", List.of()).forEach(text -> {
            UUID id = uuid(text);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        });
        ListingCloner.Request request = request(actor, ids, params);
        try {
            List<Listing> created = cloner.clone(actor, request);
            redirect.addFlashAttribute("notice", messages.get("workspace.clone.done", created.size()));
            return created.size() == 1 ? "redirect:/console/workspace/listings/" + created.get(0).id() + "/edit"
                    : "redirect:/console/workspace?account=" + request.targetAccountId() + "&owner=me&status=DRAFT";
        } catch (WorkspaceException e) {
            if ("clone.listing.unknown".equals(e.code())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            try {
                fillClone(model, actor, ids, params, request, cloner.preview(actor, request), labels);
                model.addAttribute("error", labels.error(e.code()));
                return "console/workspace-clone";
            } catch (WorkspaceException again) {
                redirect.addFlashAttribute("error", labels.error(again.code()));
                return "redirect:/console/workspace";
            }
        }
    }

    private ListingCloner.Request request(Actor actor, List<UUID> ids, MultiValueMap<String, String> params) {
        UUID target = uuid(params.getFirst("target"));
        if (target == null) {
            Listing first = ids.isEmpty() ? null : listings.find(actor.clientId(), ids.get(0)).orElse(null);
            if (first == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            target = first.accountId();
        }
        Map<UUID, String> manual = new LinkedHashMap<>();
        Map<String, String> unmapped = new LinkedHashMap<>();
        params.forEach((name, values) -> {
            if (name.startsWith("sku.") && !values.isEmpty()) {
                UUID id = uuid(name.substring(4));
                if (id != null) {
                    manual.put(id, values.get(0));
                }
            } else if (name.startsWith("map.") && !values.isEmpty() && !values.get(0).isBlank()) {
                unmapped.put(name.substring(4), values.get(0));
            }
        });
        SkuPlan.Mode mode = "manual".equals(params.getFirst("skuMode")) ? SkuPlan.Mode.MANUAL : SkuPlan.Mode.PATTERN;
        Integer from = null;
        String number = params.getFirst("numberFrom");
        int width = 0;
        try {
            from = number == null || number.isBlank() ? null : Integer.valueOf(number.strip());
            String w = params.getFirst("numberWidth");
            width = w == null || w.isBlank() ? 0 : Integer.parseInt(w.strip());
        } catch (NumberFormatException e) {
            throw new WorkspaceException("params.invalid");
        }
        SkuPlan plan = new SkuPlan(mode, params.getFirst("prefix"), params.getFirst("suffix"), params.getFirst("find"), params.getFirst("replace"),
                from, width, manual);
        return new ListingCloner.Request(target, ids, params.containsKey("family"), plan, unmapped, params.containsKey("custom"));
    }

    private void fillClone(Model model, Actor actor, List<UUID> ids, MultiValueMap<String, String> params, ListingCloner.Request request,
                           ListingCloner.Preview preview, WorkspaceLabels labels) {
        List<CloneItemRow> items = new ArrayList<>();
        for (ListingCloner.Item item : preview.items()) {
            items.add(new CloneItemRow(item.sourceId(), item.sourceSku(), item.newSku(), item.title(), labels.role(ListingRole.valueOf(item.role())),
                    item.problems().stream().map(labels::cloneProblem).toList(), item.parentSku()));
        }
        List<String> notices = preview.notices().stream().map(n -> labels.cloneNotice(n.code(), n.count())).toList();
        List<String> problems = preview.problems().stream().map(labels::cloneProblem).toList();
        model.addAttribute("ids", ids);
        model.addAttribute("items", items);
        model.addAttribute("preview", preview);
        model.addAttribute("notices", notices);
        model.addAttribute("problems", problems);
        model.addAttribute("targets", cloner.targetsFor(actor.clientId(), preview.source().marketplace()));
        model.addAttribute("targetId", preview.target().id());
        model.addAttribute("manual", request.sku().mode() == SkuPlan.Mode.MANUAL);
        model.addAttribute("plan", request.sku());
        model.addAttribute("family", request.withChildren());
        model.addAttribute("custom", request.copyCustomization());
        model.addAttribute("amazon", preview.source().marketplace() == com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace.AMAZON);
        model.addAttribute("sameAccount", preview.sameAccount());
        model.addAttribute("canWrite", preview.canWrite());
        model.addAttribute("max", ListingCloner.MAX);
        model.addAttribute("chosen", request.unmapped());
        model.addAttribute("numberFrom", request.sku().numberFrom() == null ? "" : request.sku().numberFrom().toString());
        model.addAttribute("error", model.getAttribute("error"));
    }

    // ------------------------------------------------------------------ history

    @GetMapping("/listings/{id}/history")
    public String historyPage(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, @RequestParam Map<String, String> params, Model model,
                              HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        Listing listing = listing(actor, id);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        Template template = templates.find(actor.clientId(), listing.templateId()).orElseThrow();
        Map<UUID, String> emails = roles.emails(actor.clientId());
        List<HistoryEntry> entries = history.versions(actor.clientId(), id);
        boolean canEdit = access.canEdit(actor, listing.ownerAccountId());
        List<VersionRow> versions = new ArrayList<>();
        for (HistoryEntry entry : entries) {
            versions.add(new VersionRow(entry.version(), messages.get("workspace.change." + entry.kind().name().toLowerCase(java.util.Locale.ROOT)),
                    labels.status(entry.status()), entry.changedBy() == null ? null : emails.get(entry.changedBy()),
                    ConsoleDates.dateTime(messages, entry.changedAt()), entry.version() == listing.version(),
                    canEdit && entry.version() != listing.version()));
        }
        long to = number(params.get("to"), listing.version());
        long from = number(params.get("from"), entries.size() > 1 ? entries.get(1).version() : listing.version());
        List<ChangeRow> changes = List.of();
        boolean compare = params.containsKey("from") || params.containsKey("to");
        boolean unknown = false;
        if (compare) {
            HistorySnapshot a = history.snapshot(actor.clientId(), id, from).orElse(null);
            HistorySnapshot b = history.snapshot(actor.clientId(), id, to).orElse(null);
            if (a == null || b == null) {
                unknown = true;
            } else {
                changes = ListingHistory.diff(template, a.data(), b.data()).stream().map(c -> new ChangeRow(c.header(),
                        c.target() == null ? "" : labels.target(c.target()), c.before(), c.after())).toList();
            }
        }
        model.addAttribute("listing", listing);
        model.addAttribute("versions", versions);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("compare", compare && !unknown);
        model.addAttribute("unknownVersion", unknown);
        model.addAttribute("changes", changes);
        model.addAttribute("canEdit", canEdit);
        model.addAttribute("keep", properties.historyKeep());
        return "console/workspace-history";
    }

    private static long number(String text, long fallback) {
        try {
            return text == null || text.isBlank() ? fallback : Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @PostMapping("/listings/{id}/restore")
    public String restore(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, @RequestParam Map<String, String> params,
                          RedirectAttributes redirect) {
        Actor actor = actor(user);
        listing(actor, id);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        try {
            ListingHistory.Restored restored = history.restore(actor, id, number(params.get("restore"), -1), version(params));
            redirect.addFlashAttribute("notice", restored.dropped() > 0
                    ? messages.get("workspace.history.restored.dropped", restored.dropped()) : messages.get("workspace.history.restored"));
            return "redirect:/console/workspace/listings/" + id + "/edit";
        } catch (ListingEditor.VersionConflict e) {
            redirect.addFlashAttribute("error", messages.get("workspace.detail.conflict"));
        } catch (WorkspaceException e) {
            if ("forbidden".equals(e.code())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            redirect.addFlashAttribute("error", labels.error(e.code()));
        }
        return "redirect:/console/workspace/listings/" + id + "/history";
    }

    // ------------------------------------------------------------------ status

    /**
     * "Draft to ready to export" for a selection. {@code step=check} (the default) shows what the check says of each listing; {@code step=apply}
     * moves the ones that may go. An ADMIN may open the blocks of the selection with a reason.
     */
    @PostMapping("/status")
    public String status(@AuthenticationPrincipal ConsoleUser user, @RequestParam MultiValueMap<String, String> params, Model model,
                         RedirectAttributes redirect, HttpServletResponse response) {
        noStore(response);
        Actor actor = actor(user);
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        List<UUID> ids = new ArrayList<>();
        params.getOrDefault("listing", List.of()).forEach(text -> {
            UUID id = uuid(text);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        });
        if (ids.isEmpty()) {
            redirect.addFlashAttribute("error", messages.get("workspace.status.none"));
            return "redirect:/console/workspace";
        }
        boolean apply = "apply".equals(params.getFirst("step"));
        Map<UUID, String> results = new LinkedHashMap<>();
        String error = null;
        if (apply) {
            boolean open = params.containsKey("open");
            try {
                for (ListingStatusService.Outcome outcome : statusService.markReady(actor, ids, open, params.getFirst("reason"))) {
                    results.put(outcome.id(), labels.result(outcome.result()));
                }
            } catch (WorkspaceException e) {
                if ("forbidden".equals(e.code())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN);
                }
                error = labels.error(e.code());
                apply = false;
                response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            }
        }
        Map<UUID, Listing> found = listings.findMany(actor.clientId(), ids);
        Map<UUID, ListingStatusService.Verdict> verdicts = statusService.verdictsOf(actor.clientId(), found.values());
        Map<UUID, String> emails = roles.emails(actor.clientId());
        List<StatusRow> rows = new ArrayList<>();
        for (UUID id : ids) {
            Listing l = found.get(id);
            if (l == null) {
                continue;
            }
            ListingStatusService.Verdict v = verdicts.get(id);
            rows.add(new StatusRow(id, l.sku(), l.title(), labels.status(l.status()), v.blocks().stream().map(labels::block).toList(), v.state(),
                    v.opened() == null ? null : v.opened().reason(), v.opened() == null || v.opened().openedBy() == null ? null : emails.get(v.opened().openedBy()),
                    results.get(id), v.mayGo(), !v.trademarkChecked(), trademarkWhy(v), v.catalogUnchecked()));
        }
        model.addAttribute("rows", rows);
        model.addAttribute("ids", ids);
        model.addAttribute("applied", apply);
        model.addAttribute("admin", actor.admin());
        model.addAttribute("gate", statusService.gateActive());
        model.addAttribute("auditOff", !statusService.auditActive());
        model.addAttribute("anyBlocked", rows.stream().anyMatch(r -> !r.mayGo()));
        model.addAttribute("anyUnchecked", rows.stream().anyMatch(StatusRow::trademarkUnchecked));
        model.addAttribute("anyCatalogUnchecked", rows.stream().anyMatch(StatusRow::catalogUnchecked));
        model.addAttribute("error", error);
        model.addAttribute("reasonMax", WorkspaceOverrides.REASON_MAX);
        return "console/workspace-status";
    }

    /** Why the trademark part was not checked, as a word of the language of the page: what the audit stored, or that no audit result is of this text. */
    private String trademarkWhy(ListingStatusService.Verdict v) {
        if (v.trademarkChecked()) {
            return null;
        }
        return messages.get("workspace.trademark.reason." + (v.trademarkReason() == null ? "none" : v.trademarkReason().name().toLowerCase(java.util.Locale.ROOT)));
    }

    @PostMapping("/listings/{id}/draft")
    public String backToDraft(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id, RedirectAttributes redirect) {
        Actor actor = actor(user);
        listing(actor, id);
        try {
            redirect.addFlashAttribute("notice", statusService.markDraft(actor, id) ? messages.get("workspace.status.drafted")
                    : messages.get("workspace.status.drafted.no"));
        } catch (WorkspaceException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return "redirect:/console/workspace/listings/" + id + "/edit";
    }
}
