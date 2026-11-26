package com.thanhdatpb.listingguard.api.console.phrase;

import com.thanhdatpb.listingguard.api.console.ConsoleFeedbackSupport;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.console.bulk.DelimitedText;
import com.thanhdatpb.listingguard.api.feedback.FeedbackFindings;
import com.thanhdatpb.listingguard.api.feedback.FeedbackSource;
import com.thanhdatpb.listingguard.api.feedback.FeedbackVerdict;
import com.thanhdatpb.listingguard.api.feedback.FindingFeedbackService;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningException;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningProperties;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningRequest;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningRequest.PhraseInput;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import com.thanhdatpb.listingguard.trademark.PhraseScreening;
import com.thanhdatpb.listingguard.trademark.PhraseScreening.Verdict;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping(PhraseConsoleController.PATH)
@ConditionalOnExpression("${listing-guard.console.enabled:false} && ${listing-guard.phrase-screening.enabled:false}")
public class PhraseConsoleController {
    static final String PATH = "/console/phrase-screening";
    public static final long REQUEST_BYTES = 2L * 1024 * 1024;
    private static final int INPUT_BYTES = 1024 * 1024;
    private static final String VIEW = "console/phrase-screening";

    public record InputForm(String mode, String data, Integer phraseColumn, Integer volumeColumn,
                            Boolean remember, Boolean confirmOverflow, String marketplace) {}
    public record Column(int index, String label) {}
    public record Preview(String mode, String carriedData, String marketplace, List<Column> columns,
                   int phraseColumn, int volumeColumn, boolean guessed, boolean remember,
                   Integer total, int duplicates, int dropped, int overflow, int max) {}
    private record Selection(int phrase, int volume, boolean guessed) {}

    private final PhraseScreeningService service;
    private final PhraseScreeningProperties properties;
    private final TrademarkRulesets rulesets;
    private final PhraseConsoleResults results;
    private final ConsoleFeedbackSupport feedbackSupport;
    private final FindingFeedbackService feedback;
    private final ConsoleMessages messages;
    private final JdbcTemplate jdbc;

    public PhraseConsoleController(PhraseScreeningService service, PhraseScreeningProperties properties,
                                   TrademarkRulesets rulesets, PhraseConsoleResults results,
                                   ConsoleFeedbackSupport feedbackSupport, FindingFeedbackService feedback,
                                   ConsoleMessages messages, JdbcTemplate jdbc) {
        this.service = service;
        this.properties = properties;
        this.rulesets = rulesets;
        this.results = results;
        this.feedbackSupport = feedbackSupport;
        this.feedback = feedback;
        this.messages = messages;
        this.jdbc = jdbc;
    }

    @ModelAttribute
    void shell(@AuthenticationPrincipal ConsoleUser user, Model model, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        model.addAttribute("email", user.email());
    }

    @GetMapping
    public String start() {
        return VIEW;
    }

    @PostMapping({"/preview", "/run"})
    public String submit(@AuthenticationPrincipal ConsoleUser user, HttpSession session,
                         @ModelAttribute InputForm form, @RequestParam(required = false) MultipartFile file,
                         HttpServletRequest request, HttpServletResponse response, Model model) throws IOException {
        var owner = owner(user, session);
        boolean run = request.getRequestURI().endsWith("/run");
        boolean fileMode = "file".equals(form.mode());
        String marketplace = form.marketplace() == null ? "ETSY" : form.marketplace().toUpperCase(Locale.ROOT);
        try {
            if (!List.of("ETSY", "AMAZON").contains(marketplace)) {
                throw new IllegalArgumentException("phrase.error.marketplace");
            }
            String text = readInput(form.data(), file);
            PhraseInputTable table = fileMode ? PhraseInputTable.read(text) : null;
            Selection selection = table == null ? new Selection(-1, -1, false) : select(owner.account(), form, table);
            List<Column> columns = table == null ? List.of() : java.util.stream.IntStream.range(0, table.headers().size())
                    .mapToObj(i -> new Column(i, table.headers().get(i).isEmpty() ? messages.get("phrase.column", i + 1)
                            : table.headers().get(i))).toList();
            List<PhraseInput> input = table == null
                    ? Arrays.stream(text.split("\\R")).map(line -> new PhraseInput(line, null)).toList()
                    : selection.phrase() < 0 || selection.phrase() == selection.volume() ? List.of()
                    : table.values(selection.phrase(), selection.volume());
            int max = properties.maxPhrases();
            List<PhraseInput> batch = input.subList(0, Math.min(max, input.size()));
            var prepared = PhraseScreening.prepare(batch.stream()
                    .map(row -> new PhraseScreening.Input(row.text(), row.searchVolume())).toList(),
                    new TextNormalizer(rulesets.ruleset().normalization()));
            int overflow = input.size() - batch.size();
            model.addAttribute("preview", new Preview(fileMode ? "file" : "paste", "\n" + text, marketplace, columns,
                    selection.phrase(), selection.volume(), selection.guessed(), Boolean.TRUE.equals(form.remember()),
                    input.isEmpty() ? null : input.size(), prepared.duplicates(), prepared.dropped(), overflow, max));
            if (!run) {
                return VIEW;
            }
            if (prepared.phrases().isEmpty() || (overflow > 0 && !Boolean.TRUE.equals(form.confirmOverflow()))) {
                return error(model, response, HttpStatus.BAD_REQUEST,
                        prepared.phrases().isEmpty() ? "phrase.error.mapping" : "phrase.error.overflow");
            }
            var result = service.screenConsole(owner.client(), owner.account(), new PhraseScreeningRequest(batch, marketplace, null));
            if (table != null && Boolean.TRUE.equals(form.remember())) {
                remember(owner.account(), table, selection);
            }
            return "redirect:" + PATH + "/" + results.store(owner, result).id();
        } catch (CharacterCodingException e) {
            return error(model, response, HttpStatus.BAD_REQUEST, "phrase.error.encoding");
        } catch (DelimitedText.FormatException e) {
            return error(model, response, HttpStatus.BAD_REQUEST, "phrase.error.table");
        } catch (IllegalArgumentException e) {
            String key = e.getMessage();
            return error(model, response, HttpStatus.BAD_REQUEST,
                    key != null && key.startsWith("phrase.error.") ? key : "phrase.error.input");
        } catch (PhraseScreeningException e) {
            return error(model, response, e.status(), "phrase.error." + e.code());
        } catch (TrademarkUnavailableException e) {
            return error(model, response, HttpStatus.SERVICE_UNAVAILABLE, "phrase.error.unavailable");
        } catch (QuotaExceededException e) {
            return error(model, response, HttpStatus.TOO_MANY_REQUESTS, "phrase.error.quota");
        } catch (PlanNotAllowedException e) {
            return error(model, response, HttpStatus.FORBIDDEN, "phrase.error.plan");
        }
    }

    @GetMapping("/{id}")
    public String result(@AuthenticationPrincipal ConsoleUser user, HttpSession session, @PathVariable UUID id,
                         @RequestParam(required = false) Verdict tab, Model model, HttpServletResponse response) {
        var result = results.find(owner(user, session), id).orElse(null);
        if (result == null) {
            return error(model, response, HttpStatus.GONE, "phrase.error.expired");
        }
        Verdict selected = tab == null ? Verdict.BLOCK : tab;
        var rows = result.forVerdict(selected);
        model.addAttribute("result", result);
        model.addAttribute("selected", selected);
        model.addAttribute("tabs", Verdict.values());
        model.addAttribute("rows", rows);
        model.addAttribute("catalogDate", catalogDate(result.response().catalogVersion()));
        model.addAttribute("phraseFeedback", true);
        feedbackSupport.populate(model, user, rows.stream().flatMap(row -> row.findings().stream())
                .map(PhraseConsoleResults.Finding::target).toList());
        return VIEW;
    }

    @GetMapping("/{id}/export")
    public String export(@AuthenticationPrincipal ConsoleUser user, HttpSession session, @PathVariable UUID id,
                         @RequestParam(defaultValue = "full") String kind, Model model,
                         HttpServletResponse response) throws IOException {
        var result = results.find(owner(user, session), id).orElse(null);
        if (result == null) {
            return error(model, response, HttpStatus.GONE, "phrase.error.expired");
        }
        if ("text".equals(kind)) {
            response.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8).toString());
            response.getWriter().write(PhraseConsoleExports.safeText(result));
            return null;
        }
        boolean safe = "safe".equals(kind);
        if (!safe && !"full".equals(kind)) {
            return error(model, response, HttpStatus.BAD_REQUEST, "phrase.error.input");
        }
        response.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8).toString());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(safe ? "phrase-screening-clear.csv" : "phrase-screening.csv").build().toString());
        response.getWriter().write(PhraseConsoleExports.csv(messages, result, safe));
        return null;
    }

    @PostMapping("/{id}/feedback")
    public String feedback(@AuthenticationPrincipal ConsoleUser user, HttpSession session, @PathVariable UUID id,
                           @RequestParam int position, @RequestParam String verdict,
                           @RequestHeader(name = "HX-Request", defaultValue = "false") boolean fragment,
                           Model model, HttpServletResponse response) {
        var owner = owner(user, session);
        var result = results.find(owner, id).orElse(null);
        if (result == null) {
            return feedbackError(model, response, fragment, HttpStatus.GONE, "phrase.error.expired");
        }
        var entry = result.rows().stream().flatMap(row -> row.findings().stream())
                .filter(item -> item.target().position() == position).findFirst().orElse(null);
        if (entry == null) {
            return feedbackError(model, response, fragment, HttpStatus.NOT_FOUND, "feedback.error.notfound");
        }
        var parsed = FeedbackVerdict.parse(verdict).filter(value -> value != FeedbackVerdict.NOT_USEFUL).orElse(null);
        if (parsed == null) {
            return feedbackError(model, response, fragment, HttpStatus.BAD_REQUEST, "feedback.error.verdict");
        }
        var view = entry.view();
        var metadata = new FeedbackFindings.Resolved(result.response().marketplace(), result.response().analysisVersion(),
                view.ruleId(), FeedbackSource.valueOf(view.source().name()), view.risk().name(),
                result.response().rulesetVersion(), result.response().catalogVersion(), null);
        feedback.recordPhrase(owner.client(), owner.account(), entry.target(), parsed, metadata);
        if (!fragment) {
            return "redirect:" + PATH + "/" + id;
        }
        model.addAttribute("target", entry.target());
        model.addAttribute("phraseFeedback", true);
        model.addAttribute("feedbackEnabled", true);
        model.addAttribute("feedbackSavedKey", entry.target().key());
        model.addAttribute("feedbackVerdicts", Map.of(entry.target().key(), new FindingFeedbackService.Mine(parsed, null)));
        return "console/feedback :: widget(target=${target})";
    }

    private String feedbackError(Model model, HttpServletResponse response, boolean fragment, HttpStatus status, String key) {
        error(model, response, status, key);
        return fragment ? VIEW + " :: phraseError" : VIEW;
    }

    private PhraseConsoleResults.Owner owner(ConsoleUser user, HttpSession session) {
        UUID account = feedbackSupport.accountId(user).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        return new PhraseConsoleResults.Owner(user.clientId(), account, session.getId());
    }

    private static String readInput(String data, MultipartFile file) throws IOException {
        if (file != null && !file.isEmpty()) {
            String filename = file.getOriginalFilename();
            if (filename == null || !filename.toLowerCase(Locale.ROOT).matches(".*\\.(csv|tsv)$")) {
                throw new IllegalArgumentException("phrase.error.filetype");
            }
            if (file.getSize() > INPUT_BYTES) {
                throw new IllegalArgumentException("phrase.error.size");
            }
            data = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(file.getBytes())).toString();
        }
        if (data == null || data.isBlank()) {
            throw new IllegalArgumentException("phrase.error.input");
        }
        if (data.getBytes(StandardCharsets.UTF_8).length > INPUT_BYTES) {
            throw new IllegalArgumentException("phrase.error.size");
        }
        return data;
    }

    private Selection select(UUID account, InputForm form, PhraseInputTable table) {
        if (form.phraseColumn() != null || form.volumeColumn() != null) {
            int phrase = form.phraseColumn() == null ? -1 : form.phraseColumn();
            int volume = form.volumeColumn() == null ? -1 : form.volumeColumn();
            if (phrase < -1 || phrase >= table.headers().size() || volume < -1 || volume >= table.headers().size()) {
                throw new IllegalArgumentException("phrase.error.mapping");
            }
            return new Selection(phrase, volume, false);
        }
        var saved = jdbc.query("SELECT phrase_header, volume_header FROM console_phrase_mapping WHERE account_id = ?",
                (rs, index) -> new Selection(table.remembered(rs.getString(1)), table.remembered(rs.getString(2)), false), account);
        if (!saved.isEmpty() && saved.getFirst().phrase() >= 0) {
            return saved.getFirst();
        }
        int phrase = table.guess(false);
        int volume = table.guess(true);
        return new Selection(phrase, phrase == volume ? -1 : volume, phrase >= 0 || volume >= 0);
    }

    private void remember(UUID account, PhraseInputTable table, Selection selection) {
        String phrase = header(table, selection.phrase());
        if (phrase != null) {
            jdbc.update("""
                    INSERT INTO console_phrase_mapping (account_id, phrase_header, volume_header) VALUES (?, ?, ?)
                    ON CONFLICT (account_id) DO UPDATE SET phrase_header = EXCLUDED.phrase_header, volume_header = EXCLUDED.volume_header
                    """, account, phrase, header(table, selection.volume()));
        }
    }

    private static String header(PhraseInputTable table, int index) {
        if (index < 0) {
            return null;
        }
        String value = table.headers().get(index);
        return !value.isEmpty() && value.codePointCount(0, value.length()) <= 120 && table.remembered(value) == index
                ? value : null;
    }

    private LocalDate catalogDate(String version) {
        return version == null ? null : jdbc.query("SELECT source_date FROM trademark_catalog_version WHERE version = ?",
                (rs, index) -> rs.getObject(1, LocalDate.class), version).stream().findFirst().orElse(null);
    }

    private String error(Model model, HttpServletResponse response, HttpStatus status, String key) {
        response.setStatus(status.value());
        model.addAttribute("phraseError", messages.get(key));
        return VIEW;
    }
}
