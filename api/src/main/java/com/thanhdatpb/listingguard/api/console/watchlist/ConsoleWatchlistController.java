package com.thanhdatpb.listingguard.api.console.watchlist;

import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRoles;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.console.bulk.DelimitedText;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryView;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.WatchlistView;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The "Watchlist" page of the console (ADR-031): the client's own trademark watchlist. Every account of the client sees it; only
 * an {@code ADMIN} account changes it, and every write route answers a {@code MEMBER} with {@code 403} (the role is read from the
 * database at each request). Every change goes through {@link WatchlistService#replace} with the revision the page was built
 * from, so a form built before someone else's change is refused with a message instead of undoing that change.
 *
 * <p>Import is two steps, and the server keeps nothing between them: the preview reads the pasted cells or the uploaded file and
 * shows what each row would do; the confirmation sends the same text back with the revision and applies it. Nothing is written to
 * a marketplace or to the seller's Sheet. Every value is rendered with {@code th:text}.
 */
@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleWatchlistController {

    static final String VIEW = "console/watchlist";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final Pattern ENTRY_PATH = Pattern.compile("entries\\[(\\d+)]");
    private static final Pattern CONSOLE_ACTOR = Pattern.compile("console:([0-9a-f-]{36})");
    /** The largest text a preview echoes back in its hidden field: the body limit bounds it already. */
    private static final int MAX_IMPORT_CHARS = 200_000;

    /** What the table shows of one entry, times in UTC. */
    public record EntryRow(UUID id, String mark, String aliases, String classes, String serialNumber, String note,
                           boolean enabled, String addedBy, String added, String updatedBy, String updated,
                           List<Integer> niceClasses) {

        /** True when the entry has the word in its mark, an alias or its note, in any case. */
        boolean mentions(String word) {
            String needle = word.toLowerCase(Locale.ROOT);
            return Stream.of(mark, aliases, note).anyMatch(text -> text != null && text.toLowerCase(Locale.ROOT).contains(needle));
        }
    }

    /**
     * The filter row: a word, a Nice class, and "on" or "off" (anything else is both). It narrows what the table shows, never what
     * the list holds, so a change made from a filtered page still goes through the whole list and its revision.
     */
    public record Filters(String q, Integer niceClass, String status) {

        static Filters none() {
            return new Filters("", null, "");
        }

        public boolean active() {
            return !q.isEmpty() || niceClass != null || !status.isEmpty();
        }

        List<EntryRow> apply(List<EntryRow> rows) {
            return rows.stream()
                    .filter(row -> q.isEmpty() || row.mentions(q))
                    .filter(row -> niceClass == null || row.niceClasses().contains(niceClass))
                    .filter(row -> status.isEmpty() || (status.equals("on") == row.enabled()))
                    .toList();
        }
    }

    /** The values of the add or edit form, as typed. */
    public record EntryForm(String id, String mark, String aliases, String classes, String serialNumber, String note) {

        static EntryForm empty() {
            return new EntryForm(null, "", "", "", "", "");
        }

        static EntryForm of(EntryView entry) {
            return new EntryForm(entry.id().toString(), entry.mark(), String.join("; ", entry.aliases()),
                    String.join(", ", entry.niceClasses().stream().map(String::valueOf).toList()),
                    Objects.toString(entry.serialNumber(), ""), Objects.toString(entry.note(), ""));
        }
    }

    /** One row of an import preview. */
    public record PreviewRow(int line, String mark, String outcome, String outcomeText, List<String> problems) {
    }

    private final WatchlistService watchlists;
    private final ConsoleAccountRoles roles;
    private final ConsoleMessages messages;

    public ConsoleWatchlistController(WatchlistService watchlists, ConsoleAccountRoles roles, ConsoleMessages messages) {
        this.watchlists = watchlists;
        this.roles = roles;
        this.messages = messages;
    }

    @GetMapping("/console/watchlist")
    public String page(@AuthenticationPrincipal ConsoleUser user, @RequestParam(name = "edit", required = false) UUID edit,
                       @RequestParam(name = "add", required = false) String add,
                       @RequestParam(name = "import", required = false) String importing,
                       @RequestParam(name = "q", defaultValue = "") String q,
                       @RequestParam(name = "class", required = false) Integer niceClass,
                       @RequestParam(name = "status", defaultValue = "") String status,
                       Model model, HttpServletResponse response) {
        noStore(response);
        WatchlistView view = populate(model, user, response);
        boolean admin = Boolean.TRUE.equals(model.getAttribute("admin"));
        EntryForm form = EntryForm.empty();
        if (view != null && edit != null && admin) {
            EntryView entry = view.entries().stream().filter(e -> e.id().equals(edit)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            form = EntryForm.of(entry);
        }
        model.addAttribute("form", form);
        // the form and the import open from the address, so the buttons of the header are plain links
        model.addAttribute("formOpen", view != null && admin && (edit != null || add != null));
        model.addAttribute("importOpen", view != null && admin && importing != null);
        Filters filters = new Filters(q.strip(), niceClass, "on".equals(status) || "off".equals(status) ? status : "");
        model.addAttribute("filters", filters);
        @SuppressWarnings("unchecked")
        List<EntryRow> all = (List<EntryRow>) model.getAttribute("rows");
        if (all != null && filters.active()) {
            model.addAttribute("rows", filters.apply(all));
        }
        return VIEW;
    }

    /** Adds an entry, or replaces the one named by {@code id} (the edit form). */
    @PostMapping("/console/watchlist/entries")
    public String save(@AuthenticationPrincipal ConsoleUser user,
                       @RequestParam(name = "id", required = false) String id,
                       @RequestParam(name = "mark", defaultValue = "") String mark,
                       @RequestParam(name = "aliases", defaultValue = "") String aliases,
                       @RequestParam(name = "classes", defaultValue = "") String classes,
                       @RequestParam(name = "serialNumber", defaultValue = "") String serialNumber,
                       @RequestParam(name = "note", defaultValue = "") String note,
                       @RequestParam(name = "revision") long revision,
                       Model model, HttpServletResponse response, RedirectAttributes redirect) {
        noStore(response);
        String actor = requireAdmin(user);
        EntryForm form = new EntryForm(id, mark, aliases, classes, serialNumber, note);
        List<Integer> classList = WatchlistTable.classes(classes);
        if (classList == null) {
            return refused(model, user, response, form, List.of(messages.get("watchlist.error.classes-format", classes)));
        }
        WatchlistView current = watchlists.read(user.clientId());
        List<EntryInput> entries = new ArrayList<>(current.entries().stream().map(WatchlistTable::input).toList());
        boolean adding = id == null || id.isBlank();
        if (adding) {
            entries.add(new EntryInput(mark, WatchlistTable.aliases(aliases), classList, serialNumber, note, true));
        } else {
            int index = indexOf(current, parseId(id));
            entries.set(index, new EntryInput(mark, WatchlistTable.aliases(aliases), classList, serialNumber, note,
                    entries.get(index).enabled()));
        }
        return apply(user, actor, entries, revision, model, response, redirect, form,
                adding ? "watchlist.notice.added" : "watchlist.notice.saved", mark.strip());
    }

    @PostMapping("/console/watchlist/entries/{id}/toggle")
    public String toggle(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id,
                         @RequestParam(name = "revision") long revision, Model model, HttpServletResponse response,
                         RedirectAttributes redirect) {
        String actor = requireAdmin(user);
        WatchlistView current = watchlists.read(user.clientId());
        int index = indexOf(current, id);
        List<EntryInput> entries = new ArrayList<>(current.entries().stream().map(WatchlistTable::input).toList());
        EntryInput entry = entries.get(index);
        boolean enable = !Boolean.TRUE.equals(entry.enabled());
        entries.set(index, new EntryInput(entry.mark(), entry.aliases(), entry.niceClasses(), entry.serialNumber(), entry.note(),
                enable));
        return apply(user, actor, entries, revision, model, response, redirect, EntryForm.empty(),
                enable ? "watchlist.notice.enabled" : "watchlist.notice.disabled", entry.mark());
    }

    @PostMapping("/console/watchlist/entries/{id}/delete")
    public String delete(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id,
                         @RequestParam(name = "revision") long revision, Model model, HttpServletResponse response,
                         RedirectAttributes redirect) {
        String actor = requireAdmin(user);
        WatchlistView current = watchlists.read(user.clientId());
        int index = indexOf(current, id);
        List<EntryInput> entries = new ArrayList<>(current.entries().stream().map(WatchlistTable::input).toList());
        EntryInput removed = entries.remove(index);
        return apply(user, actor, entries, revision, model, response, redirect, EntryForm.empty(), "watchlist.notice.deleted",
                removed.mark());
    }

    /** Reads the pasted cells or the uploaded file and shows what each row would do; nothing is stored. */
    @PostMapping("/console/watchlist/import/preview")
    public String preview(@AuthenticationPrincipal ConsoleUser user,
                          @RequestParam(name = "pasted", defaultValue = "") String pasted,
                          @RequestParam(name = "file", required = false) MultipartFile file,
                          Model model, HttpServletResponse response) throws IOException {
        noStore(response);
        requireAdmin(user);
        String text;
        char delimiter;
        if (file != null && !file.isEmpty()) {
            text = utf8(file.getBytes());
            if (text == null) {
                return importRefused(model, user, response, messages.get("watchlist.import.error.encoding"));
            }
            delimiter = DelimitedText.detectDelimiter(text);
        } else {
            text = pasted;
            delimiter = '\t';
        }
        if (text.isBlank()) {
            return importRefused(model, user, response, messages.get("watchlist.import.error.empty"));
        }
        if (text.length() > MAX_IMPORT_CHARS) {
            return importRefused(model, user, response, messages.get("watchlist.import.error.toolarge", MAX_IMPORT_CHARS));
        }
        return showPreview(model, user, response, text, delimiter);
    }

    /** Applies the text a preview showed, if the list is still at the preview's revision. */
    @PostMapping("/console/watchlist/import")
    public String importRows(@AuthenticationPrincipal ConsoleUser user,
                             @RequestParam(name = "data", defaultValue = "") String data,
                             @RequestParam(name = "delimiter", defaultValue = "tab") String delimiterName,
                             @RequestParam(name = "revision") long revision,
                             Model model, HttpServletResponse response, RedirectAttributes redirect) {
        noStore(response);
        String actor = requireAdmin(user);
        char delimiter = delimiter(delimiterName);
        WatchlistView current = watchlists.read(user.clientId());
        WatchlistTable.Merge merge;
        try {
            merge = WatchlistTable.merge(current.entries(), WatchlistTable.read(data, delimiter), watchlists::normalizedForm);
        } catch (DelimitedText.FormatException e) {
            return importRefused(model, user, response, messages.get("watchlist.import.error.quote", e.line));
        }
        if (merge.rows().isEmpty() || merge.rows().stream().anyMatch(row -> row.outcome() == WatchlistTable.Outcome.REFUSED)) {
            return showPreview(model, user, response, data, delimiter);
        }
        long changed = merge.rows().stream().filter(row -> row.outcome() != WatchlistTable.Outcome.UNCHANGED).count();
        try {
            watchlists.replace(user.clientId(), merge.entries(), actor, revision);
        } catch (WatchlistService.InvalidWatchlistException e) {
            return showPreview(model, user, response, data, delimiter);
        } catch (WatchlistService.RevisionMismatchException e) {
            return conflict(model, user, response);
        }
        redirect.addFlashAttribute("notice", messages.get("watchlist.notice.imported", merge.rows().size(), changed));
        return "redirect:/console/watchlist";
    }

    @GetMapping("/console/watchlist/export.csv")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal ConsoleUser user) {
        WatchlistView view = watchlists.read(user.clientId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("trademark-watchlist.csv").build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(WatchlistTable.csv(view.entries()).getBytes(StandardCharsets.UTF_8));
    }

    // ---- the page

    /** Fills what every rendering of the page needs; null when the client's plan has no trademark pack. */
    private WatchlistView populate(Model model, ConsoleUser user, HttpServletResponse response) {
        model.addAttribute("email", user.email());
        model.addAttribute("admin", roles.isAdmin(user));
        model.addAttribute("formOpen", false);
        model.addAttribute("importOpen", false);
        model.addAttribute("filters", Filters.none());
        model.addAttribute("total", 0);
        model.addAttribute("classOptions", List.of());
        WatchlistView view;
        try {
            view = watchlists.read(user.clientId());
        } catch (PlanNotAllowedException e) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            model.addAttribute("planRefused", messages.get("watchlist.plan", e.plan()));
            model.addAttribute("rows", List.of());
            return null;
        }
        Map<UUID, String> emails = roles.emails(user.clientId());
        model.addAttribute("revision", view.revision());
        List<EntryRow> rows = view.entries().stream().map(entry -> row(entry, emails)).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("total", rows.size());
        model.addAttribute("classOptions",
                rows.stream().flatMap(row -> row.niceClasses().stream()).collect(Collectors.toCollection(TreeSet::new)));
        model.addAttribute("enabledCount", view.entries().stream().filter(EntryView::enabled).count());
        model.addAttribute("limits", view.limits());
        String minimum = watchlists.settings().minimumRisk().name();
        model.addAttribute("minimumRisk", minimum);
        // the label sits inside a sentence, so it is written in lower case ("high risk", "rủi ro cao")
        Locale language = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        model.addAttribute("minimumLabel", messages.get("bulk.level." + minimum.toLowerCase(Locale.ROOT)).toLowerCase(language));
        return view;
    }

    private EntryRow row(EntryView entry, Map<UUID, String> emails) {
        return new EntryRow(entry.id(), entry.mark(), String.join("; ", entry.aliases()),
                String.join(", ", entry.niceClasses().stream().map(String::valueOf).toList()), entry.serialNumber(), entry.note(),
                entry.enabled(), actor(entry.addedBy(), emails), time(entry.addedAt()), actor(entry.updatedBy(), emails),
                time(entry.updatedAt()), List.copyOf(entry.niceClasses()));
    }

    /** {@code console:<account id>} as the account's email, {@code api-key:<key id>} as the key. */
    private String actor(String actor, Map<UUID, String> emails) {
        Matcher console = CONSOLE_ACTOR.matcher(actor);
        if (console.matches()) {
            String email = emails.get(UUID.fromString(console.group(1)));
            return email != null ? email : messages.get("watchlist.actor.removed");
        }
        if (actor.startsWith("api-key:")) {
            return messages.get("watchlist.actor.key", actor.substring("api-key:".length()));
        }
        return actor;
    }

    private String apply(ConsoleUser user, String actor, List<EntryInput> entries, long revision, Model model,
                         HttpServletResponse response, RedirectAttributes redirect, EntryForm form, String noticeKey,
                         String mark) {
        try {
            watchlists.replace(user.clientId(), entries, actor, revision);
        } catch (WatchlistService.InvalidWatchlistException e) {
            List<EntryInput> cleaned = WatchlistService.clean(entries);
            return refused(model, user, response, form, e.problems().stream().map(p -> describe(p, cleaned)).toList());
        } catch (WatchlistService.RevisionMismatchException e) {
            return conflict(model, user, response);
        }
        redirect.addFlashAttribute("notice", messages.get(noticeKey, mark));
        return "redirect:/console/watchlist";
    }

    private String refused(Model model, ConsoleUser user, HttpServletResponse response, EntryForm form, List<String> problems) {
        populate(model, user, response);
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("form", form);
        model.addAttribute("formOpen", Boolean.TRUE.equals(model.getAttribute("admin")));
        model.addAttribute("errors", problems);
        return VIEW;
    }

    private String conflict(Model model, ConsoleUser user, HttpServletResponse response) {
        populate(model, user, response);
        response.setStatus(HttpStatus.CONFLICT.value());
        model.addAttribute("form", EntryForm.empty());
        model.addAttribute("errors", List.of(messages.get("watchlist.error.stale")));
        return VIEW;
    }

    private String importRefused(Model model, ConsoleUser user, HttpServletResponse response, String problem) {
        populate(model, user, response);
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("form", EntryForm.empty());
        model.addAttribute("importOpen", Boolean.TRUE.equals(model.getAttribute("admin")));
        model.addAttribute("importError", problem);
        return VIEW;
    }

    private String showPreview(Model model, ConsoleUser user, HttpServletResponse response, String text, char delimiter) {
        WatchlistView current = populate(model, user, response);
        model.addAttribute("form", EntryForm.empty());
        if (current == null) {
            return VIEW;
        }
        WatchlistTable.Merge merge;
        try {
            merge = WatchlistTable.merge(current.entries(), WatchlistTable.read(text, delimiter), watchlists::normalizedForm);
        } catch (DelimitedText.FormatException e) {
            return importRefused(model, user, response, messages.get("watchlist.import.error.quote", e.line));
        }
        List<EntryInput> cleaned = WatchlistService.clean(merge.entries());
        List<WatchlistService.Problem> problems = watchlists.validate(cleaned);
        List<String> general = new ArrayList<>();
        List<PreviewRow> rows = new ArrayList<>();
        boolean refused = false;
        for (WatchlistTable.MergedRow merged : merge.rows()) {
            List<String> rowProblems = new ArrayList<>();
            if (merged.outcome() == WatchlistTable.Outcome.REFUSED) {
                rowProblems.add(messages.get("watchlist.error." + merged.row().errorCode(), merged.row().errorValue()));
            }
            for (WatchlistService.Problem problem : problems) {
                if (merged.index() >= 0 && entryIndex(problem.field()) == merged.index()) {
                    rowProblems.add(describe(problem, cleaned));
                }
            }
            String outcome = rowProblems.isEmpty() ? merged.outcome().name().toLowerCase(Locale.ROOT) : "refused";
            refused |= !rowProblems.isEmpty();
            String mark = merged.row().entry() == null ? "" : merged.row().entry().mark();
            rows.add(new PreviewRow(merged.row().line(), mark, outcome, messages.get("watchlist.preview.outcome." + outcome),
                    List.copyOf(rowProblems)));
        }
        for (WatchlistService.Problem problem : problems) {
            int index = entryIndex(problem.field());
            if (index < 0 || merge.rows().stream().noneMatch(row -> row.index() == index)) {
                general.add(describe(problem, cleaned));
                refused = true;
            }
        }
        model.addAttribute("preview", rows);
        model.addAttribute("previewProblems", general);
        model.addAttribute("previewCanApply", !refused && !rows.isEmpty());
        model.addAttribute("previewData", text);
        model.addAttribute("previewDelimiter", delimiter == '\t' ? "tab" : delimiter == ',' ? "comma" : "semicolon");
        return VIEW;
    }

    /** A problem of the service in the page's language, with the mark of the entry it is about. */
    private String describe(WatchlistService.Problem problem, List<EntryInput> entries) {
        List<Object> args = new ArrayList<>(problem.args());
        if (problem.code().equals("duplicate") && args.size() == 2) {
            int other = entryIndex((String) args.get(1));
            if (other >= 0 && other < entries.size() && entries.get(other) != null) {
                args.set(1, entries.get(other).mark());
            }
        }
        String text = messages.get("watchlist.error." + problem.code(), args.toArray());
        int index = entryIndex(problem.field());
        if (index >= 0 && index < entries.size() && entries.get(index) != null && !entries.get(index).mark().isEmpty()) {
            return messages.get("watchlist.error.about", entries.get(index).mark(), text);
        }
        return text;
    }

    private static int entryIndex(String path) {
        Matcher matcher = ENTRY_PATH.matcher(path == null ? "" : path);
        return matcher.lookingAt() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /** The actor of a change by this account; a {@code MEMBER} (or an account that left the client) is refused with 403. */
    private String requireAdmin(ConsoleUser user) {
        ConsoleAccount account = roles.account(user).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (!roles.isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return "console:" + account.getId();
    }

    private static int indexOf(WatchlistView view, UUID id) {
        for (int i = 0; i < view.entries().size(); i++) {
            if (view.entries().get(i).id().equals(id)) {
                return i;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id.strip());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static char delimiter(String name) {
        return switch (name) {
            case "comma" -> ',';
            case "semicolon" -> ';';
            default -> '\t';
        };
    }

    private static String time(Instant instant) {
        return instant == null ? "" : TIME.format(instant);
    }

    /** The bytes as UTF-8, or null when they are not (a file saved in another encoding would be read wrongly). */
    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
