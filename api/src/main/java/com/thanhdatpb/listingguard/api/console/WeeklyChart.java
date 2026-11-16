package com.thanhdatpb.listingguard.api.console;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * The weekly bar chart of the console home page (ADR-034, ADR-041), as coordinates the template writes into an inline SVG: no script and
 * no chart library. Each week has one bar, the listings with an {@code ERROR} that the checks held back, on a scale that starts at zero;
 * the current week is drawn in the brand color and the weeks before it in the pale one (the template picks the class). The page puts
 * the numbers of both counts, the listings checked too, in a table under the chart, so the chart is never the only place they are.
 */
public record WeeklyChart(int width, int height, int left, int baseline, long max, List<Column> columns, List<Tick> ticks) {

    /** One week as the page receives it: an ISO week label, its first day (dd/MM), whether it is still running, and the counts. */
    public record Week(String isoWeek, String label, boolean partial, long checked, long withError) {
    }

    /** A bar's rectangle in SVG user units. */
    public record Bar(int x, int y, int width, int height) {
    }

    public record Column(Week week, Bar bar, int labelX) {
    }

    /** A line of the scale: its height and the count it stands for. */
    public record Tick(int y, long value) {
    }

    static final int WIDTH = 640;
    static final int HEIGHT = 220;
    static final int LEFT = 44;
    static final int RIGHT = 8;
    static final int TOP = 12;
    static final int BOTTOM = 32;

    public static WeeklyChart of(List<Week> weeks) {
        return of(weeks, Week::withError);
    }

    /** The same chart for another figure of the week: the report page draws the listing-rules results of each week, not the errors. */
    public static WeeklyChart of(List<Week> weeks, ToLongFunction<Week> measure) {
        long max = 0;
        for (Week week : weeks) {
            max = Math.max(max, measure.applyAsLong(week));
        }
        long scale = niceCeiling(max);
        int baseline = HEIGHT - BOTTOM;
        int plotHeight = baseline - TOP;
        int plotWidth = WIDTH - LEFT - RIGHT;
        int slot = weeks.isEmpty() ? plotWidth : plotWidth / weeks.size();
        int barWidth = Math.max(8, (int) Math.round(slot * 0.5));
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < weeks.size(); i++) {
            Week week = weeks.get(i);
            int start = LEFT + i * slot + (slot - barWidth) / 2;
            columns.add(new Column(week, bar(start, barWidth, measure.applyAsLong(week), scale, baseline, plotHeight), LEFT + i * slot + slot / 2));
        }
        List<Tick> ticks = new ArrayList<>();
        for (int step = 0; step <= 2; step++) {
            long value = scale * step / 2;
            ticks.add(new Tick(baseline - (int) Math.round((double) plotHeight * step / 2), value));
        }
        return new WeeklyChart(WIDTH, HEIGHT, LEFT, baseline, scale, List.copyOf(columns), List.copyOf(ticks));
    }

    /** A bar with a count of zero has no height; one with a count above zero is at least one unit high, so it can be seen. */
    private static Bar bar(int x, int width, long value, long scale, int baseline, int plotHeight) {
        int height = value == 0 ? 0 : Math.max(1, (int) Math.round((double) plotHeight * value / scale));
        return new Bar(x, baseline - height, width, height);
    }

    /** The smallest even number of 1, 2 or 5 times a power of ten that is at least {@code max}; 2 when there is nothing. */
    static long niceCeiling(long max) {
        if (max <= 2) {
            return 2;
        }
        long power = 1;
        while (power * 10 < max) {
            power *= 10;
        }
        for (long factor : new long[] {1, 2, 5, 10}) {
            long candidate = factor * power;
            if (candidate >= max && candidate % 2 == 0) {
                return candidate;
            }
        }
        return 10 * power;
    }
}
