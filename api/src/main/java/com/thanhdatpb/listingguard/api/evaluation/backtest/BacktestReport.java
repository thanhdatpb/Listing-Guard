package com.thanhdatpb.listingguard.api.evaluation.backtest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thanhdatpb.listingguard.api.evaluation.Rate;
import com.thanhdatpb.listingguard.api.evaluation.backtest.BacktestRow.Caught;
import com.thanhdatpb.listingguard.api.evaluation.backtest.BacktestRow.Status;
import com.thanhdatpb.listingguard.api.evaluation.backtest.BacktestRow.Verdict;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Collects the rows of a backtest and writes {@code report.md} (Vietnamese, for a person) and {@code report.json} (the same
 * numbers, for a program) (ADR-032).
 *
 * <p>Neither file holds a listing's text: a row is named by its line and the value of its id column, a finding by its rule
 * id, its field and its severity, a trademark finding by the catalog mark. The one piece of listing text is the near word
 * of a {@link MissReason#SPELLING_VARIANT}, cut short, because it is the evidence of that reason.
 *
 * <p>Every rate comes with its counts ({@link Rate}). The share of flagged unclaimed listings is an <em>estimate</em> of false
 * alarms, and an upper bound: a listing nobody claimed may still break a rule.
 */
final class BacktestReport {

    static final String FORMAT = "listing-guard/backtest/v1";
    static final String MARKDOWN_FILE = "report.md";
    static final String JSON_FILE = "report.json";
    /** The most rows of one list written to the report; the counts always cover all of them. */
    static final int LIST_MAX = 500;

    /** What the run was, for the head of the report. */
    record RunInfo(String generatedAt, String marketplace, String inputFile, String inputSha256, String mappingFile,
                   List<String> labelColumns, String rulesetVersion, String trademarkState, String trademarkCatalogVersion,
                   String trademarkRulesetVersion, String trademarkNote, String catchSeverity, String catchTrademarkRisk,
                   double variantThreshold, Integer maxRows, boolean stoppedAtMaxRows, String abortReason) {
    }

    record RowCounts(int total, int checked, int unreadable, int labelInvalid, int unlabeled) {
    }

    record IssueSummary(String issue, int total, int caught, int missed, int undetermined, Rate recall) {
    }

    record ClaimedSummary(int total, int caught, int missed, int undetermined, Rate recall, int caughtByRules,
                          int caughtByTrademark, Rate trademarkClaimsCaughtOnTheirMark, List<IssueSummary> byIssue) {
    }

    record UnclaimedSummary(int total, int flagged, int clear, int undetermined, Rate flaggedShare) {
    }

    record ReasonCount(String reason, int count, String text) {
    }

    record Missed(int line, String id, String issue, String mark, String reason, String detail, List<String> foundRules) {
    }

    record RuleStats(String rule, String sourceStatus, int claimedListings, int unclaimedListings, Rate shareClaimed) {
    }

    record Problem(int line, String id, String status, String problem) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Report(String format, RunInfo run, RowCounts rows, ClaimedSummary claimed, List<ReasonCount> missReasons,
                  List<Missed> missed, int missedNotListed, UnclaimedSummary unclaimed, List<RuleStats> rules,
                  List<Problem> problems, int problemsNotListed, String note,
                  @JsonInclude(JsonInclude.Include.NON_EMPTY) List<BacktestProbe> probes) {
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String NOTE = "Backtest chỉ cho biết Listing Guard có cảnh báo trước các listing đã từng bị claim hay "
            + "không, trên file này. Đây không phải kết luận pháp lý. Tỉ lệ listing không bị claim nhưng bị cảnh báo là ước lượng "
            + "báo nhầm, và là cận trên: một listing chưa ai claim vẫn có thể vi phạm.";

    private final Map<String, String> sourceStatuses;
    private final List<BacktestRow> rows = new ArrayList<>();

    /** @param sourceStatuses the source status of each listing rule of the active ruleset, by rule id */
    BacktestReport(Map<String, String> sourceStatuses) {
        this.sourceStatuses = sourceStatuses;
    }

    void add(BacktestRow row) {
        rows.add(row);
    }

    Report build(RunInfo info) {
        int checked = 0;
        int unreadable = 0;
        int labelInvalid = 0;
        int unlabeled = 0;
        int claimedTotal = 0;
        int caught = 0;
        int missed = 0;
        int undetermined = 0;
        int byRules = 0;
        int byTrademark = 0;
        int trademarkWithMark = 0;
        int trademarkOnMark = 0;
        Map<IssueType, int[]> issues = new EnumMap<>(IssueType.class);
        Map<MissReason, Integer> reasons = new EnumMap<>(MissReason.class);
        List<Missed> missedRows = new ArrayList<>();
        int unclaimedTotal = 0;
        int flagged = 0;
        int clear = 0;
        int unclaimedUndetermined = 0;
        Map<String, int[]> rules = new TreeMap<>();
        List<Problem> problems = new ArrayList<>();

        for (BacktestRow row : rows) {
            switch (row.status()) {
                case UNREADABLE -> {
                    unreadable++;
                    problems.add(new Problem(row.line(), row.id(), row.status().name(), row.problem()));
                    continue;
                }
                case LABEL_INVALID -> {
                    labelInvalid++;
                    problems.add(new Problem(row.line(), row.id(), row.status().name(), row.problem()));
                    continue;
                }
                case CHECKED -> checked++;
            }
            if (!row.measured()) {
                unlabeled++;
                continue;
            }
            for (String rule : row.flaggedRules()) {
                rules.computeIfAbsent(rule, key -> new int[2])[row.claimed() ? 0 : 1]++;
            }
            if (row.claimed()) {
                claimedTotal++;
                int[] issue = issues.computeIfAbsent(row.issue(), key -> new int[4]);
                issue[0]++;
                switch (row.verdict()) {
                    case CAUGHT -> {
                        caught++;
                        issue[1]++;
                    }
                    case MISSED -> {
                        missed++;
                        issue[2]++;
                        reasons.merge(row.missReason(), 1, Integer::sum);
                        missedRows.add(new Missed(row.line(), row.id(), row.issue().code(), row.mark(),
                                row.missReason().name(), row.missDetail(), List.copyOf(row.foundRules())));
                    }
                    case UNDETERMINED -> {
                        undetermined++;
                        issue[3]++;
                    }
                }
                if (row.caughtBy().contains(Caught.RULES)) {
                    byRules++;
                }
                if (row.caughtBy().contains(Caught.TRADEMARK)) {
                    byTrademark++;
                }
                if (row.issue() == IssueType.TRADEMARK && row.mark() != null && row.verdict() != Verdict.UNDETERMINED) {
                    trademarkWithMark++;
                    if (row.markMatched()) {
                        trademarkOnMark++;
                    }
                }
            } else {
                unclaimedTotal++;
                switch (row.verdict()) {
                    case CAUGHT -> flagged++;
                    case MISSED -> clear++;
                    case UNDETERMINED -> unclaimedUndetermined++;
                }
            }
        }

        List<IssueSummary> byIssue = new ArrayList<>();
        issues.forEach((type, counts) -> byIssue.add(new IssueSummary(type.code(), counts[0], counts[1], counts[2], counts[3],
                new Rate(counts[1], counts[1] + counts[2]))));
        List<ReasonCount> reasonCounts = new ArrayList<>();
        reasons.entrySet().stream().sorted(Map.Entry.<MissReason, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> reasonCounts.add(new ReasonCount(entry.getKey().name(), entry.getValue(),
                        entry.getKey().text())));
        List<RuleStats> ruleStats = new ArrayList<>();
        rules.forEach((rule, counts) -> ruleStats.add(new RuleStats(rule,
                rule.startsWith(BacktestRow.TRADEMARK_PREFIX) ? "unverified" : sourceStatuses.get(rule), counts[0],
                counts[1], new Rate(counts[0], counts[0] + counts[1]))));
        ruleStats.sort(Comparator.comparingInt((RuleStats stats) -> -(stats.claimedListings() + stats.unclaimedListings()))
                .thenComparing(RuleStats::rule));

        ClaimedSummary claimed = new ClaimedSummary(claimedTotal, caught, missed, undetermined, new Rate(caught, caught + missed),
                byRules, byTrademark, new Rate(trademarkOnMark, trademarkWithMark), List.copyOf(byIssue));
        UnclaimedSummary unclaimed = new UnclaimedSummary(unclaimedTotal, flagged, clear, unclaimedUndetermined,
                new Rate(flagged, flagged + clear));
        return new Report(FORMAT, info, new RowCounts(rows.size(), checked, unreadable, labelInvalid, unlabeled), claimed,
                List.copyOf(reasonCounts), List.copyOf(missedRows.subList(0, Math.min(LIST_MAX, missedRows.size()))),
                Math.max(0, missedRows.size() - LIST_MAX), unclaimed, List.copyOf(ruleStats),
                List.copyOf(problems.subList(0, Math.min(LIST_MAX, problems.size()))),
                Math.max(0, problems.size() - LIST_MAX), NOTE,
                rows.stream().map(BacktestRow::probe).filter(java.util.Objects::nonNull).toList());
    }

    /** Writes both files into {@code dir} (created when missing) and returns the report. */
    Report write(Path dir, RunInfo info) {
        Report report = build(info);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(JSON_FILE), JSON.writeValueAsString(report) + "\n", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(MARKDOWN_FILE), markdown(report), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the backtest report to " + dir + ": " + e.getMessage(), e);
        }
        return report;
    }

    // ---- markdown

    static String markdown(Report report) {
        RunInfo run = report.run();
        StringBuilder md = new StringBuilder();
        md.append("# Backtest Listing Guard\n\n");
        if (run.abortReason() != null) {
            md.append("> **CHƯA XONG**: dừng giữa chừng vì ").append(cell(run.abortReason()))
                    .append(". Số liệu dưới đây chỉ gồm các dòng trước chỗ hỏng.\n\n");
        }
        if (run.stoppedAtMaxRows()) {
            md.append("> Chỉ chạy ").append(run.maxRows()).append(" dòng đầu (--max-rows); file còn dòng chưa xét.\n\n");
        }
        md.append("- Thời điểm: ").append(run.generatedAt()).append('\n');
        md.append("- Marketplace: ").append(run.marketplace()).append(", ruleset ").append(run.rulesetVersion()).append('\n');
        md.append("- File: `").append(cell(run.inputFile())).append("` (SHA-256 `").append(run.inputSha256())
                .append("`), mapping `").append(cell(run.mappingFile())).append("`, cột nhãn ")
                .append(cell(String.join(", ", run.labelColumns()))).append('\n');
        md.append("- Nhãn hiệu: ").append(run.trademarkState());
        if (run.trademarkCatalogVersion() != null) {
            md.append(", catalog `").append(cell(run.trademarkCatalogVersion())).append("`, ruleset ")
                    .append(run.trademarkRulesetVersion());
        }
        if (run.trademarkNote() != null) {
            md.append(" (").append(cell(run.trademarkNote())).append(')');
        }
        md.append('\n');
        md.append("- Ngưỡng \"bắt được\": finding của rule từ mức ").append(run.catchSeverity())
                .append(" trở lên, hoặc rủi ro nhãn hiệu từ ").append(run.catchTrademarkRisk()).append(" trở lên\n\n");

        RowCounts rows = report.rows();
        md.append("Dòng: ").append(rows.total()).append(" (đã kiểm tra ").append(rows.checked()).append(", không đọc được ")
                .append(rows.unreadable()).append(", nhãn sai ").append(rows.labelInvalid()).append(", chưa có nhãn ")
                .append(rows.unlabeled()).append(").\n\n");

        ClaimedSummary claimed = report.claimed();
        md.append("## 1. Listing từng bị claim/takedown: có bị bắt trước không\n\n");
        md.append("| | Số listing |\n|---|---:|\n");
        md.append("| Bị claim | ").append(claimed.total()).append(" |\n");
        md.append("| Bắt được | ").append(claimed.caught()).append(" |\n");
        md.append("| Bị sót | ").append(claimed.missed()).append(" |\n");
        md.append("| Không xác định (một phần kiểm tra lỗi hoặc rủi ro UNKNOWN) | ").append(claimed.undetermined()).append(" |\n\n");
        md.append("**Recall: ").append(claimed.recall().text()).append("** (bắt được / (bắt được + bị sót); dòng không xác "
                + "định không tính).\n\n");
        md.append("Bắt bởi rule listing: ").append(claimed.caughtByRules()).append("; bởi phần nhãn hiệu: ")
                .append(claimed.caughtByTrademark()).append(". Vụ claim nhãn hiệu có ghi mark được cảnh báo đúng mark đó: ")
                .append(claimed.trademarkClaimsCaughtOnTheirMark().text()).append(".\n\n");
        if (!claimed.byIssue().isEmpty()) {
            md.append("| Loại vấn đề | Claim | Bắt được | Bị sót | Không xác định | Recall |\n|---|---:|---:|---:|---:|---|\n");
            for (IssueSummary issue : claimed.byIssue()) {
                md.append("| ").append(issue.issue()).append(" | ").append(issue.total()).append(" | ").append(issue.caught())
                        .append(" | ").append(issue.missed()).append(" | ").append(issue.undetermined()).append(" | ")
                        .append(issue.recall().text()).append(" |\n");
            }
            md.append('\n');
        }

        md.append("## 2. Vì sao bị sót\n\n");
        if (report.missReasons().isEmpty()) {
            md.append("Không có vụ nào bị sót.\n\n");
        } else {
            md.append("| Lý do | Số vụ | Nghĩa là |\n|---|---:|---|\n");
            for (ReasonCount reason : report.missReasons()) {
                md.append("| ").append(reason.reason()).append(" | ").append(reason.count()).append(" | ")
                        .append(cell(reason.text())).append(" |\n");
            }
            md.append("\n| Dòng | Id | Loại | Mark | Lý do | Chi tiết | Rule đã báo (dưới ngưỡng) |\n|---:|---|---|---|---|---|---|\n");
            for (Missed missed : report.missed()) {
                md.append("| ").append(missed.line()).append(" | ").append(cell(missed.id())).append(" | ")
                        .append(missed.issue()).append(" | ").append(cell(missed.mark())).append(" | ").append(missed.reason())
                        .append(" | ").append(cell(missed.detail())).append(" | ")
                        .append(cell(String.join(", ", missed.foundRules()))).append(" |\n");
            }
            if (report.missedNotListed() > 0) {
                md.append("\n... và ").append(report.missedNotListed()).append(" vụ nữa không được liệt kê (tối đa ").append(LIST_MAX)
                        .append(" dòng); chia file để xem hết.\n");
            }
            md.append('\n');
        }

        UnclaimedSummary unclaimed = report.unclaimed();
        md.append("## 3. Listing không bị claim: ước lượng báo nhầm\n\n");
        md.append("Không bị claim: ").append(unclaimed.total()).append("; bị cảnh báo: ").append(unclaimed.flagged())
                .append("; sạch: ").append(unclaimed.clear()).append("; không xác định: ").append(unclaimed.undetermined())
                .append(".\n\n**Tỉ lệ bị cảnh báo: ").append(unclaimed.flaggedShare().text())
                .append("** (ước lượng báo nhầm, cận trên: listing chưa ai claim vẫn có thể vi phạm).\n\n");

        md.append("## 4. Theo rule\n\n");
        if (report.rules().isEmpty()) {
            md.append("Không rule nào báo ở mức từ ngưỡng trở lên.\n\n");
        } else {
            md.append("Số listing có finding của rule (từ ngưỡng trở lên). \"Tỉ lệ đúng ước lượng\" = trên claim / tổng: rule báo "
                    + "nhiều trên listing không bị claim thì tỉ lệ này thấp.\n\n");
            md.append("| Rule | Nguồn | Trên listing bị claim | Trên listing không bị claim | Tỉ lệ đúng ước lượng |\n"
                    + "|---|---|---:|---:|---|\n");
            for (RuleStats stats : report.rules()) {
                md.append("| ").append(stats.rule()).append(" | ")
                        .append(stats.sourceStatus() == null ? "?" : stats.sourceStatus().toLowerCase(Locale.ROOT)).append(" | ")
                        .append(stats.claimedListings()).append(" | ").append(stats.unclaimedListings()).append(" | ")
                        .append(stats.shareClaimed().text()).append(" |\n");
            }
            md.append('\n');
        }

        if (!report.problems().isEmpty()) {
            md.append("## 5. Dòng không xét được\n\n| Dòng | Id | Vấn đề | Chi tiết |\n|---:|---|---|---|\n");
            for (Problem problem : report.problems()) {
                md.append("| ").append(problem.line()).append(" | ").append(cell(problem.id())).append(" | ")
                        .append(problem.status()).append(" | ").append(cell(problem.problem())).append(" |\n");
            }
            if (report.problemsNotListed() > 0) {
                md.append("\n... và ").append(report.problemsNotListed()).append(" dòng nữa.\n");
            }
            md.append('\n');
        }
        if (!report.probes().isEmpty()) {
            md.append("## Kỳ vọng mức trademark theo dòng\n\n");
            md.append("| Dòng | Id | Min | Max | Thực tế | Kết quả | Rule nặng nhất | Tier | Số chủ | Số serial | Mức mark đích | Tier mark đích | Số finding |\n");
            md.append("|---:|---|---|---|---|---|---|---|---:|---:|---|---|---:|\n");
            for (BacktestProbe probe : report.probes()) {
                md.append("| ").append(probe.line()).append(" | ").append(cell(probe.id())).append(" | ")
                        .append(display(probe.min())).append(" | ").append(display(probe.max())).append(" | ")
                        .append(display(probe.actual())).append(" | ").append(probe.passed() ? "ĐẠT" : "KHÔNG ĐẠT").append(" | ")
                        .append(cell(probe.finding())).append(" | ").append(display(probe.tier())).append(" | ")
                        .append(display(probe.owners())).append(" | ").append(display(probe.serials())).append(" | ")
                        .append(display(probe.namedMarkRisk())).append(" | ").append(display(probe.namedMarkTier())).append(" | ")
                        .append(probe.findingCount()).append(" |\n");
            }
            md.append('\n');
        }
        md.append("---\n\n").append(report.note()).append('\n');
        return md.toString();
    }

    private static String display(Object value) {
        return value == null ? "—" : value.toString();
    }

    /** A value in a table cell: no line break, no column separator. */
    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ").replace("\n", " ").replace("|", "\\|");
    }
}
