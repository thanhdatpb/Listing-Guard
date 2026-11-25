package com.thanhdatpb.listingguard.api.evaluation.listing;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.llm.LlmCache;
import com.thanhdatpb.listingguard.api.llm.LlmClient;
import com.thanhdatpb.listingguard.api.llm.LlmProperties;
import com.thanhdatpb.listingguard.api.llm.LlmUsageStore;
import com.thanhdatpb.listingguard.api.trademark.judge.JudgeContext;
import com.thanhdatpb.listingguard.api.trademark.judge.JudgeEvidence;
import com.thanhdatpb.listingguard.api.trademark.judge.JudgeEvidenceGatherer;
import com.thanhdatpb.listingguard.api.trademark.judge.JudgePrompt;
import com.thanhdatpb.listingguard.api.trademark.judge.TrademarkContextJudge;
import com.thanhdatpb.listingguard.rag.llm.ScriptedLlmProvider;
import com.thanhdatpb.listingguard.trademark.JudgeBasis;
import com.thanhdatpb.listingguard.trademark.RiskAdjustment;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.TrademarkCheckInput;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import com.thanhdatpb.listingguard.trademark.TrademarkScanner;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals;
import com.thanhdatpb.listingguard.trademark.catalog.InMemoryTrademarkCatalog;
import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
import com.thanhdatpb.listingguard.trademark.watchlist.Watchlist;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistEntry;
import com.thanhdatpb.listingguard.trademark.watchlist.WatchlistSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The gate of the trademark judge with an evidence pack (ADR-051), offline: synthetic cases, a scripted model that answers what the
 * fixture says whatever the prompt is, no network and no database. The same cases run once with the prompt of ADR-036 and once with
 * the evidence prompt. What the gate proves is about the program around the model, not about the model: no level ever goes up, a
 * watchlist, brand-field, STRONG-tier or multi-word finding is never sent and never lowered, a missing fact is shown as
 * {@code unknown}, and with the facts missing the evidence prompt ends where the old one does. How often a real model agrees with
 * labelled data is not measured here (docs/llm.md says how to measure it).
 */
class TrademarkJudgeEvalGateTest {

    private static final Path FIXTURE = ListingEvalGateTest.LISTING_DIR.resolve("trademark/judge-cases.tsv");
    private static final Path REPORT = ListingEvalGateTest.REPORT_DIR.resolve("trademark-judge.md");
    private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-00000000e0a1");
    private static final Set<String> NEVER_JUDGED = Set.of("WATCHLIST", "BRAND_FIELD", "STRONG_TIER", "MULTI_WORD");

    private record Case(String id, String group, String ruleset, String catalog, String mark, String title, String brand,
                        String watchlist, String productType, String tsdr, String verified, String verdict, RiskLevel before,
                        RiskLevel after, String sourceId) {
    }

    private record Run(Case row, TrademarkResult scanned, TrademarkResult oldPrompt, TrademarkResult evidencePrompt, int oldRequests,
                       int evidenceRequests, String evidenceUser) {
    }

    @Test
    void theEvidencePromptNeverRaisesALevelNeverReachesAnIneligibleFindingAndEndsWhereTheOldPromptEndsWithoutFacts()
            throws IOException {
        List<Case> cases = parse(Files.readAllLines(FIXTURE));
        TrademarkRuleset r11 = TrademarkRulesetLoader.loadClasspath("rulesets/trademark-risk-2026-11b.yaml");
        TrademarkRuleset r12 = TrademarkRulesetLoader.loadClasspath("rulesets/trademark-risk-2026-12.yaml");
        TextNormalizer normalizer = new TextNormalizer(r12.normalization());
        InMemoryTrademarkCatalog catalog = new InMemoryTrademarkCatalog("synthetic-judge", marks(cases, normalizer));
        WatchlistSettings watchSettings = WatchlistSettings.loadClasspath("watchlist/trademark-watchlist-2026-11.yaml");

        ScriptedLlmProvider oldModel = scripted(cases);
        ScriptedLlmProvider evidenceModel = scripted(cases);
        TrademarkContextJudge oldJudge = new TrademarkContextJudge(client(oldModel), properties(JudgePrompt.DEFAULT_VERSION),
                JudgeEvidenceGatherer.none());
        TrademarkContextJudge evidenceJudge = new TrademarkContextJudge(client(evidenceModel),
                properties(JudgePrompt.EVIDENCE_VERSION), gatherer(cases));

        List<Run> runs = new ArrayList<>();
        for (Case row : cases) {
            TrademarkRuleset ruleset = row.ruleset().equals("R12") ? r12 : r11;
            TrademarkCheckInput input = new TrademarkCheckInput(row.title(), List.of(), List.of(), null, present(row.brand()), null,
                    null, null, present(row.productType()));
            Watchlist watchlist = row.watchlist().equals("-") ? Watchlist.EMPTY : new Watchlist(watchSettings,
                    List.of(new WatchlistEntry(row.id(), row.watchlist(), List.of(), List.of(25), null, null)));
            TrademarkResult scanned = new TrademarkScanner(ruleset, catalog).scan(input, watchlist);
            Set<Integer> classes = row.productType().equals("-") ? null : ruleset.productClasses().classesOf(row.productType());

            int oldBefore = oldModel.requests().size();
            TrademarkResult oldRun = oldJudge.judge(input, scanned, catalog, JudgeContext.NONE);
            int oldRequests = oldModel.requests().size() - oldBefore;
            int evidenceBefore = evidenceModel.requests().size();
            TrademarkResult evidenceRun = evidenceJudge.judge(input, scanned, catalog, new JudgeContext(CLIENT, "ETSY", classes));
            int evidenceRequests = evidenceModel.requests().size() - evidenceBefore;
            String user = evidenceRequests == 0 ? "" : evidenceModel.requests().getLast().user();
            runs.add(new Run(row, scanned, oldRun, evidenceRun, oldRequests, evidenceRequests, user));
        }

        for (Run run : runs) {
            String id = run.row().id();
            assertThat(run.scanned().riskLevel()).as("%s: the level the rules give", id).isEqualTo(run.row().before());
            for (TrademarkResult judged : List.of(run.oldPrompt(), run.evidencePrompt())) {
                assertThat(judged.findings()).as(id).hasSameSizeAs(run.scanned().findings());
                for (int i = 0; i < judged.findings().size(); i++) {
                    assertThat(judged.findings().get(i).risk().ordinal()).as("%s: finding %d is never raised", id, i)
                            .isLessThanOrEqualTo(run.scanned().findings().get(i).risk().ordinal());
                }
                assertThat(judged.riskLevel().ordinal()).as("%s: the overall level is never raised", id)
                        .isLessThanOrEqualTo(run.scanned().riskLevel().ordinal());
            }
            assertThat(run.evidencePrompt().riskLevel()).as("%s: level with the evidence prompt", id).isEqualTo(run.row().after());
            assertThat(run.oldPrompt().riskLevel()).as("%s: level with the old prompt", id).isEqualTo(run.row().after());
            assertThat(run.evidenceRequests()).as("%s: the same findings are sent", id).isEqualTo(run.oldRequests());
            assertThat(judgeBases(run.oldPrompt())).as("%s: the old prompt records no evidence", id).isEmpty();
        }

        List<Run> never = runs.stream().filter(run -> NEVER_JUDGED.contains(run.row().group())).toList();
        assertThat(never).hasSizeGreaterThanOrEqualTo(6);
        for (Run run : never) {
            assertThat(run.oldRequests() + run.evidenceRequests()).as("%s: never sent to the model", run.row().id()).isZero();
            assertThat(run.evidencePrompt()).as(run.row().id()).isSameAs(run.scanned());
            assertThat(run.evidencePrompt().riskLevel()).isEqualTo(RiskLevel.HIGH);
        }

        List<Run> eligible = runs.stream().filter(run -> !NEVER_JUDGED.contains(run.row().group())).toList();
        assertThat(eligible).hasSizeGreaterThanOrEqualTo(10)
                .allSatisfy(run -> assertThat(run.evidenceRequests()).as(run.row().id()).isEqualTo(1));
        assertThat(eligible.stream().filter(run -> run.evidencePrompt().riskLevel() != run.scanned().riskLevel()).count())
                .as("lowered exactly where the scripted model said likely_descriptive")
                .isEqualTo(eligible.stream().filter(run -> run.row().verdict().equals("likely_descriptive")).count());

        for (Run run : eligible) {
            String id = run.row().id();
            String user = run.evidenceUser();
            switch (run.row().group()) {
                case "FULL_EVIDENCE" -> {
                    assertThat(user).as(id).contains("- distinctiveness_tier: ORDINARY\n").contains("- same_mark_count: 12\n")
                            .contains("- same_mark_class_count: 1\n").contains("- mark_kind: STANDARD_CHARACTER\n")
                            .contains("- dictionary_word: true\n").contains("- word_count: 1\n");
                    assertThat(user).contains("- tsdr_status: " + (run.row().tsdr().equals("-") ? "unknown"
                            : run.row().tsdr() + ", checked 7 days ago") + "\n");
                    assertThat(user).contains("- verified_case: " + (run.row().verified().equals("none") ? "none"
                            : run.row().verified() + ", 20 days ago") + "\n");
                    assertThat(user).contains("- product_class_overlap: " + (run.row().productType().equals("-") ? "unknown" : "no")
                            + "\n");
                }
                case "PARTIAL_EVIDENCE" -> assertThat(user).as(id).contains("- distinctiveness_tier: ORDINARY\n")
                        .contains("- same_mark_count: unknown\n").contains("- same_mark_class_count: unknown\n")
                        .contains("- mark_kind: unknown\n").contains("- dictionary_word: unknown\n")
                        .contains("- verified_case: unknown\n");
                case "VERIFIED_CONFLICT" -> {
                    assertThat(user).as(id).contains("- distinctiveness_tier: ORDINARY\n");
                    assertThat(user).contains("- verified_case: " + run.row().verified() + ", 20 days ago\n");
                }
                case "NO_TIER" -> assertThat(user).as(id).contains("- distinctiveness_tier: unknown\n")
                        .contains("- same_mark_count: unknown\n").contains("- word_count: 1\n")
                        .contains("- product_class_overlap: no\n").contains("- tsdr_status: unknown\n");
                default -> throw new AssertionError("unexpected group " + run.row().group());
            }
            if (run.evidencePrompt().riskLevel() != run.scanned().riskLevel()) {
                assertThat(judgeBases(run.evidencePrompt())).as("%s: the demotion keeps what the model saw", id).singleElement()
                        .satisfies(basis -> {
                            assertThat(basis.promptVersion()).isEqualTo(JudgePrompt.EVIDENCE_VERSION);
                            assertThat(basis.reason()).isNotBlank();
                            assertThat(basis.evidence()).hasSize(9);
                        });
            } else {
                assertThat(judgeBases(run.evidencePrompt())).as("%s: nothing is recorded when nothing changes", id).isEmpty();
            }
        }

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report(runs, evidenceModel));
    }

    private static String present(String value) {
        return value.equals("-") ? null : value;
    }

    private static List<JudgeBasis> judgeBases(TrademarkResult result) {
        List<JudgeBasis> bases = new ArrayList<>();
        for (TrademarkFinding finding : result.findings()) {
            for (RiskAdjustment adjustment : finding.evidence().riskAdjustments()) {
                if (adjustment.judge() != null) {
                    bases.add(adjustment.judge());
                }
            }
        }
        return bases;
    }

    private static List<Case> parse(List<String> lines) {
        assertThat(lines.getFirst()).isEqualTo(
                "id\tgroup\truleset\tcatalog\tmark\ttitle\tbrand\twatchlist\tproduct_type\ttsdr\tverified\tverdict\tbefore\tafter");
        List<Case> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split("\t", -1);
            assertThat(c).as(line).hasSize(14);
            assertThat(ids.add(c[0])).as("unique id %s", c[0]).isTrue();
            assertThat(c[5] + " " + c[6]).as("the mark is in the title or the brand of %s", c[0]).contains(c[4]);
            cases.add(new Case(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], RiskLevel.valueOf(c[12]),
                    RiskLevel.valueOf(c[13]), "uspto:" + (91000001 + cases.size())));
        }
        assertThat(cases.stream().map(Case::group).collect(Collectors.toSet())).containsExactlyInAnyOrder("FULL_EVIDENCE",
                "PARTIAL_EVIDENCE", "VERIFIED_CONFLICT", "NO_TIER", "WATCHLIST", "BRAND_FIELD", "STRONG_TIER", "MULTI_WORD");
        return cases;
    }

    private static List<CatalogMark> marks(List<Case> cases, TextNormalizer normalizer) {
        List<CatalogMark> marks = new ArrayList<>();
        for (Case row : cases) {
            int words = row.mark().split(" ").length;
            DistinctivenessSignals signals = switch (row.catalog()) {
                case "ORDINARY" -> new DistinctivenessSignals(words, 12L, 1, DistinctivenessSignals.MarkKind.STANDARD_CHARACTER, true,
                        DistinctivenessSignals.Tier.ORDINARY, null);
                case "STRONG" -> new DistinctivenessSignals(words, 1L, 1, DistinctivenessSignals.MarkKind.STANDARD_CHARACTER, false,
                        DistinctivenessSignals.Tier.STRONG, null);
                case "PARTIAL" -> new DistinctivenessSignals(words, null, null, null, null, DistinctivenessSignals.Tier.ORDINARY, null);
                case "NOTIER" -> null;
                default -> throw new AssertionError("unknown catalog kind " + row.catalog());
            };
            marks.add(new CatalogMark(normalizer.normalizeMark(row.mark()), row.mark(), List.of(25), MarkStatus.REGISTERED, false,
                    List.of(row.sourceId()), signals));
        }
        return marks;
    }

    /** A model that answers what the fixture says for the mark in the sentence, whatever else the prompt holds. */
    private static ScriptedLlmProvider scripted(List<Case> cases) {
        ScriptedLlmProvider model = new ScriptedLlmProvider("scripted:eval", "not json");
        for (Case row : cases) {
            model.whenUserContains("[[" + row.mark() + "]]", "{\"verdict\":\"" + row.verdict() + "\",\"reason\":\"Ca tổng hợp "
                    + row.id() + ".\"}");
        }
        return model;
    }

    private static JudgeEvidenceGatherer gatherer(List<Case> cases) {
        Map<String, JudgeEvidence.TsdrState> tsdr = new HashMap<>();
        Map<String, JudgeEvidence.VerifiedState> verified = new HashMap<>();
        for (Case row : cases) {
            if (!row.tsdr().equals("-")) {
                tsdr.put(row.sourceId(), new JudgeEvidence.TsdrState(row.tsdr(), 7));
            }
            verified.put(row.mark().toLowerCase(Locale.ROOT), switch (row.verified()) {
                case "none" -> JudgeEvidence.VerifiedState.NO_CASE;
                case "confirmed" -> new JudgeEvidence.VerifiedState(JudgeEvidence.VerifiedState.Kind.CONFIRMED, 20);
                case "false_alarm" -> new JudgeEvidence.VerifiedState(JudgeEvidence.VerifiedState.Kind.FALSE_ALARM, 20);
                default -> JudgeEvidence.VerifiedState.NOT_KNOWN;
            });
        }
        return new JudgeEvidenceGatherer(sourceIds -> sourceIds.stream().map(tsdr::get).filter(Objects::nonNull).findFirst(),
                new JudgeEvidenceGatherer.ReviewSource() {
                    @Override
                    public JudgeEvidence.VerifiedState nearest(UUID clientId, String marketplace, String ruleId, String fieldKind,
                                                               String mark) {
                        return verified.getOrDefault(mark, JudgeEvidence.VerifiedState.NOT_KNOWN);
                    }

                    @Override
                    public String digest(UUID clientId) {
                        return "";
                    }
                });
    }

    private static LlmProperties properties(String version) {
        return new LlmProperties(true, List.of(), new LlmProperties.Cache(100, Duration.ofHours(1)),
                new LlmProperties.Judge(true, 8, 160, 600, Duration.ofSeconds(5), version));
    }

    private static LlmClient client(ScriptedLlmProvider model) {
        Clock clock = Clock.systemUTC();
        LlmUsageStore store = new LlmUsageStore() {
            @Override
            public boolean reserve(String providerId, LocalDate day, int limit) {
                return true;
            }

            @Override
            public void addTokens(String providerId, LocalDate day, long inputTokens, long outputTokens) {
            }

            @Override
            public Map<String, Long> requestsOn(LocalDate day) {
                return Map.of();
            }
        };
        return new LlmClient(List.of(new LlmClient.Endpoint("scripted", model, 1000, 1000, true, false)), store,
                new LlmCache(100, Duration.ofHours(1), clock), clock);
    }

    private static String report(List<Run> runs, ScriptedLlmProvider evidenceModel) {
        StringBuilder out = new StringBuilder("""
                # Judge với gói bằng chứng trên ca tổng hợp (ADR-051)

                Mô hình là kịch bản cố định, không phải mô hình thật: bảng chỉ chứng minh phần chương trình bao quanh mô hình
                (không mức nào tăng, finding không đủ điều kiện không bao giờ được gửi, thiếu bằng chứng thì ghi `unknown`).
                Nó không đo mức đồng ý của mô hình thật với nhãn đã gán.

                | Ca | Nhóm | Ruleset | Mức của rule | Prompt cũ | Prompt bằng chứng | Request cũ | Request mới |
                | --- | --- | --- | --- | --- | --- | ---: | ---: |
                """);
        for (Run run : runs) {
            out.append("| %s | %s | %s | %s | %s | %s | %d | %d |%n".formatted(run.row().id(), run.row().group(), run.row().ruleset(),
                    run.scanned().riskLevel(), run.oldPrompt().riskLevel(), run.evidencePrompt().riskLevel(), run.oldRequests(),
                    run.evidenceRequests()));
        }
        long sent = runs.stream().mapToLong(Run::evidenceRequests).sum();
        out.append("\n- Request gửi tới mô hình với prompt bằng chứng: ").append(sent).append(" trên ").append(runs.size())
                .append(" ca\n- Mô hình đã nhận ").append(evidenceModel.requests().size()).append(" request\n");
        return out.toString().replace("\r\n", "\n");
    }
}
