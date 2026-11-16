package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.feedback.FeedbackStats;
import com.thanhdatpb.listingguard.api.report.HealthReport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The pure parts of the home page and the statistics page (ADR-034): the chart's geometry and the CSV's cells. */
class ConsoleHomeAndStatsTest {

    @Test
    void theChartStartsAtZeroAndTheTallestBarFillsTheScale() {
        WeeklyChart chart = WeeklyChart.of(List.of(
                new WeeklyChart.Week("2026-W45", "02/11", false, 12, 10),
                new WeeklyChart.Week("2026-W46", "09/11", false, 0, 0),
                new WeeklyChart.Week("2026-W47", "16/11", true, 5, 1)));

        assertThat(chart.max()).as("the scale follows the listings held back, the one thing drawn").isEqualTo(10);
        assertThat(chart.columns()).hasSize(3);
        WeeklyChart.Column first = chart.columns().get(0);
        assertThat(first.bar().y()).as("the tallest bar fills the scale").isEqualTo(WeeklyChart.TOP);
        assertThat(first.bar().y() + first.bar().height()).isEqualTo(chart.baseline());
        assertThat(first.bar().height()).isPositive();
        assertThat(chart.columns().get(1).bar().height()).isZero();
        assertThat(chart.columns().get(2).bar().height()).isLessThan(first.bar().height()).isPositive();
        assertThat(chart.ticks()).extracting(WeeklyChart.Tick::value).containsExactly(0L, 5L, 10L);
        for (WeeklyChart.Column column : chart.columns()) {
            assertThat(column.bar().x() + column.bar().width()).isLessThanOrEqualTo(chart.width());
        }
    }

    @Test
    void anEmptyChartHasAScaleAndNoBar() {
        WeeklyChart chart = WeeklyChart.of(List.of(new WeeklyChart.Week("2026-W47", "16/11", true, 0, 0)));
        assertThat(chart.max()).isEqualTo(2);
        assertThat(chart.columns().get(0).bar().height()).isZero();
        assertThat(WeeklyChart.niceCeiling(7)).isEqualTo(10);
        assertThat(WeeklyChart.niceCeiling(15)).isEqualTo(20);
        assertThat(WeeklyChart.niceCeiling(120)).isEqualTo(200);
    }

    @Test
    void aCsvCellThatCouldRunAsAFormulaIsDefusedAndQuotedWhenNeeded() {
        assertThat(StatsCsv.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(StatsCsv.cell("@x")).isEqualTo("'@x");
        assertThat(StatsCsv.cell("-3")).isEqualTo("'-3");
        assertThat(StatsCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(StatsCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(StatsCsv.cell(null)).isEmpty();
        assertThat(StatsCsv.rate(null)).isEmpty();
        assertThat(StatsCsv.rate(0.25)).isEqualTo("0.2500");
    }

    @Test
    void theCsvHasABomBothTablesAndNoNote() {
        FeedbackStats stats = new FeedbackStats(new HealthReport.PeriodView(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 16), 16),
                null, Instant.parse("2026-11-16T03:00:00Z"),
                new FeedbackStats.VerdictTotalsView(2, Map.of("CORRECT", 1L, "FALSE_POSITIVE", 1L, "NOT_USEFUL", 0L), 0.5),
                List.of(new FeedbackStats.RuleFeedbackView("listing-rules", "title_length", "RULE", 4, 2, 1, 1, 0, 0.5)),
                List.of(new FeedbackStats.ReviewerFeedbackView("ACCOUNT", "id", "=evil@example.test", 2, 1, 1, 0)));
        ConsoleMessages messages = new ConsoleMessages(messageSource());

        String csv = StatsCsv.csv(messages, stats);

        assertThat(csv).startsWith("﻿").contains("listing-rules,title_length,RULE,4,2,1,1,0,0.5000")
                .contains("'=evil@example.test,ACCOUNT,2,1,1,0").contains("\r\n");
    }

    private static org.springframework.context.MessageSource messageSource() {
        org.springframework.context.support.ResourceBundleMessageSource source =
                new org.springframework.context.support.ResourceBundleMessageSource();
        source.setBasenames("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
