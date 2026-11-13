package com.thanhdatpb.listingguard.api.console.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
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
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
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

    /** One column of the mapping step: what it holds (a few cells) and what the page chose for it. */
    public record ColumnView(int index, String letter, String label, List<String> samples, String selected) {
    }

    public record TargetOption(String key, String label) {
    }

    public record SeparatorView(String key, String label, String selected) {
    }

    public record SeparatorOption(String key, String label) {
    }

    /**
     * @param dataField the text to send back, with the line break that an HTML textarea drops at its start put in front of it
     * @param rows      how many data rows the table has
     * @param costNote  what the run can cost, or null when plans are off
     */
    public record MappingPage(String dataField, String delimiter, boolean header, int rows, List<ColumnView> columns,
                              List<SeparatorView> separators, List<String> problems, boolean remember, String costNote,
                              boolean remembered) {
    }

    private final BulkProperties limits;
    private final BulkCheckService service;
    private final BulkMappingStore mappingStore;
    private final ConsoleAccountRepository accounts;
    private final PlanService plans;
    private final ObjectMapper mapper;

    public ConsoleBulkController(BulkProperties limits, BulkCheckService service, BulkMappingStore mappingStore,
                                 ConsoleAccountRepository accounts, PlanService plans, ObjectMapper mapper) {
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
            requireWithinBytes(pasted.getBytes(StandardCharsets.UTF_8).length, "Nội dung dán");
            if (pasted.isBlank()) {
                throw new BulkTable.Refused(400, "Chưa có dữ liệu. Dán các dòng đã chép từ Google Sheet vào ô bên dưới.");
            }
            BulkTable.Prepared table = BulkTable.prepare(pasted, '\t', mode, marketplace, rememberedLabels(user, marketplace),
                    limits.maxRows());
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
                      HttpServletResponse response) {
        noStore(response);
        EditorMarketplace marketplace = marketplace(params.getOrDefault("marketplace", ""));
        String data = params.getOrDefault("data", "");
        try {
            requireWithinBytes(data.getBytes(StandardCharsets.UTF_8).length, "Nội dung");
            BulkTable.HeaderMode mode = "yes".equals(params.get("header")) ? BulkTable.HeaderMode.YES : BulkTable.HeaderMode.NO;
            char delimiter = delimiter(params.get("delimiter"));
            BulkTable.Prepared table = BulkTable.prepare(data, delimiter, mode, marketplace, Set.of(), limits.maxRows());
            ColumnMapping mapping = ColumnMapping.fromParams(marketplace, table.columns(), params);
            boolean remember = params.containsKey("remember");
            List<String> problems = mapping.problems(marketplace, table.labels());
            if (!problems.isEmpty()) {
                response.setStatus(HttpStatus.BAD_REQUEST.value());
                return mappingStep(user, marketplace, table, data, new Remembered(mapping, problems, remember), model);
            }
            List<BulkCheckService.Input> inputs = new ArrayList<>();
            for (DelimitedText.Row row : table.rows()) {
                BulkListingMapper.Mapped mapped = BulkListingMapper.map(marketplace, mapping, table.labels(), row.cells());
                if (mapped.blank()) {
                    inputs.add(BulkCheckService.Input.empty(row.line()));
                } else if (mapped.error() != null) {
                    inputs.add(BulkCheckService.Input.unreadable(row.line(), mapped.sku(), mapped.error()));
                } else {
                    inputs.add(BulkCheckService.Input.of(row.line(), mapped.sku(), mapped.parentSku(), mapped.theme(),
                            mapped.attributes(), mapped.listing()));
                }
            }
            BulkResult result = service.run(user.clientId(), marketplace, inputs, table.header());
            if (remember) {
                remember(user, marketplace, mapping.toStored(marketplace, table.labels()));
            }
            return result(user, marketplace, result, List.of(), model);
        } catch (BulkTable.Refused e) {
            return refuse(user, marketplace, Tab.PASTE, e, null, model, response);
        } catch (PlanNotAllowedException e) {
            return refuse(user, marketplace, Tab.PASTE, new BulkTable.Refused(403, "Gói của bạn (" + e.plan() + ") không gồm "
                    + BulkText.packName(e.pack()) + ". Nhờ người quản trị máy chủ chuyển bạn sang gói có chức năng này."), null, model,
                    response);
        } catch (QuotaExceededException e) {
            return refuse(user, marketplace, Tab.PASTE, new BulkTable.Refused(429, "Quota tháng đã hết: " + BulkText.quota(e)
                    + ". Chưa kiểm tra dòng nào. Kết quả đã lưu của listing đã kiểm tra trước đó vẫn trả lời được khi bạn kiểm tra "
                    + "lại từng listing."), null, model, response);
        }
    }

    // ---- step 1: the file

    private String prepareFile(ConsoleUser user, EditorMarketplace marketplace, MultipartFile file, BulkTable.HeaderMode mode,
                               Model model) {
        if (file == null || file.isEmpty()) {
            throw new BulkTable.Refused(400, "Chưa chọn file. Chọn một file .csv, .tsv hoặc .txt.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt"))) {
            throw new BulkTable.Refused(400, "Chỉ nhận file .csv, .tsv hoặc .txt.");
        }
        requireWithinBytes(file.getSize(), "File");
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BulkTable.Refused(400, "Không đọc được file. Thử tải lại.");
        }
        String text = decode(bytes);
        Optional<ConfiguredCsvAdapter> adapter = adapterFor(text);
        if (adapter.isEmpty()) {
            char delimiter = DelimitedText.detectDelimiter(text);
            BulkTable.Prepared table = BulkTable.prepare(text, delimiter, mode, marketplace, rememberedLabels(user, marketplace),
                    limits.maxRows());
            return mappingStep(user, marketplace, table, text, null, model);
        }
        if (!adapter.get().marketplace().equals(marketplace.code())) {
            throw new BulkTable.Refused(400, "File này có dạng “" + adapter.get().id() + "” của " + adapter.get().marketplace()
                    + ", nhưng bạn đã chọn " + marketplace.label() + ". Chọn đúng sàn rồi tải lại.");
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
                    inputs.add(BulkCheckService.Input.unreadable(line, record.id(), "Dữ liệu sai: " + fieldErrors(record.errors()) + "."));
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
            throw new BulkTable.Refused(400, "Chỉ có dòng tiêu đề, chưa có dòng dữ liệu nào.");
        }
        try {
            BulkResult result = service.run(user.clientId(), marketplace, inputs, true);
            List<String> notes = new ArrayList<>();
            notes.add("File được nhận dạng là “" + adapter.id() + "”: các cột đã được đọc theo định dạng đó, bạn không cần chọn cột.");
            if (families) {
                notes.add("File có dòng cha/con, nhưng định dạng này chưa đọc thuộc tính biến thể nên chưa kiểm tra biến thể. Dán bảng "
                        + "có cột SKU cha và thuộc tính để kiểm tra biến thể.");
            }
            return result(user, marketplace, result, notes, model);
        } catch (PlanNotAllowedException e) {
            throw new BulkTable.Refused(403, "Gói của bạn (" + e.plan() + ") không gồm " + BulkText.packName(e.pack()) + ".");
        } catch (QuotaExceededException e) {
            throw new BulkTable.Refused(429, "Quota tháng đã hết: " + BulkText.quota(e) + ". Chưa kiểm tra dòng nào.");
        }
    }

    /** The built-in CSV adapter whose columns the header holds (the one with most columns when several do), or empty. */
    private static Optional<ConfiguredCsvAdapter> adapterFor(String text) {
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

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new BulkTable.Refused(400, "File không phải UTF-8. Lưu lại thành CSV UTF-8 (Google Sheets: Tệp, Tải xuống, "
                    + "Giá trị phân tách bằng dấu phẩy) rồi tải lên.");
        }
    }

    private static String fieldErrors(List<ListingInputAdapter.FieldError> errors) {
        List<String> messages = new ArrayList<>();
        for (ListingInputAdapter.FieldError error : errors) {
            messages.add(switch (error.code()) {
                case "invalid-number" -> error.field() + " không phải số thập phân thường (dùng dấu chấm, không có ký hiệu tiền tệ)";
                case "missing-column" -> "dòng thiếu ô cho cột của " + error.field();
                default -> error.field() + ": " + error.code();
            });
        }
        return String.join("; ", messages);
    }

    private String adapterFailure(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("row limit")) {
            return "File có nhiều hơn " + limits.maxRows() + " dòng dữ liệu, vượt giới hạn của một lần kiểm tra. Chia file thành nhiều phần.";
        }
        if (message.contains("byte limit")) {
            return "File vượt giới hạn " + bytesText(limits.maxBytes().toBytes()) + ".";
        }
        return "File không đọc được: không phải CSV hợp lệ (dấu nháy không đóng, hoặc dòng thiếu cột). Kiểm tra file rồi tải lại.";
    }

    // ---- the pages

    /** The mapping a refused run comes back with: the seller's choices, the reasons and the "remember" box. */
    private record Remembered(ColumnMapping mapping, List<String> problems, boolean remember) {
    }

    private String mappingStep(ConsoleUser user, EditorMarketplace marketplace, BulkTable.Prepared table, String text,
                               Remembered again, Model model) {
        Optional<BulkMappingStore.Stored> stored = again == null ? stored(user, marketplace) : Optional.empty();
        ColumnMapping mapping = again != null ? again.mapping() : ColumnMapping.suggest(marketplace, table.labels(), stored.orElse(null));
        List<ColumnView> columns = new ArrayList<>();
        for (int i = 0; i < table.columns(); i++) {
            columns.add(new ColumnView(i, BulkTable.letters(i), table.labels().get(i), samples(table, i), mapping.targetOf(i)));
        }
        List<SeparatorView> separators = new ArrayList<>();
        for (BulkTargets.Target target : BulkTargets.of(marketplace)) {
            if (target.separable()) {
                separators.add(new SeparatorView(target.key(), target.label(), mapping.separatorOf(target)));
            }
        }
        long judgeable = table.rows().stream().filter(row -> !row.blank()).count();
        base(model, user, marketplace, Tab.PASTE);
        model.addAttribute("stage", "mapping");
        model.addAttribute("targets", BulkTargets.of(marketplace).stream().map(t -> new TargetOption(t.key(), t.label())).toList());
        model.addAttribute("separatorOptions", BulkTargets.SEPARATORS.stream().map(s -> new SeparatorOption(s.key(), s.label())).toList());
        model.addAttribute("mapping", new MappingPage("\n" + text, delimiterName(table.delimiter()), table.header(), table.rows().size(),
                columns, separators, again == null ? List.of() : again.problems(), again == null || again.remember(),
                costNote(user, judgeable), stored.isPresent()));
        return VIEW;
    }

    private String result(ConsoleUser user, EditorMarketplace marketplace, BulkResult result, List<String> extraNotes, Model model) {
        base(model, user, marketplace, Tab.PASTE);
        model.addAttribute("stage", "result");
        List<String> notes = new ArrayList<>(extraNotes);
        notes.addAll(result.notes());
        model.addAttribute("result", new BulkResult(result.marketplace(), result.header(), result.rows(), result.counts(), notes,
                result.disclaimer(), result.sheetBlock(), result.csvHref()));
        return VIEW;
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
        model.addAttribute("maxRows", limits.maxRows());
        model.addAttribute("maxBytesText", bytesText(limits.maxBytes().toBytes()));
    }

    private static void noStore(HttpServletResponse response) {
        // the pages hold what the seller pasted: nothing may keep a copy
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    // ---- small helpers

    private void requireWithinBytes(long size, String what) {
        if (size > limits.maxBytes().toBytes()) {
            throw new BulkTable.Refused(413, what + " lớn hơn giới hạn " + bytesText(limits.maxBytes().toBytes())
                    + " của một lần kiểm tra. Chia nhỏ rồi thử lại.");
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
        String note = "Tối đa " + (rows * cost.units()) + " đơn vị quota cho " + rows + " dòng (mỗi dòng: quy tắc listing 1"
                + (cost.trademarkIncluded() ? " + nhãn hiệu 1" : "") + "; mỗi nhóm biến thể thêm 1). Listing đã kiểm tra trước đó "
                + "không tốn thêm.";
        if (cost.monthlyQuota() != null) {
            note += " Còn lại tháng này: " + cost.remaining() + "/" + cost.monthlyQuota() + ", đặt lại ngày " + cost.resetDate()
                    + " (UTC).";
        } else {
            note += " Quota của bạn không giới hạn.";
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
