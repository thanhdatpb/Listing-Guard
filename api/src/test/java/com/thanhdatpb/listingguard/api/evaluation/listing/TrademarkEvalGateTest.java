package com.thanhdatpb.listingguard.api.evaluation.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer;
import com.thanhdatpb.listingguard.api.evaluation.ListingAnalyzer.PartState;
import com.thanhdatpb.listingguard.api.evaluation.Rate;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleRegistry;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.FindingSource;
import com.thanhdatpb.listingguard.trademark.LlmJudgement;
import com.thanhdatpb.listingguard.trademark.PhraseScreening;
import com.thanhdatpb.listingguard.trademark.TrademarkCheckInput;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals;
import com.thanhdatpb.listingguard.trademark.catalog.InMemoryTrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import com.thanhdatpb.listingguard.trademark.watchlist.Watchlist;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistEntry;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Offline listing-level characterization using the existing ADR-032 analysis pipeline. */
class TrademarkEvalGateTest {

    private static final Path FIXTURE = ListingEvalGateTest.LISTING_DIR.resolve("trademark/cases.tsv");
    private static final Path REPORT = ListingEvalGateTest.REPORT_DIR.resolve("trademark.md");
    private static final String FIXTURE_SHA = "3cd398b13c886b387d2c1920a52967314f61699004771753b52d232e42db51a9";

    @Test
    void syntheticPhraseGateRespectsCatalogTierAndNeverClearsKnownBrandsOrAliases() throws IOException {
        var ruleset = TrademarkRulesetLoader.loadClasspath("rulesets/trademark-risk-2026-12.yaml");
        var catalog = new InMemoryTrademarkCatalog("phrase-eval", List.of(
                phraseMark("mug", "Mug", DistinctivenessSignals.Tier.WEAK, 12L, 30L, "12345678"),
                phraseMark("blue", "Blue", DistinctivenessSignals.Tier.WEAK, 8L, 19L, "12345679"),
                phraseMark("zorvex", "Zorvex", DistinctivenessSignals.Tier.STRONG, 1L, 1L, "12345680"),
                phraseMark("crimson falcons", "Crimson Falcons", DistinctivenessSignals.Tier.ORDINARY, 2L, 3L, "12345681")
        ));
        var scanner = new TrademarkScanner(ruleset, catalog);
        var oldScanner = new TrademarkScanner(ruleset, new InMemoryTrademarkCatalog("before-tier",
                List.of(new CatalogMark("mug", "Mug"))));
        var watchlist = new Watchlist(WatchlistSettings.loadClasspath(WatchlistSettings.SHIPPED),
                List.of(new WatchlistEntry("1", "Brimwick Otters", List.of("Bkwott"), List.of(), "12345682", null)));
        var sample = Files.readAllLines(FIXTURE.resolveSibling("phrases.tsv"), StandardCharsets.UTF_8);
        assertThat(sample).hasSize(8);
        for (String line : sample.subList(1, sample.size())) {
            String[] row = line.split("\t", -1);
            boolean legacy = row[1].equals("LEGACY");
            var result = PhraseScreening.screen(legacy ? oldScanner : scanner,
                    List.of(new PhraseScreening.Input(row[2], null)), null, watchlist, 200);
            var checked = result.phrases().getFirst();
            assertThat(checked.verdict().name()).as(row[0]).isEqualTo(row[3]);
            assertThat(checked.completeness()).isEqualTo(legacy ? Completeness.INCOMPLETE : Completeness.COMPLETE);
            if (!row[4].equals("-")) {
                assertThat(checked.findings()).extracting(PhraseScreening.PhraseFinding::mark).contains(row[4]);
            }
            if (row[1].equals("BRAND") || row[1].equals("WATCHLIST")) {
                assertThat(checked.verdict()).isNotEqualTo(PhraseScreening.Verdict.CLEAR);
            }
            if (row[0].equals("P03")) {
                assertThat(checked.findings()).anySatisfy(finding -> {
                    assertThat(finding.mark()).isEqualTo("Mug");
                    assertThat(finding.tier()).isEqualTo(DistinctivenessSignals.Tier.WEAK);
                    assertThat(finding.ownerCountSameMark()).isEqualTo(12L);
                    assertThat(finding.sourceLinks().getFirst().url()).contains("tsdr.uspto.gov", "12345678");
                });
            }
            if (legacy) {
                assertThat(checked.skippedRuleIds()).contains("catalog_distinctiveness");
            }
        }
    }

    private static CatalogMark phraseMark(String normalized, String display, DistinctivenessSignals.Tier tier,
                                          long owners, long serials, String serial) {
        return new CatalogMark(normalized, display, List.of(21), MarkStatus.REGISTERED, false, List.of("uspto:" + serial),
                new DistinctivenessSignals(normalized.contains(" ") ? 2 : 1, serials, 1,
                        DistinctivenessSignals.MarkKind.STANDARD_CHARACTER, tier == DistinctivenessSignals.Tier.WEAK,
                        tier, DistinctivenessSignals.Reason.DEFAULT, owners));
    }

    private enum Label { POSITIVE, NEGATIVE, UNLABELLED }

    private enum Group {
        BRAND_USE, DESCRIPTIVE, HARD_NEGATIVE, AMBIGUOUS, MISSING_CATALOG, MISSING_DATA, ALL_HIGH
    }

    private record Sample(String id, Group group, Label label, String title) { }

    private record Observation(Sample sample, PartState state, Completeness completeness, RiskLevel risk) {
        boolean scored() {
            return sample.label() != Label.UNLABELLED && state == PartState.DONE
                    && completeness == Completeness.COMPLETE && risk != null && risk != RiskLevel.UNKNOWN;
        }

        boolean alert() {
            return risk == RiskLevel.HIGH || risk == RiskLevel.MEDIUM;
        }
    }

    private record Matrix(int tp, int fp, int fn, int tn) {
        static Matrix count(List<Observation> observations) {
            int tp = 0;
            int fp = 0;
            int fn = 0;
            int tn = 0;
            for (Observation observation : observations) {
                if (!observation.scored()) {
                    continue;
                }
                if (observation.sample().label() == Label.POSITIVE) {
                    if (observation.alert()) {
                        tp++;
                    } else {
                        fn++;
                    }
                } else if (observation.alert()) {
                    fp++;
                } else {
                    tn++;
                }
            }
            return new Matrix(tp, fp, fn, tn);
        }

        String row(String name) {
            return "| %s | %d | %d | %d | %d | %s | %s | %s |%n".formatted(name, tp, fp, fn, tn,
                    new Rate(tp, tp + fp).text(), new Rate(tp, tp + fn).text(), new Rate(fp, fp + tn).text());
        }
    }

    @Test
    void evaluatesTrademarkWithNoNetworkOrDatabase() throws IOException, NoSuchAlgorithmException {
        Files.deleteIfExists(REPORT);
        byte[] bytes = Files.readAllBytes(FIXTURE);
        String hash = sha256(bytes);
        assertThat(hash).as("frozen trademark fixture SHA-256").isEqualTo(FIXTURE_SHA);
        List<Sample> samples = parse(new String(bytes, StandardCharsets.UTF_8));

        var ruleset = ListingEvalGateTest.trademarkRuleset();
        var demo = DemoCatalogScanner.load(ruleset, ListingEvalGateTest.DEMO_DIR);
        var engine = new RuleEngine(RuleRegistry.withDefaults());
        var normalizers = ListingEvalGateTest.normalizers();
        var rulesets = ListingEvalGateTest.activeRulesets();
        var analyzer = new ListingAnalyzer(normalizers, engine, rulesets, marketplace -> demo);
        var withoutCatalog = new ListingAnalyzer(normalizers, engine, rulesets, marketplace -> {
            throw new TrademarkUnavailableException("No synthetic catalog configured.");
        });

        List<Observation> observations = new ArrayList<>();
        for (Sample sample : samples) {
            var listing = JsonNodeFactory.instance.objectNode();
            if (!sample.title().isEmpty()) {
                listing.put("title", sample.title());
            }
            var analysis = (sample.group() == Group.MISSING_CATALOG ? withoutCatalog : analyzer).analyze("ETSY", listing);
            var result = analysis.trademark();
            observations.add(new Observation(sample, analysis.trademarkState(),
                    result == null ? null : result.completeness(), result == null ? null : result.riskLevel()));
        }

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report(observations, hash, ruleset.version(), demo.catalog().version(),
                demo.fingerprint(Map.of("catalog_version", demo.catalog().version())).value()));
        assertThat(observations).filteredOn(o -> o.sample().group() == Group.MISSING_CATALOG)
                .singleElement().extracting(Observation::state).isEqualTo(PartState.NOT_CONFIGURED);
        assertThat(observations).filteredOn(o -> o.sample().group() != Group.MISSING_CATALOG)
                .allSatisfy(o -> {
                    assertThat(o.state()).as(o.sample().id()).isEqualTo(PartState.DONE);
                    assertThat(o.completeness()).as(o.sample().id()).isEqualTo(Completeness.COMPLETE);
                });
        List<Observation> allHigh = observations.stream().filter(o -> o.sample().group() == Group.ALL_HIGH).toList();
        assertThat(allHigh).hasSize(20).allSatisfy(o -> {
            assertThat(o.sample().label()).isEqualTo(Label.UNLABELLED);
            assertThat(o.risk()).as(o.sample().id()).isEqualTo(RiskLevel.HIGH);
        });
        enforceBaseline(observations);
    }

    @Test
    void calibratesOnlyEligibleSyntheticMatchesPreservingTheEvidenceAndFingerprint() throws IOException {
        Path fixtures = FIXTURE.getParent();
        var oldRules = TrademarkRulesetLoader.loadClasspath("rulesets/trademark-risk-2026-11b.yaml");
        var newRules = TrademarkRulesetLoader.loadClasspath("rulesets/trademark-risk-2026-12.yaml");
        var normalizer = new TextNormalizer(newRules.normalization());
        List<CatalogMark> marks = new ArrayList<>();
        for (String row : Files.readAllLines(fixtures.resolve("calibration-catalog.tsv")).stream().skip(1).toList()) {
            String[] columns = row.split("\t");
            var tier = DistinctivenessSignals.Tier.valueOf(columns[1]);
            boolean weak = tier == DistinctivenessSignals.Tier.WEAK;
            String serial = "uspto:" + (90000001 + marks.size());
            marks.add(new CatalogMark(normalizer.normalizeMark(columns[0]), columns[0], List.of(25),
                    MarkStatus.REGISTERED, false, List.of(serial),
                    new DistinctivenessSignals(columns[0].split(" ").length, weak ? 12L : 1L, 1,
                            DistinctivenessSignals.MarkKind.STANDARD_CHARACTER, weak, tier, null)));
        }
        var catalog = new InMemoryTrademarkCatalog("synthetic-calibration", marks);
        var legacy = new InMemoryTrademarkCatalog("synthetic-legacy", normalizer, List.of("Mug"));
        var before = new TrademarkScanner(oldRules, catalog);
        var after = new TrademarkScanner(newRules, catalog);
        var watchSettings = WatchlistSettings.loadClasspath("watchlist/trademark-watchlist-2026-11.yaml");
        var report = new StringBuilder("""
                # So sánh hiệu chỉnh trademark trên fixture tổng hợp

                | Ca | Nhóm | Trước | Sau | Finding |
                | --- | --- | --- | --- | ---: |
                """);
        var totals = new java.util.TreeMap<String, int[]>();
        var seen = new HashSet<String>();
        boolean ordinaryCovered = false;
        for (String row : Files.readAllLines(fixtures.resolve("calibration.tsv")).stream().skip(1).toList()) {
            String[] c = row.split("\t", -1);
            assertThat(c).hasSize(9);
            assertThat(seen.add(c[0])).isTrue();
            var input = new TrademarkCheckInput(c[2], c[3].equals("-") ? List.of() : List.of(c[3]),
                    present(c[4]), present(c[5]), present(c[6]), null);
            var watch = c[7].equals("-") ? Watchlist.EMPTY : new Watchlist(watchSettings,
                    List.of(new WatchlistEntry(c[0], c[7], List.of(), List.of(25), null, null)));
            var oldResult = (c[1].equals("LEGACY") ? new TrademarkScanner(oldRules, legacy) : before).scan(input, watch);
            var result = (c[1].equals("LEGACY") ? new TrademarkScanner(newRules, legacy) : after).scan(input, watch);
            assertThat(oldResult.findings()).as(c[0]).isNotEmpty();
            assertThat(result.riskLevel().name()).as(c[0]).isEqualTo(c[8]);
            assertThat(result.findings()).as(c[0]).hasSameSizeAs(oldResult.findings());
            for (int j = 0; j < result.findings().size(); j++) {
                var first = oldResult.findings().get(j);
                var next = result.findings().get(j);
                assertThat(next.evidence()).usingRecursiveComparison().ignoringFields("riskAdjustments").isEqualTo(first.evidence());
                assertThat(next.ruleId()).isEqualTo(first.ruleId());
                if (next.risk() != first.risk()) {
                    assertThat(next.evidence().riskAdjustments()).singleElement().satisfies(adjustment -> {
                        assertThat(adjustment.from()).isEqualTo(first.risk());
                        assertThat(adjustment.to()).isEqualTo(next.risk());
                        assertThat(adjustment.distinctiveness()).isNotNull();
                        assertThat(adjustment.note()).contains("chưa xác minh");
                    });
                }
            }
            if (c[1].equals("WATCHLIST")) {
                assertThat(result.findings()).allSatisfy(f -> assertThat(f.source()).isEqualTo(FindingSource.WATCHLIST));
            }
            if (c[1].equals("LEGACY")) {
                assertThat(result.skippedRuleIds()).contains("catalog_distinctiveness");
                assertThat(result.completeness()).isEqualTo(Completeness.INCOMPLETE);
            } else {
                assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
            }
            if (c[1].equals("DESCRIPTIVE") && result.riskLevel() == RiskLevel.MEDIUM) {
                assertThat(LlmJudgement.eligibleIndexes(result)).containsExactly(0);
                var judged = LlmJudgement.apply(result, Map.of(0,
                        new LlmJudgement.Judged(LlmJudgement.Verdict.LIKELY_DESCRIPTIVE, "Đối chứng tổng hợp")), false);
                assertThat(judged.riskLevel()).isEqualTo(RiskLevel.LOW);
                assertThat(judged.findings().getFirst().evidence().riskAdjustments().getFirst().distinctiveness())
                        .isNotNull();
                ordinaryCovered = true;
            } else {
                assertThat(LlmJudgement.eligibleIndexes(result)).isEmpty();
            }

            int[] counts = totals.computeIfAbsent(c[1], key -> new int[3]);
            counts[0]++;
            counts[1] += oldResult.riskLevel() == RiskLevel.HIGH ? 1 : 0;
            counts[2] += result.riskLevel() == RiskLevel.HIGH ? 1 : 0;
            report.append("| %s | %s | %s | %s | %d |%n".formatted(c[0], c[1], oldResult.riskLevel(),
                    result.riskLevel(), result.findings().size()));
        }
        assertThat(ordinaryCovered).isTrue();
        assertThat(totals.get("DESCRIPTIVE")[0]).isGreaterThanOrEqualTo(10);
        assertThat(totals.get("DESCRIPTIVE")[2]).isZero();
        for (String group : List.of("BRAND_USE", "WATCHLIST")) {
            int[] counts = totals.get(group);
            assertThat(counts[0]).isGreaterThanOrEqualTo(group.equals("WATCHLIST") ? 5 : 10);
            assertThat(counts[1]).isEqualTo(counts[2]).isEqualTo(counts[0]);
        }
        assertThat(after.fingerprint(Map.of()).value()).isNotEqualTo(before.fingerprint(Map.of()).value());

        report.append("""

                | Nhóm | Số ca | HIGH trước | HIGH sau |
                | --- | ---: | ---: | ---: |
                """);
        totals.forEach((group, counts) -> report.append("| %s | %d | %d | %d |%n"
                .formatted(group, counts[0], counts[1], counts[2])));
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT.resolveSibling("trademark-calibration.md"), report);
    }

    private static String present(String value) {
        return value.equals("-") ? null : value;
    }

    @Test
    void failsTheGateForAnIncorrectOrUnavailablePrediction() {
        var negative = new Sample("negative", Group.HARD_NEGATIVE, Label.NEGATIVE, "Synthetic control");
        var positive = new Sample("positive", Group.BRAND_USE, Label.POSITIVE, "Synthetic control");
        assertThatThrownBy(() -> enforceBaseline(List.of(
                new Observation(negative, PartState.DONE, Completeness.COMPLETE, RiskLevel.HIGH))))
                .isInstanceOf(AssertionError.class).hasMessageContaining("false positives");
        assertThatThrownBy(() -> enforceBaseline(List.of(
                new Observation(positive, PartState.DONE, Completeness.COMPLETE, RiskLevel.LOW))))
                .isInstanceOf(AssertionError.class).hasMessageContaining("false negatives");
        assertThatThrownBy(() -> enforceBaseline(List.of(
                new Observation(positive, PartState.NOT_CONFIGURED, null, null))))
                .isInstanceOf(AssertionError.class).hasMessageContaining("labelled coverage");
    }

    private static void enforceBaseline(List<Observation> observations) {
        long labelled = observations.stream().filter(o -> o.sample().label() != Label.UNLABELLED).count();
        assertThat(observations.stream().filter(Observation::scored).count())
                .as("labelled coverage").isEqualTo(labelled);
        Matrix result = Matrix.count(observations);
        assertThat(result.fp()).as("false positives on synthetic controls").isZero();
        assertThat(result.fn()).as("false negatives on synthetic controls").isZero();
        assertThat(result.tp()).as("positive controls").isGreaterThanOrEqualTo(3);
        assertThat(result.tn()).as("negative controls").isGreaterThanOrEqualTo(5);
    }

    private static List<Sample> parse(String input) {
        List<String> lines = input.lines().toList();
        assertThat(lines).isNotEmpty();
        assertThat(lines.getFirst()).isEqualTo("id\tgroup\tlabel\ttitle");
        List<Sample> samples = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] columns = line.split("\t", -1);
            assertThat(columns).hasSize(4);
            assertThat(columns[0]).matches("[a-z0-9-]+");
            assertThat(ids.add(columns[0])).as("unique fixture id %s", columns[0]).isTrue();
            Group group = Group.valueOf(columns[1]);
            Label label = Label.valueOf(columns[2]);
            Label expectedLabel = switch (group) {
                case BRAND_USE -> Label.POSITIVE;
                case DESCRIPTIVE, HARD_NEGATIVE -> Label.NEGATIVE;
                default -> Label.UNLABELLED;
            };
            assertThat(label).as(columns[0]).isEqualTo(expectedLabel);
            boolean missingTitle = columns[3].equals("<absent>");
            assertThat(missingTitle).as(columns[0]).isEqualTo(group == Group.MISSING_DATA);
            assertThat(columns[3]).isNotBlank();
            samples.add(new Sample(columns[0], group, label, missingTitle ? "" : columns[3]));
        }
        assertThat(samples.stream().map(Sample::group).toList()).containsAll(EnumSet.allOf(Group.class));
        return samples;
    }

    private static String report(List<Observation> observations, String hash, String ruleset, String catalog,
                                 String fingerprint) {
        var report = new StringBuilder("""
                # Đánh giá trademark trên bộ mẫu tổng hợp

                Chỉ dùng để phát hiện hồi quy trên các mẫu cố định. Kết quả không xác nhận vi phạm hay độ chính xác ngoài thực tế.
                Mẫu dương có tín hiệu sử dụng nhãn hiệu DEMO theo tình huống giả định. Mẫu âm là đối chứng tổng hợp.
                MEDIUM và HIGH được tính là cảnh báo. LOW được tính là không cảnh báo trong ma trận.

                """);
        report.append("- SHA-256 bộ mẫu `").append(hash).append("`\n")
                .append("- Ruleset `").append(ruleset).append("`\n")
                .append("- Catalog `").append(catalog).append("`\n")
                .append("- Fingerprint `").append(fingerprint).append("`\n\n")
                .append("| Nhóm | TP | FP | FN | TN | Precision | Recall | FPR |\n")
                .append("| --- | ---: | ---: | ---: | ---: | --- | --- | --- |\n");
        Matrix total = Matrix.count(observations);
        report.append(total.row("Tổng mẫu có nhãn"));
        for (Group group : List.of(Group.BRAND_USE, Group.DESCRIPTIVE, Group.HARD_NEGATIVE)) {
            report.append(Matrix.count(observations.stream().filter(o -> o.sample().group() == group).toList()).row(group.name()));
        }
        int labelled = (int) observations.stream().filter(o -> o.sample().label() != Label.UNLABELLED).count();
        int scored = (int) observations.stream().filter(Observation::scored).count();
        int high = (int) observations.stream().filter(o -> o.risk() == RiskLevel.HIGH).count();
        report.append("\n- Bao phủ nhãn trên toàn bộ mẫu ").append(new Rate(labelled, observations.size()).text())
                .append("\n- Mẫu có nhãn chấm được ").append(new Rate(scored, labelled).text())
                .append("\n- HIGH trên toàn bộ mẫu ").append(new Rate(high, observations.size()).text())
                .append("\n- Gánh nặng FP trong cảnh báo có nhãn ").append(new Rate(total.fp(), total.tp() + total.fp()).text())
                .append("\n\n| Nhóm | Số mẫu | Số mẫu có nhãn | HIGH trên toàn bộ mẫu của nhóm |\n")
                .append("| --- | ---: | ---: | --- |\n");
        for (Group group : Group.values()) {
            List<Observation> items = observations.stream().filter(o -> o.sample().group() == group).toList();
            report.append("| %s | %d | %d | %s |%n".formatted(group, items.size(),
                    items.stream().filter(o -> o.sample().label() != Label.UNLABELLED).count(),
                    new Rate((int) items.stream().filter(o -> o.risk() == RiskLevel.HIGH).count(), items.size()).text()));
        }
        report.append("\n| Mã mẫu | Nhóm | Nhãn | Trạng thái | Mức trả về |\n| --- | --- | --- | --- | --- |\n");
        for (Observation o : observations) {
            report.append("| %s | %s | %s | %s | %s |%n".formatted(o.sample().id(), o.sample().group(),
                    o.sample().label(), o.state(), o.risk() == null ? "—" : o.risk()));
        }
        return report.toString().replace("\r\n", "\n");
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
