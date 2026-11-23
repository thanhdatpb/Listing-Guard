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
