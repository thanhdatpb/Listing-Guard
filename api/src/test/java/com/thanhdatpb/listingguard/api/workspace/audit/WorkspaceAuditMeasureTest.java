package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.console.ConsoleMessagesTestSupport;
import com.thanhdatpb.listingguard.api.listing.EtsyNormalizer;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditModels.StoredFinding;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Level;
import com.thanhdatpb.listingguard.api.workspace.audit.AuditTypes.Source;
import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.ruleengine.RuleRun;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.Trigrams;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Measures the compute part of the background audit on synthetic data without a database or Docker (ADR-061 §10): 5,000 listings (a title, 13
 * tags, a description), a large catalog held in memory, and for each listing the work an audit does outside PostgreSQL: the input hash, the rules
 * with their positions, the trademark scan, the mapping of every span back to the stored text, the marked text of the detail page, and the alert
 * summary. It does <b>not</b> measure the database (queue, reads, writes), the real catalog, the real rulesets' effect on real listings or a real
 * mail server; those are "not measured" in {@code docs/deploy-readiness.md} with the command to run them.
 *
 * <p>Off unless {@code -Dworkspace.audit.measure=true}. Settings: {@code -Dworkspace.audit.measure.listings} (5000) and
 * {@code -Dworkspace.audit.measure.marks} (500000). The result is printed and written to {@code target/workspace-audit-measure.md}.
 */
@EnabledIfSystemProperty(named = "workspace.audit.measure", matches = "true")
class WorkspaceAuditMeasureTest {

    private static final String[] WORDS = {"soft", "cozy", "gift", "mug", "ceramic", "hoodie", "print", "vintage", "custom", "funny", "cat", "dog",
            "garden", "tea", "coffee", "plant", "poster", "sticker", "tote", "bag", "candle", "linen", "wool", "sunset", "forest", "river"};

    private static int property(String name, int fallback) {
        return Integer.parseInt(System.getProperty(name, String.valueOf(fallback)));
    }

    private static String word(Random random) {
        return WORDS[random.nextInt(WORDS.length)];
    }

    private static final String CONSONANTS = "bdfgklmnprstvz";
    private static final String VOWELS = "aeiou";

    /** An invented pronounceable word of 3 to 5 syllables, such as "kumorabi": the marks of the synthetic catalog (letters only, no real mark). */
    private static String invented(Random random) {
        StringBuilder word = new StringBuilder();
        int syllables = 3 + random.nextInt(3);
        for (int i = 0; i < syllables; i++) {
            word.append(CONSONANTS.charAt(random.nextInt(CONSONANTS.length()))).append(VOWELS.charAt(random.nextInt(VOWELS.length())));
        }
        return word.toString();
    }

    /**
     * A catalog held in memory the way the database serves one: exact lookup by normalized form and candidates by trigram, from an inverted
     * index (a trigram to the marks that contain it) with pg_trgm's similarity. {@code InMemoryTrademarkCatalog} compares a form with every mark, which is
     * right for a test of a few marks and unusable at this size.
     */
    private static final class IndexedCatalog implements TrademarkCatalog {
        private final String version;
        private final Map<String, CatalogMark> exact = new HashMap<>();
        private final List<CatalogMark> marks = new ArrayList<>();
        private final int[] sizes;
        private final Map<String, int[]> postings = new HashMap<>();

        IndexedCatalog(String version, List<String> normalizedMarks) {
            this.version = version;
            sizes = new int[normalizedMarks.size()];
            Map<String, List<Integer>> building = new HashMap<>();
            for (String normalized : normalizedMarks) {
                int id = marks.size();
                CatalogMark mark = new CatalogMark(normalized, normalized);
                marks.add(mark);
                exact.put(normalized, mark);
                Set<String> trigrams = Trigrams.of(normalized);
                sizes[id] = trigrams.size();
                for (String trigram : trigrams) {
                    building.computeIfAbsent(trigram, t -> new ArrayList<>()).add(id);
                }
            }
            building.forEach((trigram, ids) -> postings.put(trigram, ids.stream().mapToInt(Integer::intValue).toArray()));
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public Map<String, CatalogMark> findExact(Collection<String> forms) {
            Map<String, CatalogMark> found = new TreeMap<>();
            for (String form : forms) {
                CatalogMark mark = exact.get(form);
                if (mark != null) {
                    found.put(form, mark);
                }
            }
            return found;
        }

        @Override
        public Map<String, List<CatalogMark>> fuzzyCandidates(Collection<String> forms, double threshold) {
            Map<String, List<CatalogMark>> result = new TreeMap<>();
            for (String form : forms) {
                Set<String> query = Trigrams.of(form);
                if (query.isEmpty()) {
                    continue;
                }
                Map<Integer, int[]> shared = new HashMap<>();
                for (String trigram : query) {
                    for (int id : postings.getOrDefault(trigram, new int[0])) {
                        shared.computeIfAbsent(id, k -> new int[1])[0]++;
                    }
                }
                List<CatalogMark> candidates = new ArrayList<>();
                shared.forEach((id, count) -> {
                    double similarity = (double) count[0] / (query.size() + sizes[id] - count[0]);
                    if (similarity >= threshold) {
                        candidates.add(marks.get(id));
                    }
                });
                if (!candidates.isEmpty()) {
                    candidates.sort(Comparator.comparing(CatalogMark::normalized));
                    result.put(form, candidates);
                }
            }
            return result;
        }
    }

    @Test
    void measure() throws IOException {
        int listings = property("workspace.audit.measure.listings", 5000);
        int marks = property("workspace.audit.measure.marks", 500_000);
        ObjectMapper mapper = new ObjectMapper();
        RulesetConfig rules = RulesetLoader.loadClasspath("rulesets/etsy-2026-12.yaml");
        TrademarkRuleset trademarkRuleset = TrademarkRulesetLoader.loadClasspath("rulesets/trademark-risk-2026-11b.yaml");
        TextNormalizer normalizer = new TextNormalizer(trademarkRuleset.normalization());
        RuleEngine engine = new RuleEngine(RuleRegistry.withDefaults());
        EtsyNormalizer etsy = new EtsyNormalizer();
        Set<String> scope = Set.of("title", "description", "tags", "price", "quantity", "category", "who_made", "when_made", "materials");

        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP).toList();
        System.gc();
        long baseline = used();

        long catalogStart = System.nanoTime();
        Random generator = new Random(6000);
        Set<String> unique = new java.util.LinkedHashSet<>();
        while (unique.size() < marks) {
            unique.add(normalizer.normalizeMark(invented(generator)));
        }
        List<String> catalogMarks = new ArrayList<>(unique);
        unique = null;
        IndexedCatalog catalog = new IndexedCatalog("measure-1", catalogMarks);
        TrademarkScanner scanner = new TrademarkScanner(trademarkRuleset, catalog);
        long catalogMillis = (System.nanoTime() - catalogStart) / 1_000_000;
        System.gc();
        long afterCatalog = used();
        // the marks stay referenced by the catalog; the list is kept only to pick the marks of the listings that carry one
        pools.forEach(MemoryPoolMXBean::resetPeakUsage);

        Random random = new Random(60);
        long[] perListing = new long[listings];
        int withTrademark = 0;
        int withRule = 0;
        int mapped = 0;
        int unmapped = 0;
        int highlightedPieces = 0;
        List<AlertDigest.Item> items = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < listings; i++) {
            long t0 = System.nanoTime();
            // about 1 listing in 20 carries a mark of the catalog, with the case, punctuation and extra spaces of a seller
            boolean marked = i % 20 == 0;
            String title = "  " + word(random) + "  " + (marked ? catalogMarks.get(random.nextInt(marks)).toUpperCase(Locale.ROOT) + "!!" : word(random)) + " "
                    + word(random) + " " + word(random) + (i % 40 == 0 ? " free shipping" : "") + " ";
            ObjectNode fields = mapper.createObjectNode();
            fields.put("title", title);
            fields.put("description", "A " + word(random) + " " + word(random) + " for you. ".repeat(20));
            var tags = fields.putArray("tags");
            for (int t = 0; t < 13; t++) {
                tags.add(word(random) + " " + word(random));
            }
            fields.put("price", "19.99");
            fields.put("quantity", 5);
            fields.put("category", "Home");
            fields.put("who_made", "i_did");
            fields.put("when_made", "made_to_order");

            AuditHasher.input(fields);
            Map<String, String> texts = FieldTexts.of(fields);
            Listing listing = etsy.normalize(fields);
            RuleRun run = engine.runDetailed(listing, rules, scope);
            TrademarkResult scan = scanner.scan(TrademarkCheckService.input(listing, null));

            List<StoredFinding> found = new ArrayList<>();
            for (Finding finding : run.findings()) {
                if (finding.severity() == Severity.INFO) {
                    continue;
                }
                var span = finding.matchStart() == null ? java.util.Optional.<TextOffsets.Span>empty()
                        : TextOffsets.fromRule(texts.get(finding.field()), finding.matchStart(), finding.matchEnd());
                withRule++;
                found.add(new StoredFinding(0, found.size(), "listing-rules", finding.ruleId(), finding.severity() == Severity.ERROR ? Level.ERROR : Level.WARNING,
                        Source.RULE, finding.field(), span.map(TextOffsets.Span::start).orElse(null), span.map(TextOffsets.Span::end).orElse(null), null, null,
                        null, null, null, finding.message(), finding.suggestion(), false));
            }
            for (TrademarkFinding finding : scan.findings()) {
                if (finding.risk() != RiskLevel.HIGH && finding.risk() != RiskLevel.MEDIUM) {
                    continue;
                }
                withTrademark++;
                var e = finding.evidence();
                var span = TextOffsets.fromCanonical(texts.get(e.field()), e.start(), e.end());
                if (span.isPresent()) {
                    mapped++;
                } else {
                    unmapped++;
                }
                found.add(new StoredFinding(0, found.size(), "trademark-risk", finding.ruleId(), finding.risk() == RiskLevel.HIGH ? Level.HIGH : Level.MEDIUM,
                        Source.CATALOG, e.field(), span.map(TextOffsets.Span::start).orElse(null), span.map(TextOffsets.Span::end).orElse(null), e.matchedMark(),
                        null, "measure-1", null, null, finding.message(), finding.suggestion(), true));
            }
            if (!found.isEmpty()) {
                for (WorkspaceHighlights.Field field : WorkspaceHighlights.build(texts, found)) {
                    highlightedPieces += (int) field.segments().stream().filter(WorkspaceHighlights.Segment::marked).count();
                }
                StoredFinding f = found.get(0);
                if (f.isNew()) {
                    items.add(new AlertDigest.Item(UUID.randomUUID(), "SKU-" + i, "Shop " + (i % 5), f.field(), f.mark(), f.level(),
                            AlertExcerpt.of(texts.get(f.field()), f.spanStart(), f.spanEnd(), 40)));
                }
            }
            perListing[i] = System.nanoTime() - t0;
        }
        long loopMillis = (System.nanoTime() - start) / 1_000_000;
        long digestStart = System.nanoTime();
        AlertDigest.Digest digest = AlertDigest.compose(ConsoleMessagesTestSupport.messages(), Locale.ENGLISH, true, items, 40, "", "");
        long digestMillis = (System.nanoTime() - digestStart) / 1_000_000;
        long peak = pools.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum();

        long[] sorted = perListing.clone();
        Arrays.sort(sorted);
        double mb = 1024.0 * 1024.0;
        String report = String.format(Locale.ROOT, """
                # Workspace audit: compute measurement (synthetic, in memory, no database)

                | What | Value |
                | --- | --- |
                | Listings audited | %d |
                | Catalog marks held in memory | %d |
                | Catalog build time | %d ms |
                | Heap in use before the catalog | %.0f MB |
                | Heap in use with the catalog | %.0f MB (catalog about %.0f MB) |
                | Audit of all listings | %d ms (%.0f listings per second) |
                | Per listing, median / p95 / max | %.2f / %.2f / %.2f ms |
                | Peak heap during the audit (sum of heap pool peaks) | %.0f MB |
                | Rule findings with a position attempted | %d |
                | Trademark findings (high or medium) | %d, mapped to a span %d, no span %d |
                | Marked pieces for the detail pages | %d |
                | Alert summary of %d rows | %d ms, %d characters |
                | JVM | %s %s, %d processors, max heap %.0f MB |

                Not measured here: PostgreSQL (queue, reads, writes), the real USPTO catalog, real listings and the real mail server.
                """, listings, marks, catalogMillis, baseline / mb, afterCatalog / mb, (afterCatalog - baseline) / mb, loopMillis, listings * 1000.0 / Math.max(1, loopMillis),
                sorted[listings / 2] / 1e6, sorted[(int) (listings * 0.95)] / 1e6, sorted[listings - 1] / 1e6, peak / mb, withRule, withTrademark, mapped, unmapped,
                highlightedPieces, items.size(), digestMillis, digest.body().length(), System.getProperty("java.vm.name"), System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / mb);
        System.out.println(report);
        Files.writeString(Path.of("target/workspace-audit-measure.md"), report, StandardCharsets.UTF_8);

        assertThat(withTrademark).isGreaterThan(0);
        assertThat(mapped).isEqualTo(withTrademark);
        assertThat(digest.rows()).isLessThanOrEqualTo(40);
    }

    private static long used() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
