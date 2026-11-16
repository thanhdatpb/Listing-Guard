package com.thanhdatpb.listingguard.api.console.reaudit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.console.ConsoleAccount;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleDates;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.console.bulk.BulkProperties;
import com.thanhdatpb.listingguard.api.console.bulk.ConsoleBulkController;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
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
 * The "Rà định kỳ" page of the console (ADR-035): the seller uploads the file of the listings that are on sale (the export of the
 * marketplace, or the Sheet saved as CSV), the page checks every row and says which risks are new since the last run and which
 * were already known, what changed in the listings, and gives the list of what to fix as a CSV.
 *
 * <p>One step, and the server keeps nothing of the file: it lives in the request. What is stored is the trace of the run (V40: times,
 * versions, hashes, SKUs, counts) and what the check services store for any check. Only a file in a format the input adapters know
 * (ADR-025) is accepted, because a run must read the same columns every time to be compared with the one before it; a table that
 * needs a column mapping belongs to the bulk check. It only checks and suggests: nothing is published or written to a marketplace.
 * Every value the page prints goes through {@code th:text}.
 */
@Controller
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleReauditController {

    static final String VIEW = "console/reaudit";
    static final int MAX_ACCOUNT_CHARS = 80;
    private static final int RECENT = 10;
    private static final int LABELS = 30;

    /** One earlier run, for the table under the form; every text is ready to print. */
    public record RunView(String when, String marketplace, String account, String catalog, int rows, int findings, int fresh) {
    }

    /** A request the page cannot use; the form comes back with the reason and the status. */
    private static final class Refusal extends RuntimeException {

        private final int status;

        Refusal(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final ReauditService service;
    private final ReauditStore store;
    private final BulkProperties limits;
    private final ConsoleAccountRepository accounts;
    private final ObjectMapper mapper;
    private final ConsoleMessages messages;

    public ConsoleReauditController(ReauditService service, ReauditStore store, BulkProperties limits,
                                    ConsoleAccountRepository accounts, ObjectMapper mapper, ConsoleMessages messages) {
        this.service = service;
        this.store = store;
        this.limits = limits;
        this.accounts = accounts;
        this.mapper = mapper;
        this.messages = messages;
    }

    @GetMapping("/console/reaudit")
    public String form(@RequestParam(name = "marketplace", defaultValue = "ETSY") String marketplace,
                       @RequestParam(name = "account", defaultValue = "") String account,
                       @AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        noStore(response);
        base(model, user, marketplace(marketplace), account);
        return VIEW;
    }

    /**
     * Reads the file with the input adapter that recognizes its header, checks every row, and shows the result. A file no adapter
     * recognizes, one of another marketplace than the one chosen, one that is not UTF-8, empty, too large or malformed is refused with
     * the reason and nothing is checked, charged or stored.
     */
    @PostMapping("/console/reaudit/run")
    public String run(@RequestParam(name = "marketplace", defaultValue = "") String marketplaceCode,
                      @RequestParam(name = "account", defaultValue = "") String accountLabel,
                      @RequestParam(name = "file", required = false) MultipartFile file,
                      @AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        noStore(response);
        EditorMarketplace marketplace = marketplace(marketplaceCode);
        String account = cleanAccount(accountLabel);
        try {
            requireAccountLength(account);
            byte[] bytes = bytesOf(file);
            String text = decode(bytes);
            ConfiguredCsvAdapter adapter = ConsoleBulkController.adapterFor(text).orElseThrow(() -> new Refusal(400,
                    messages.get("reaudit.error.noadapter", adapterIds())));
            if (!adapter.marketplace().equals(marketplace.code())) {
                throw new Refusal(400, messages.get("bulk.error.adapter.marketplace", adapter.id(), adapter.marketplace(),
                        marketplace.label()));
            }
            List<ReauditService.Input> inputs = read(adapter, marketplace, bytes);
            ReauditResult result = service.run(user.clientId(), accountId(user).orElse(null), marketplace, adapter.id(), account,
                    inputs);
            base(model, user, marketplace, account);
            model.addAttribute("stage", "result");
            model.addAttribute("result", result);
            return VIEW;
        } catch (Refusal e) {
            return refuse(user, marketplace, account, e.status, e.getMessage(), model, response);
        } catch (PlanNotAllowedException e) {
            return refuse(user, marketplace, account, 403, messages.get("bulk.error.plan", e.plan(), service.packName(e.pack())),
                    model, response);
        } catch (QuotaExceededException e) {
            return refuse(user, marketplace, account, 429, messages.get("bulk.error.quota", service.quota(e)), model, response);
        }
    }

    // ---- the file

    private byte[] bytesOf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new Refusal(400, messages.get("bulk.error.nofile"));
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt"))) {
            throw new Refusal(400, messages.get("bulk.error.extension"));
        }
        if (file.getSize() > limits.maxBytes().toBytes()) {
            throw new Refusal(413, messages.get("bulk.error.toolarge", messages.get("bulk.error.what.file"),
                    bytesText(limits.maxBytes().toBytes())));
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new Refusal(400, messages.get("bulk.error.readfile"));
        }
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new Refusal(400, messages.get("bulk.error.notutf8"));
        }
    }

    private List<ReauditService.Input> read(ConfiguredCsvAdapter adapter, EditorMarketplace marketplace, byte[] bytes) {
        List<ReauditService.Input> inputs = new ArrayList<>();
        try (ListingInputAdapter.ListingReader reader = adapter.open(new ByteArrayInputStream(bytes),
                new ListingInputAdapter.Limits(limits.maxBytes().toBytes(), limits.maxRows()))) {
            ListingInputAdapter.ListingRecord record;
            while ((record = reader.next()) != null) {
                int line = (int) record.line();
                if (!record.errors().isEmpty()) {
                    inputs.add(ReauditService.Input.unreadable(line, record.id(),
                            messages.get("bulk.row.problem", fieldErrors(record.errors()))));
                    continue;
                }
                JsonNode tree = mapper.valueToTree(record.listing());
                ObjectNode listing = StandardListingAdapter.toStandardInput(marketplace.code(), (ObjectNode) tree);
                inputs.add(ReauditService.Input.of(line, record.id(), listing));
            }
        } catch (IOException | RuntimeException e) {
            throw new Refusal(400, adapterFailure(e));
        }
        if (inputs.isEmpty()) {
            throw new Refusal(400, messages.get("bulk.error.headeronly"));
        }
        return inputs;
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

    /** The ids of the input adapters whose files the page reads, for the message that says what it accepts. */
    private static String adapterIds() {
        return String.join(", ", com.thanhdatpb.listingguard.input.BuiltinAdapterCatalog.adapters().stream()
                .filter(adapter -> adapter instanceof ConfiguredCsvAdapter).map(ListingInputAdapter::id).toList());
    }

    // ---- the account label

    /** The label the seller typed, with every run of blanks and control characters as one space and nothing at either end. */
    static String cleanAccount(String label) {
        return label == null ? "" : label.replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
    }

    private void requireAccountLength(String account) {
        if (account.codePointCount(0, account.length()) > MAX_ACCOUNT_CHARS) {
            throw new Refusal(400, messages.get("reaudit.error.account.long", MAX_ACCOUNT_CHARS));
        }
    }

    // ---- the pages

    private String refuse(ConsoleUser user, EditorMarketplace marketplace, String account, int status, String message, Model model,
                          HttpServletResponse response) {
        response.setStatus(status);
        base(model, user, marketplace, account);
        model.addAttribute("error", message);
        return VIEW;
    }

    private void base(Model model, ConsoleUser user, EditorMarketplace marketplace, String account) {
        model.addAttribute("email", user.email());
        model.addAttribute("marketplace", marketplace);
        model.addAttribute("marketplaces", EditorMarketplace.values());
        model.addAttribute("account", account);
        model.addAttribute("stage", "form");
        model.addAttribute("fileHint", messages.get("reaudit.file.hint", limits.maxRows(), bytesText(limits.maxBytes().toBytes())));
        model.addAttribute("labels", store.accountLabels(user.clientId(), LABELS));
        model.addAttribute("recent", store.recent(user.clientId(), RECENT).stream().map(this::view).toList());
        model.addAttribute("notice", service.notice(user.clientId()).orElse(null));
    }

    private RunView view(ReauditStore.Run run) {
        return new RunView(ConsoleDates.dateTime(messages, run.startedAt()), EditorMarketplace.parse(run.marketplace())
                .map(EditorMarketplace::label).orElse(run.marketplace()), run.account(), run.catalogVersion(), run.rowsTotal(),
                run.findings(), run.findingsNew());
    }

    private Optional<UUID> accountId(ConsoleUser user) {
        return accounts.findByEmail(user.email()).map(ConsoleAccount::getId);
    }

    private static void noStore(HttpServletResponse response) {
        // the result holds findings that can quote a listing: nothing may keep a copy
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    private static String bytesText(long bytes) {
        if (bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0) {
            return (bytes / (1024 * 1024)) + " MB";
        }
        return Math.max(1, bytes / 1024) + " KB";
    }

    private static EditorMarketplace marketplace(String code) {
        return EditorMarketplace.parse(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown marketplace."));
    }
}
