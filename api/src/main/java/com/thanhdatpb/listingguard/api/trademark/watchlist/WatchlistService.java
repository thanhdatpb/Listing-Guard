package com.thanhdatpb.listingguard.api.trademark.watchlist;

import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryInput;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.EntryView;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.LimitsView;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistModels.WatchlistView;
import com.thanhdatpb.listingguard.api.trademark.watchlist.WatchlistStore.StoredEntry;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import com.thanhdatpb.listingguard.trademark.watchlist.Watchlist;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistEntry;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistSettings;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A client's trademark watchlist (ADR-031): read, replace and hand to the trademark scan. Every change is the replacement of
 * the whole list under the client's row lock, so the API's PUT and every console action go through {@link #replace} and two
 * people editing at once cannot lose a change silently ({@code expectedRevision}).
 *
 * <p>An entry is identified by the normalized form of its mark (the served trademark ruleset's normalizer): it keeps its id,
 * {@code addedBy} and {@code addedAt} across replacements. Every normalized form of a list (marks and aliases, enabled or not)
 * is unique. Values are trimmed and blank aliases dropped; nothing else is changed.
 */
@Service
public class WatchlistService {

    /** The serial number of a USPTO application: eight digits. */
    private static final Pattern SERIAL = Pattern.compile("[0-9]{8}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    /** What a scan needs: the list's revision and its enabled entries ({@link Watchlist#EMPTY} when there are none). */
    public record Active(long revision, Watchlist watchlist) {

        public static final Active NONE = new Active(0, Watchlist.EMPTY);
    }

    /**
     * One problem of a submitted list. {@code field} is a path such as {@code entries[3].aliases[1]}; {@code code} and
     * {@code args} let the console say it in the page's language; {@link #message()} says it in English for the API.
     */
    public record Problem(String field, String code, List<String> args) {

        private static final Map<String, String> ENGLISH = Map.ofEntries(
                Map.entry("too-many-entries", "The list has {0} entries; at most {1} are allowed."),
                Map.entry("missing-entry", "The entry is missing."),
                Map.entry("required", "The mark is required."),
                Map.entry("too-long", "Longer than {0} characters."),
                Map.entry("no-words", "Has no letter or digit, so it can never match."),
                Map.entry("too-many-words", "Has {0} words after normalization; at most {1} are allowed."),
                Map.entry("control", "Holds a control character."),
                Map.entry("too-many-aliases", "Has {0} aliases; at most {1} are allowed."),
                Map.entry("duplicate", "''{0}'' is the same as {1} after normalization (case, accents and punctuation are ignored)."),
                Map.entry("class-range", "Nice class {0} is not between 1 and 45."),
                Map.entry("class-duplicate", "Nice class {0} is listed twice."),
                Map.entry("serial-format", "A serial number is eight digits."));

        /** Every code a problem can have; the console has a message for each. */
        public static final Set<String> CODES = ENGLISH.keySet();

        public Problem {
            args = List.copyOf(args);
        }

        public String message() {
            return new MessageFormat(ENGLISH.get(code), Locale.ROOT).format(args.toArray());
        }
    }

    /** The submitted list has problems; nothing was stored. */
    public static final class InvalidWatchlistException extends RuntimeException {

        private final transient List<Problem> problems;

        public InvalidWatchlistException(List<Problem> problems) {
            super("The watchlist is not valid; see errors.");
            this.problems = List.copyOf(problems);
        }

        public List<Problem> problems() {
            return problems;
        }
    }

    /** The list changed since the revision the caller read; nothing was stored. */
    public static final class RevisionMismatchException extends RuntimeException {

        public static final String CODE = "watchlist-revision-mismatch";

        private final long currentRevision;

        public RevisionMismatchException(long expected, long currentRevision) {
            super("The watchlist is at revision " + currentRevision + ", not " + expected
                    + ": it changed since it was read. Read it again and apply the change to the current list.");
            this.currentRevision = currentRevision;
        }

        public long currentRevision() {
            return currentRevision;
        }
    }

    private final WatchlistStore store;
    private final WatchlistProperties properties;
    private final WatchlistSettings settings;
    private final TextNormalizer normalizer;
    private final PlanService plans;
    private final TransactionTemplate transactions;

    public WatchlistService(WatchlistStore store, WatchlistProperties properties, WatchlistSettings settings,
                            TrademarkRulesets rulesets, PlanService plans, TransactionTemplate transactions) {
        this.store = store;
        this.properties = properties;
        this.settings = settings;
        this.normalizer = new TextNormalizer(rulesets.ruleset().normalization());
        this.plans = plans;
        this.transactions = transactions;
    }

    public LimitsView limits() {
        return new LimitsView(properties.maxEntries(), properties.maxAliases(), properties.maxTextChars(),
                properties.maxNoteChars(), properties.maxTokens());
    }

    public WatchlistSettings settings() {
        return settings;
    }

    /**
     * The client's list, an empty one (revision 0) when it never had one.
     *
     * @throws com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException where plans are on and the plan has no {@code trademark-risk}
     */
    public WatchlistView read(UUID clientId) {
        plans.requirePack(clientId, Pack.TRADEMARK_RISK);
        return view(clientId);
    }

    /**
     * Replaces the client's whole list. An identical list changes nothing (not even the revision).
     *
     * @param actor            {@code console:<account id>} or {@code api-key:<key id>}
     * @param expectedRevision the revision the caller read, or null to replace whatever is stored
     * @throws InvalidWatchlistException  with every problem of the list
     * @throws RevisionMismatchException  if {@code expectedRevision} is not the current one
     */
    public WatchlistView replace(UUID clientId, List<EntryInput> entries, String actor, Long expectedRevision) {
        plans.requirePack(clientId, Pack.TRADEMARK_RISK);
        List<EntryInput> cleaned = clean(entries);
        List<Problem> problems = validate(cleaned);
        if (!problems.isEmpty()) {
            throw new InvalidWatchlistException(problems);
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return transactions.execute(status -> {
            long current = store.lock(clientId);
            if (expectedRevision != null && expectedRevision != current) {
                throw new RevisionMismatchException(expectedRevision, current);
            }
            List<StoredEntry> existing = store.entries(clientId);
            Map<String, StoredEntry> byForm = new HashMap<>();
            existing.forEach(entry -> byForm.put(normalizer.normalizeMark(entry.mark()), entry));
            List<StoredEntry> next = new ArrayList<>();
            for (EntryInput input : cleaned) {
                StoredEntry before = byForm.get(normalizer.normalizeMark(input.mark()));
                boolean enabled = input.enabled() == null || input.enabled();
                if (before == null) {
                    next.add(new StoredEntry(UUID.randomUUID(), input.mark(), input.aliases(), input.niceClasses(),
                            input.serialNumber(), input.note(), enabled, actor, now, actor, now));
                } else if (sameContent(before, input, enabled)) {
                    next.add(before);
                } else {
                    next.add(new StoredEntry(before.id(), input.mark(), input.aliases(), input.niceClasses(),
                            input.serialNumber(), input.note(), enabled, before.addedBy(), before.addedAt(), actor, now));
                }
            }
            if (!next.equals(existing)) {
                store.replace(clientId, current + 1, actor, now, next);
            }
            return view(clientId);
        });
    }

    /**
     * The enabled entries of the client's list for a trademark scan. No plan check: the check that asks has made it. One
     * indexed query per check.
     */
    public Active active(UUID clientId) {
        List<StoredEntry> enabled = store.enabledEntries(clientId);
        if (enabled.isEmpty()) {
            return Active.NONE;
        }
        long revision = store.header(clientId).map(WatchlistStore.Header::revision).orElse(0L);
        return new Active(revision, new Watchlist(settings, enabled.stream().map(WatchlistService::entry).toList()));
    }

    /** The normalized form of a mark or alias, as the scan and the identity of an entry use it. */
    public String normalizedForm(String text) {
        return normalizer.normalizeMark(text == null ? "" : text);
    }

    /**
     * Trims every value, drops blank aliases, sorts the Nice classes ascending and turns blank optional values into null; dedupes
     * nothing (a repeated alias or class is a problem the client sees). A null list item stays, so {@link #validate} can name it.
     */
    public static List<EntryInput> clean(List<EntryInput> entries) {
        List<EntryInput> cleaned = new ArrayList<>();
        for (EntryInput entry : entries == null ? List.<EntryInput>of() : entries) {
            if (entry == null) {
                cleaned.add(null);
                continue;
            }
            List<String> aliases = new ArrayList<>();
            if (entry.aliases() != null) {
                entry.aliases().stream().filter(Objects::nonNull).map(String::strip).filter(a -> !a.isEmpty()).forEach(aliases::add);
            }
            List<Integer> classes = entry.niceClasses() == null ? List.of()
                    : entry.niceClasses().stream().filter(Objects::nonNull).sorted().toList();
            cleaned.add(new EntryInput(entry.mark() == null ? "" : entry.mark().strip(), aliases, classes,
                    blankToNull(entry.serialNumber()), blankToNull(entry.note()), entry.enabled()));
        }
        return cleaned;
    }

    /** Every problem of a cleaned list ({@link #clean}), in the order of the list; empty when it can be stored. */
    public List<Problem> validate(List<EntryInput> entries) {
        List<Problem> problems = new ArrayList<>();
        if (entries.size() > properties.maxEntries()) {
            problems.add(new Problem("entries", "too-many-entries",
                    List.of(String.valueOf(entries.size()), String.valueOf(properties.maxEntries()))));
        }
        Map<String, String> forms = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            String path = "entries[" + i + "]";
            EntryInput entry = entries.get(i);
            if (entry == null) {
                problems.add(new Problem(path, "missing-entry", List.of()));
                continue;
            }
            if (entry.mark().isEmpty()) {
                problems.add(new Problem(path + ".mark", "required", List.of()));
            } else {
                text(problems, forms, path + ".mark", entry.mark());
            }
            if (entry.aliases().size() > properties.maxAliases()) {
                problems.add(new Problem(path + ".aliases", "too-many-aliases",
                        List.of(String.valueOf(entry.aliases().size()), String.valueOf(properties.maxAliases()))));
            }
            for (int j = 0; j < entry.aliases().size(); j++) {
                text(problems, forms, path + ".aliases[" + j + "]", entry.aliases().get(j));
            }
            Set<Integer> classes = new LinkedHashSet<>();
            for (int j = 0; j < entry.niceClasses().size(); j++) {
                int niceClass = entry.niceClasses().get(j);
                String classPath = path + ".niceClasses[" + j + "]";
                if (niceClass < 1 || niceClass > 45) {
                    problems.add(new Problem(classPath, "class-range", List.of(String.valueOf(niceClass))));
                } else if (!classes.add(niceClass)) {
                    problems.add(new Problem(classPath, "class-duplicate", List.of(String.valueOf(niceClass))));
                }
            }
            if (entry.serialNumber() != null && !SERIAL.matcher(entry.serialNumber()).matches()) {
                problems.add(new Problem(path + ".serialNumber", "serial-format", List.of()));
            }
            if (entry.note() != null) {
                if (entry.note().codePointCount(0, entry.note().length()) > properties.maxNoteChars()) {
                    problems.add(new Problem(path + ".note", "too-long", List.of(String.valueOf(properties.maxNoteChars()))));
                } else if (CONTROL.matcher(entry.note().replace('\n', ' ')).find()) {
                    problems.add(new Problem(path + ".note", "control", List.of()));
                }
            }
        }
        return problems;
    }

    private void text(List<Problem> problems, Map<String, String> forms, String path, String text) {
        if (text.codePointCount(0, text.length()) > properties.maxTextChars()) {
            problems.add(new Problem(path, "too-long", List.of(String.valueOf(properties.maxTextChars()))));
            return;
        }
        if (CONTROL.matcher(text).find()) {
            problems.add(new Problem(path, "control", List.of()));
            return;
        }
        String form = normalizer.normalizeMark(text);
        if (form.isEmpty()) {
            problems.add(new Problem(path, "no-words", List.of()));
            return;
        }
        int words = form.split(" ", -1).length;
        if (words > properties.maxTokens()) {
            problems.add(new Problem(path, "too-many-words", List.of(String.valueOf(words), String.valueOf(properties.maxTokens()))));
            return;
        }
        String earlier = forms.putIfAbsent(form, path);
        if (earlier != null) {
            problems.add(new Problem(path, "duplicate", List.of(text, earlier)));
        }
    }

    private WatchlistView view(UUID clientId) {
        List<StoredEntry> entries = store.entries(clientId);
        WatchlistStore.Header header = store.header(clientId).orElse(null);
        List<WatchlistEntry> enabled = entries.stream().filter(StoredEntry::enabled).map(WatchlistService::entry).toList();
        String version = enabled.isEmpty() ? null : new Watchlist(settings, enabled).contentHash();
        return new WatchlistView(header == null ? 0 : header.revision(), version, header == null ? null : header.updatedAt(),
                entries.stream().map(WatchlistService::view).toList(), limits());
    }

    private static EntryView view(StoredEntry entry) {
        return new EntryView(entry.id(), entry.mark(), entry.aliases(), entry.niceClasses(), entry.serialNumber(), entry.note(),
                entry.enabled(), entry.addedBy(), entry.addedAt(), entry.updatedBy(), entry.updatedAt());
    }

    private static WatchlistEntry entry(StoredEntry entry) {
        return new WatchlistEntry(entry.id().toString(), entry.mark(), entry.aliases(), entry.niceClasses(), entry.serialNumber(),
                entry.note());
    }

    private static boolean sameContent(StoredEntry before, EntryInput input, boolean enabled) {
        return before.mark().equals(input.mark()) && before.aliases().equals(input.aliases())
                && before.niceClasses().equals(input.niceClasses()) && Objects.equals(before.serialNumber(), input.serialNumber())
                && Objects.equals(before.note(), input.note()) && before.enabled() == enabled;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
