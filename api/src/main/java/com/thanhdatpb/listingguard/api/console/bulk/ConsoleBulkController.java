package com.thanhdatpb.listingguard.api.console.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.check.CheckScope;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleFeedbackSupport;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsolePilot;
import com.thanhdatpb.listingguard.api.console.PilotMetrics;
import com.thanhdatpb.listingguard.api.console.PilotDraft;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.console.editor.EditorCost;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;
import com.thanhdatpb.listingguard.input.BuiltinAdapterCatalog;
import com.thanhdatpb.listingguard.input.ConfiguredCsvAdapter;
import com.thanhdatpb.listingguard.input.ListingInputAdapter;
import com.thanhdatpb.listingguard.input.StandardListingAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * The "Kiểm tra hàng loạt" page of the console (ADR-029): a seller pastes rows copied from a Sheet, or uploads a CSV or TSV, confirms
 * which column is what, and gets one status per row, to read here or to paste back into the Sheet.
 *
 * <p>Two steps, and the server keeps nothing between them. {@code POST /console/bulk/prepare} reads the paste or the file; a file that
 * one of the built-in input adapters recognizes is checked at once, anything else comes back as the mapping step. The mapping step
 * carries the text back in a hidden field, and {@code POST /console/bulk/run} reads it again, maps it, checks it and shows the result.
 * The pasted text, the file and the table are never stored (nothing in the session, no file of this application's own); what is stored is
 * what the check services store for any check, and the column mapping of the account (labels and targets, no cell).
 *
 * <p>It only checks and suggests: nothing is published, no fix is applied, nothing is written to a marketplace or a Sheet. Every value
 * the pages print goes through {@code th:text}.
 */
@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleBulkController {

    static final String VIEW = "console/bulk";

    private static final List<ListingInputAdapter> ADAPTERS = BuiltinAdapterCatalog.adapters();
    private static final int SAMPLES = 3;
    private static final int SAMPLE_CHARS = 40;

    /** The paste tab and the file tab. */
    public enum Tab {
        PASTE("paste"), FILE("file");

        private final String key;

        Tab(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        static Tab parse(String value) {
            return "file".equals(value) ? FILE : PASTE;
        }
    }

    /**
     * One column of the mapping step: what it holds (a few cells) and what the page chose for it. {@code chip} is the short name of the
     * chosen target ("Title"), empty when the column is skipped.
     */
    public record ColumnView(int index, String letter, String label, List<String> samples, String selected, String chip) {
    }

    public record TargetOption(String key, String label) {
    }

    public record SeparatorView(String key, String label, String selected) {
    }

    public record SeparatorOption(String key, String label) {
    }

    /**
     * @param dataField the text to send back, with the line break that an HTML textarea drops at its start put in front of it
     * @param intro     how many rows and columns the table has and how the header was read, in the language of the page
     * @param costNote  what the run can cost, or null when plans are off
     * @param rows      how many rows the run will check (blank rows are not counted)
     */
    public record MappingPage(String dataField, String delimiter, boolean header, String intro, List<ColumnView> columns,
                              List<SeparatorView> separators, List<String> problems, boolean remember, String costNote,
                              boolean remembered, long rows) {

        /** True when no column has a meaning yet, so the table that corrects them is opened for the seller. */
        public boolean noneChosen() {
            return columns.stream().allMatch(column -> column.selected().isEmpty());
        }
    }

    public record PilotDraftForm(String id, String data, Map<String, String> mapping) { }

    private final ConsolePilot pilot;
    private final PilotMetrics pilotMetrics;
    private final Clock clock;
    private final BulkProperties limits;
    private final BulkCheckService service;
    private final BulkMappingStore mappingStore;
    private final ConsoleAccountRepository accounts;
    private final PlanService plans;
    private final ObjectMapper mapper;
    private final ConsoleMessages messages;
    private final ConsoleFeedbackSupport feedback;
    private final com.thanhdatpb.listingguard.api.history.CheckHistoryRecorder history;
    private final com.thanhdatpb.listingguard.ruleengine.readiness.ReadinessScorer readiness;

    public ConsoleBulkController(BulkProperties limits, BulkCheckService service, BulkMappingStore mappingStore,
                                 ConsoleAccountRepository accounts, PlanService plans, ObjectMapper mapper,
                                 ConsoleMessages messages, ConsoleFeedbackSupport feedback,
                                 com.thanhdatpb.listingguard.api.history.CheckHistoryRecorder history,
                                 com.thanhdatpb.listingguard.ruleengine.readiness.ReadinessScorer readiness,
                                 ConsolePilot pilot, PilotMetrics pilotMetrics, Clock clock) {
        this.readiness = readiness;
        this.pilot = pilot;
        this.pilotMetrics = pilotMetrics;
        this.clock = clock;
        this.messages = messages;
        this.feedback = feedback;
        this.history = history;
        this.limits = limits;
        this.service = service;
        this.mappingStore = mappingStore;
        this.accounts = accounts;
        this.plans = plans;
        this.mapper = mapper;
    }

    @GetMapping("/console/bulk")
    public String form(@RequestParam(name = "tab", defaultValue = "paste") String tab,
                       @RequestParam(name = "marketplace", defaultValue = "ETSY") String marketplace,
                       @AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        noStore(response);
        base(model, user, marketplace(marketplace), Tab.parse(tab));
        return VIEW;
    }

    /**
     * Step 1. A paste is a tab-separated table (what Google Sheets and Excel put on the clipboard). A file is read as UTF-8; if its
     * header is one of the built-in input adapters' it is checked with that adapter's own columns, otherwise it is read as a table.
     * Anything the page cannot use is refused with a message and the form comes back (the pasted text with it, when it fits).
     */
    @PostMapping("/console/bulk/prepare")
    public String prepare(@RequestParam(name = "marketplace", defaultValue = "") String marketplaceCode,
                          @RequestParam(name = "source", defaultValue = "paste") String source,
                          @RequestParam(name = "text", required = false) String text,
                          @RequestParam(name = "header", required = false) String header,
                          @RequestParam(name = "file", required = false) MultipartFile file,
                          @AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        noStore(response);
        EditorMarketplace marketplace = marketplace(marketplaceCode);
        Tab tab = Tab.parse(source);
        try {
            BulkTable.HeaderMode mode = BulkTable.HeaderMode.parse(header);
            if (tab == Tab.FILE) {
                return prepareFile(user, marketplace, file, mode, model);
            }
            String pasted = text == null ? "" : text;
            requireWithinBytes(pasted.getBytes(StandardCharsets.UTF_8).length, messages.get("bulk.error.what.paste"));
            if (pasted.isBlank()) {
                throw new BulkTable.Refused(400, messages.get("bulk.error.nodata"));
            }
            BulkTable.Prepared table = BulkTable.prepare(messages, pasted, '\t', mode, marketplace,
                    rememberedLabels(user, marketplace), limits.maxRows());
            return mappingStep(user, marketplace, table, pasted, null, model);
        } catch (BulkTable.Refused e) {
            return refuse(user, marketplace, tab, e, text, model, response);
        }
    }

    /**
     * Step 2: the mapping the seller confirmed. A mapping that cannot be run (a target given to two columns, nothing mapped) comes
     * back to the mapping step with the reasons; a row whose price or quantity cannot be read is shown as unreadable and the others
     * are checked.
     */
    @PostMapping("/console/bulk/run")
    public String run(@RequestParam Map<String, String> params, @AuthenticationPrincipal ConsoleUser user, Model model,
                      HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        EditorMarketplace marketplace = marketplace(params.getOrDefault("marketplace", ""));
        String data = params.getOrDefault("data", "");
        try {
            requireWithinBytes(data.getBytes(StandardCharsets.UTF_8).length, messages.get("bulk.error.what.content"));
            BulkTable.HeaderMode mode = "yes".equals(params.get("header")) ? BulkTable.HeaderMode.YES : BulkTable.HeaderMode.NO;
            char delimiter = delimiter(params.get("delimiter"));
            BulkTable.Prepared table = BulkTable.prepare(messages, data, delimiter, mode, marketplace, Set.of(), limits.maxRows());
            ColumnMapping mapping = ColumnMapping.fromParams(messages, marketplace, table.columns(), params);
            boolean remember = params.containsKey("remember");
            CheckScope.Kind scope = scopeOf(params.get("scope"));
            List<String> problems = mapping.problems(messages, marketplace, table.labels());
            if (!problems.isEmpty()) {
                response.setStatus(HttpStatus.BAD_REQUEST.value());
                return mappingStep(user, marketplace, table, data, new Remembered(mapping, problems, remember, scope), model);
            }
            List<BulkCheckService.Input> inputs = new ArrayList<>();
            for (DelimitedText.Row row : table.rows()) {
                BulkListingMapper.Mapped mapped = BulkListingMapper.map(messages, marketplace, mapping, table.labels(), row.cells());
                if (mapped.blank()) {
                    inputs.add(BulkCheckService.Input.empty(row.line()));
                } else if (mapped.error() != null) {
                    inputs.add(BulkCheckService.Input.unreadable(row.line(), mapped.sku(), mapped.error()));
                } else {
                    inputs.add(BulkCheckService.Input.of(row.line(), mapped.sku(), mapped.parentSku(), mapped.theme(),
                            mapped.attributes(), mapped.listing()));
                }
            }
            // a content scope treats exactly the listing fields the seller gave a column as supplied (ADR-052)
            CheckScope checkScope = scope == CheckScope.Kind.CONTENT ? CheckScope.content(mapping.mappedFields(marketplace))
                    : CheckScope.FULL;
            BulkResult result = service.run(user.clientId(), marketplace, inputs, table.header(), checkScope);
            if (remember) {
                remember(user, marketplace, mapping.toStored(marketplace, table.labels(), scopeName(scope)));
            }
            Set<Integer> changed = Set.of();
            if (pilot.enabled()) {
                Set<Integer> checkedLines = new HashSet<>();
                result.rows().stream().filter(BulkResult.Row::checked).forEach(row -> checkedLines.add(row.line()));
                Map<Integer, PilotDraft.Fingerprint> fingerprints = new HashMap<>();
                for (var input : inputs) {
                    if (checkedLines.contains(input.line()) && input.listing() != null) {
                        fingerprints.put(input.line(), PilotDraft.Fingerprint.of(input.sku(), input.listing().toString()));
                    }
                }
                var draft = PilotDraft.advance(request.getSession(), PilotDraft.Surface.BULK, clock, user.clientId(),
                        marketplace.code(), params.get("pilotDraft"), fingerprints);
                changed = draft.changed();
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("marketplace", marketplace.code());
                fields.put("delimiter", delimiterName(delimiter));
                fields.put("header", table.header() ? "yes" : "no");
                fields.put("scope", scopeName(scope));
                for (int i = 0; i < mapping.targets().size(); i++) {
                    fields.put("col." + i, mapping.targetOf(i));
                }
                mapping.separators().forEach((key, value) -> fields.put("sep." + key, value));
                model.addAttribute("pilotDraft", new PilotDraftForm(draft.id(), "\n" + data, fields));
            }
            return result(user, marketplace, result, List.of(), changed, model);
        } catch (BulkTable.Refused e) {
            return refuse(user, marketplace, Tab.PASTE, e, pilot.enabled() ? data : null, model, response);
        } catch (PlanNotAllowedException e) {
            return refuse(user, marketplace, Tab.PASTE, new BulkTable.Refused(403, messages.get("bulk.error.plan", e.plan(),
                    BulkText.packName(messages, e.pack()))), null, model, response);
        } catch (QuotaExceededException e) {
            return refuse(user, marketplace, Tab.PASTE, new BulkTable.Refused(429, messages.get("bulk.error.quota",
                    BulkText.quota(messages, e))), null, model, response);
        }
    }

    // ---- step 1: the file

    private String prepareFile(ConsoleUser user, EditorMarketplace marketplace, MultipartFile file, BulkTable.HeaderMode mode,
                               Model model) {
        if (file == null || file.isEmpty()) {
            throw new BulkTable.Refused(400, messages.get("bulk.error.nofile"));
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt"))) {
            throw new BulkTable.Refused(400, messages.get("bulk.error.extension"));
        }
        requireWithinBytes(file.getSize(), messages.get("bulk.error.what.file"));
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BulkTable.Refused(400, messages.get("bulk.error.readfile"));
        }
        String text = decode(bytes);
        Optional<ConfiguredCsvAdapter> adapter = adapterFor(text);
        if (adapter.isEmpty()) {
            char delimiter = DelimitedText.detectDelimiter(text);
            BulkTable.Prepared table = BulkTable.prepare(messages, text, delimiter, mode, marketplace,
                    rememberedLabels(user, marketplace), limits.maxRows());
            return mappingStep(user, marketplace, table, text, null, model);
        }
        if (!adapter.get().marketplace().equals(marketplace.code())) {
            throw new BulkTable.Refused(400, messages.get("bulk.error.adapter.marketplace", adapter.get().id(),
                    adapter.get().marketplace(), marketplace.label()));
        }
        return runWithAdapter(user, marketplace, adapter.get(), bytes, model);
    }

    private String runWithAdapter(ConsoleUser user, EditorMarketplace marketplace, ConfiguredCsvAdapter adapter, byte[] bytes,
                                  Model model) {
        List<BulkCheckService.Input> inputs = new ArrayList<>();
        boolean families = false;
        try (ListingInputAdapter.ListingReader reader = adapter.open(new ByteArrayInputStream(bytes),
                new ListingInputAdapter.Limits(limits.maxBytes().toBytes(), limits.maxRows()))) {
            ListingInputAdapter.ListingRecord record;
            while ((record = reader.next()) != null) {
                families |= record.familyRole() != null;
                int line = (int) record.line();
                if (!record.errors().isEmpty()) {
                    inputs.add(BulkCheckService.Input.unreadable(line, record.id(),
                            messages.get("bulk.row.problem", fieldErrors(record.errors()))));
                    continue;
                }
                JsonNode tree = mapper.valueToTree(record.listing());
                ObjectNode listing = StandardListingAdapter.toStandardInput(marketplace.code(), (ObjectNode) tree);
                inputs.add(BulkCheckService.Input.of(line, record.id(), null, null, null, listing));
            }
        } catch (IOException | RuntimeException e) {
            throw new BulkTable.Refused(400, adapterFailure(e));
        }
        if (inputs.isEmpty()) {
            throw new BulkTable.Refused(400, messages.get("bulk.error.headeronly"));
        }
        try {
            BulkResult result = service.run(user.clientId(), marketplace, inputs, true);
            List<String> notes = new ArrayList<>();
            notes.add(messages.get("bulk.note.adapter", adapter.id()));
            if (families) {
                notes.add(messages.get("bulk.note.adapter.families"));
            }
            return result(user, marketplace, result, notes, Set.of(), model);
        } catch (PlanNotAllowedException e) {
            throw new BulkTable.Refused(403, messages.get("bulk.error.plan", e.plan(), BulkText.packName(messages, e.pack())));
        } catch (QuotaExceededException e) {
            throw new BulkTable.Refused(429, messages.get("bulk.error.quota", BulkText.quota(messages, e)));
        }
    }

    /** The built-in CSV adapter whose columns the header holds (the one with most columns when several do), or empty. */
    public static Optional<ConfiguredCsvAdapter> adapterFor(String text) {
        ConfiguredCsvAdapter best = null;
        int bestColumns = -1;
        for (ListingInputAdapter adapter : ADAPTERS) {
            if (!(adapter instanceof ConfiguredCsvAdapter csv)) {
                continue;
            }
            List<String> header = headerOf(text, csv.delimiter());
            ListingInputAdapter.Detection detection = csv.detect(header);
            if (detection.supported() && detection.matchedColumns() > bestColumns) {
                best = csv;
                bestColumns = detection.matchedColumns();
            }
        }
        return Optional.ofNullable(best);
    }

    private static List<String> headerOf(String text, char delimiter) {
        try {
            List<DelimitedText.Row> rows = DelimitedText.parse(firstRecord(text), delimiter).rows();
            return rows.isEmpty() ? List.of() : rows.get(0).cells().stream().map(String::trim).toList();
        } catch (DelimitedText.FormatException e) {
            return List.of();
        }
    }

    /** The text up to the end of its first record (a quoted cell may hold line breaks), so a header is read without parsing the file. */
    private static String firstRecord(String text) {
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new BulkTable.Refused(400, messages.get("bulk.error.notutf8"));
        }
    }

    private String fieldErrors(List<ListingInputAdapter.FieldError> errors) {
        List<String> texts = new ArrayList<>();
        for (ListingInputAdapter.FieldError error : errors) {
            texts.add(switch (error.code()) {
                case "invalid-number" -> messages.get("bulk.field.invalidnumber", error.field());
                case "missing-column" -> messages.get("bulk.field.missingcolumn", error.field());
                default -> messages.get("bulk.field.other", error.field(), error.code());
            });
        }
        return String.join("; ", texts);
    }

    private String adapterFailure(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("row limit")) {
            return messages.get("bulk.error.adapter.rows", limits.maxRows());
        }
        if (message.contains("byte limit")) {
            return messages.get("bulk.error.adapter.bytes", bytesText(limits.maxBytes().toBytes()));
        }
        return messages.get("bulk.error.adapter.csv");
    }

    // ---- the pages

    /** The mapping a refused run comes back with: the seller's choices, the reasons and the "remember" box. */
    private record Remembered(ColumnMapping mapping, List<String> problems, boolean remember, CheckScope.Kind scope) {
    }

    /**
     * The scope of a submitted form (ADR-052): {@code content} or {@code full}. A form that names none (an older page, a request built by
     * hand) gets the configured default, and a value that is neither is refused, never read as the other.
     */
    private CheckScope.Kind scopeOf(String value) {
        if (value == null || value.isBlank()) {
            return defaultScope();
        }
        return switch (value.trim()) {
            case "content" -> CheckScope.Kind.CONTENT;
            case "full" -> CheckScope.Kind.FULL;
            default -> throw new BulkTable.Refused(400, messages.get("bulk.error.badscope"));
        };
    }

    /** What the mapping step offers first: the setting, else content under the pilot profile, else full. */
    private CheckScope.Kind defaultScope() {
        return limits.scopeFor(pilot.enabled());
    }

    private static String scopeName(CheckScope.Kind scope) {
        return scope == CheckScope.Kind.CONTENT ? "content" : "full";
    }

    private String mappingStep(ConsoleUser user, EditorMarketplace marketplace, BulkTable.Prepared table, String text,
                               Remembered again, Model model) {
        Optional<BulkMappingStore.Stored> stored = again == null ? stored(user, marketplace) : Optional.empty();
        ColumnMapping mapping = again != null ? again.mapping() : ColumnMapping.suggest(marketplace, table.labels(), stored.orElse(null));
        List<ColumnView> columns = new ArrayList<>();
        for (int i = 0; i < table.columns(); i++) {
            columns.add(new ColumnView(i, BulkTable.letters(i), table.labels().get(i), samples(table, i), mapping.targetOf(i),
                    chip(marketplace, mapping.targetOf(i))));
        }
        List<SeparatorView> separators = new ArrayList<>();
        for (BulkTargets.Target target : BulkTargets.of(marketplace)) {
            if (target.separable()) {
                separators.add(new SeparatorView(target.key(), messages.get("bulk.separators.for", target.label(messages)),
                        mapping.separatorOf(target)));
            }
        }
        long judgeable = table.rows().stream().filter(row -> !row.blank()).count();
        base(model, user, marketplace, Tab.PASTE);
        model.addAttribute("stage", "mapping");
        // the scope the page offers: the one just chosen, else the account's remembered one, else the default (ADR-052)
        CheckScope.Kind offered = again != null ? again.scope()
                : stored.map(BulkMappingStore.Stored::scope).map(this::scopeOf).orElseGet(this::defaultScope);
        model.addAttribute("scopeSelected", scopeName(offered));
        model.addAttribute("targets", BulkTargets.of(marketplace).stream().map(t -> new TargetOption(t.key(), t.label(messages))).toList());
        model.addAttribute("separatorOptions",
                BulkTargets.SEPARATORS.stream().map(s -> new SeparatorOption(s.key(), s.label(messages))).toList());
        String intro = messages.get("bulk.mapping.counts", table.rows().size(), table.columns()) + " "
                + messages.get(table.header() ? "bulk.mapping.header.yes" : "bulk.mapping.header.no") + " "
                + messages.get("bulk.mapping.guess");
        model.addAttribute("mapping", new MappingPage("\n" + text, delimiterName(table.delimiter()), table.header(), intro,
                columns, separators, again == null ? List.of() : again.problems(), again == null || again.remember(),
                costNote(user, judgeable), stored.isPresent(), judgeable));
        return VIEW;
    }

    /** The short name of a target for a chip: its label up to the first parenthesis ("Tags (several columns...)" gives "Tags"). */
    private String chip(EditorMarketplace marketplace, String target) {
        if (target == null || target.isEmpty()) {
            return "";
        }
        for (BulkTargets.Target candidate : BulkTargets.of(marketplace)) {
            if (candidate.key().equals(target)) {
                String label = candidate.label(messages);
                int paren = label.indexOf(" (");
                return paren > 0 ? label.substring(0, paren) : label;
            }
        }
        return "";
    }

    private String result(ConsoleUser user, EditorMarketplace marketplace, BulkResult result, List<String> extraNotes,
                          Set<Integer> changed, Model model) {
        if (pilot.enabled()) {
            pilotMetrics.record(user.clientId(), marketplace.code(), result.scope(), result.rows().stream().filter(BulkResult.Row::checked)
                    .map(row -> {
                        BulkResult.Trademark trademark = row.detail().trademark();
                        String risk = trademark.kind() == BulkResult.Kind.RESULT ? trademark.level() : null;
                        return new PilotMetrics.Observation(risk, changed.contains(row.line()));
                    }).toList());
        }
        base(model, user, marketplace, Tab.PASTE);
        model.addAttribute("stage", "result");
        model.addAttribute("readinessMax", readiness.config().baseScore());
        List<String> notes = new ArrayList<>(extraNotes);
        notes.addAll(result.notes());
        model.addAttribute("result", new BulkResult(result.marketplace(), result.header(), result.rows(), result.counts(), notes,
                result.disclaimer(), result.sheetBlock(), result.csvHref(), result.scope(), result.skippedFields()));
        feedback.populate(model, user, result.targets());
        feedback.accountId(user).ifPresent(account -> result.rows().forEach(row -> {
            var detail = row.detail();
            if (detail == null) return;
            if (pilot.roleHome() && detail.rules().checkId() != null) {
                history.account(user.clientId(), account, "listing-rules", detail.rules().checkId(), marketplace.code(), 1, row.sku());
            } else {
                historyTarget(user.clientId(), account, marketplace.code(), row, "listing-rules", detail.rules().items().stream()
                        .map(BulkResult.Item::target).filter(java.util.Objects::nonNull).findFirst().orElse(null));
            }
            historyTarget(user.clientId(), account, marketplace.code(), row, "trademark-risk",
                    detail.trademark().items().stream().map(BulkResult.Item::target).filter(java.util.Objects::nonNull).findFirst().orElse(null));
            historyTarget(user.clientId(), account, marketplace.code(), row, "variation-checks",
                    detail.variation().items().stream().map(BulkResult.Item::target).filter(java.util.Objects::nonNull).findFirst().orElse(null));
        }));
        return VIEW;
    }

    private void historyTarget(java.util.UUID clientId, java.util.UUID accountId, String marketplace, BulkResult.Row row,
                               String pack, com.thanhdatpb.listingguard.api.feedback.FeedbackTarget target) {
        if (target != null) history.account(clientId, accountId, pack, target.checkId(), marketplace, 1, row.sku());
    }

    private String refuse(ConsoleUser user, EditorMarketplace marketplace, Tab tab, BulkTable.Refused refusal, String text,
                          Model model, HttpServletResponse response) {
        response.setStatus(refusal.status);
        base(model, user, marketplace, tab);
        model.addAttribute("error", refusal.getMessage());
        // the pasted text comes back so it is not lost, unless it was refused for being too big
        if (text != null && text.getBytes(StandardCharsets.UTF_8).length <= limits.maxBytes().toBytes()) {
            model.addAttribute("pasted", "\n" + text);
        }
        return VIEW;
    }

    private void base(Model model, ConsoleUser user, EditorMarketplace marketplace, Tab tab) {
        model.addAttribute("email", user.email());
        model.addAttribute("marketplace", marketplace);
        model.addAttribute("marketplaces", EditorMarketplace.values());
        model.addAttribute("tab", tab);
        model.addAttribute("stage", "form");
        String bytes = bytesText(limits.maxBytes().toBytes());
        model.addAttribute("pasteHint", messages.get("bulk.paste.hint", limits.maxRows(), bytes));
        model.addAttribute("fileHint", messages.get("bulk.file.hint", limits.maxRows(), bytes));
    }

    private static void noStore(HttpServletResponse response) {
        // the pages hold what the seller pasted: nothing may keep a copy
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    // ---- small helpers

    private void requireWithinBytes(long size, String what) {
        if (size > limits.maxBytes().toBytes()) {
            throw new BulkTable.Refused(413, messages.get("bulk.error.toolarge", what, bytesText(limits.maxBytes().toBytes())));
        }
    }

    private static List<String> samples(BulkTable.Prepared table, int column) {
        List<String> samples = new ArrayList<>();
        for (DelimitedText.Row row : table.rows()) {
            if (samples.size() == SAMPLES) {
                break;
            }
            String cell = column < row.cells().size() ? row.cells().get(column).trim() : "";
            if (!cell.isEmpty()) {
                String line = BulkExport.oneLine(cell);
                samples.add(line.codePointCount(0, line.length()) <= SAMPLE_CHARS ? line
                        : line.substring(0, line.offsetByCodePoints(0, SAMPLE_CHARS)) + "…");
            }
        }
        return samples;
    }

    private String costNote(ConsoleUser user, long rows) {
        EditorCost cost = EditorCost.of(plans.usage(user.clientId()));
        if (!cost.enforced()) {
            return null;
        }
        String note = cost.trademarkIncluded() ? messages.get("bulk.cost.note.both", rows * cost.units(), rows)
                : messages.get("bulk.cost.note.rules", rows * cost.units(), rows);
        if (cost.monthlyQuota() != null) {
            note += " " + messages.get("bulk.cost.remaining", cost.remaining(), cost.monthlyQuota(), cost.resetDate());
        } else {
            note += " " + messages.get("bulk.cost.unlimited");
        }
        return note;
    }

    private Optional<BulkMappingStore.Stored> stored(ConsoleUser user, EditorMarketplace marketplace) {
        return accountId(user).flatMap(id -> mappingStore.find(id, marketplace.code()));
    }

    private Set<String> rememberedLabels(ConsoleUser user, EditorMarketplace marketplace) {
        return stored(user, marketplace).map(s -> (Set<String>) new HashSet<>(s.columns().keySet())).orElse(Set.of());
    }

    private void remember(ConsoleUser user, EditorMarketplace marketplace, BulkMappingStore.Stored stored) {
        accountId(user).ifPresent(id -> mappingStore.save(id, marketplace.code(), stored));
    }

    private Optional<java.util.UUID> accountId(ConsoleUser user) {
        return accounts.findByEmail(user.email()).map(ConsoleAccount::getId);
    }

    private static char delimiter(String name) {
        if (name == null) {
            return '\t';
        }
        return switch (name) {
            case "comma" -> ',';
            case "semicolon" -> ';';
            default -> '\t';
        };
    }

    private static String delimiterName(char delimiter) {
        return switch (delimiter) {
            case ',' -> "comma";
            case ';' -> "semicolon";
            default -> "tab";
        };
    }

    static String bytesText(long bytes) {
        if (bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0) {
            return (bytes / (1024 * 1024)) + " MB";
        }
        return Math.max(1, bytes / 1024) + " KB";
    }

    static EditorMarketplace marketplace(String code) {
        return EditorMarketplace.parse(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown marketplace."));
    }
}
