package com.thanhdatpb.listingguard.api.evaluation.backtest;

import com.thanhdatpb.listingguard.api.batch.BatchConfigException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The label columns of a backtest file (ADR-032): whether the listing was claimed or taken down, what the claim was about,
 * and the mark it named. They are found in the header at start-up; a missing or repeated column stops the job before any
 * row is read. A cell that does not hold a label this class knows is refused for its row and reported with its line: the
 * row is not measured, and it is never counted as either answer.
 */
final class BacktestLabels {

    /** What the labels of one row say. {@code claimed} is null when the cell is blank: the row is then not measured. */
    record Labels(Boolean claimed, IssueType issue, String mark) {
    }

    /** A label cell that cannot be read. The message is Vietnamese and names the column, never the listing's text. */
    static final class LabelException extends RuntimeException {
        LabelException(String message) {
            super(message, null, false, false);
        }
    }

    private static final Set<String> YES = Set.of("yes", "y", "true", "1");
    private static final Set<String> NO = Set.of("no", "n", "false", "0");
    private static final int CELL_SHOWN = 30;
    private static final int MARK_MAX_CHARS = 200;

    private final String claimedName;
    private final int claimedIndex;
    private final String issueName;
    private final int issueIndex;
    private final String markName;
    private final int markIndex;

    private BacktestLabels(String claimedName, int claimedIndex, String issueName, int issueIndex, String markName,
                           int markIndex) {
        this.claimedName = claimedName;
        this.claimedIndex = claimedIndex;
        this.issueName = issueName;
        this.issueIndex = issueIndex;
        this.markName = markName;
        this.markIndex = markIndex;
    }

    /**
     * @param header        the trimmed header names in file order
     * @param claimedColumn the column with yes/no, required
     * @param issueColumn   the column with the issue type, or null
     * @param markColumn    the column with the mark the claim named, or null
     * @throws BatchConfigException when a named column is missing or appears more than once
     */
    static BacktestLabels bind(List<String> header, String claimedColumn, String issueColumn, String markColumn) {
        return new BacktestLabels(claimedColumn, index(header, claimedColumn), issueColumn,
                issueColumn == null ? -1 : index(header, issueColumn), markColumn,
                markColumn == null ? -1 : index(header, markColumn));
    }

    private static int index(List<String> header, String column) {
        int found = -1;
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).equals(column)) {
                if (found >= 0) {
                    throw new BatchConfigException("The header of the input file has the label column '" + column
                            + "' more than once, so the backtest cannot tell which one to read.");
                }
                found = i;
            }
        }
        if (found < 0) {
            throw new BatchConfigException("The header of the input file has no label column '" + column
                    + "'. Columns are matched exactly (case and spaces matter).");
        }
        return found;
    }

    /**
     * The labels of a row whose cell count already matches the header.
     *
     * @throws LabelException for a claimed cell that is not yes/no, or an issue type that is not on the list
     */
    Labels read(String[] cells) {
        String claimedText = cells[claimedIndex].trim();
        Boolean claimed = claimed(claimedText);
        if (claimed == null) {
            return new Labels(null, null, null);
        }
        String mark = markIndex < 0 ? null : blankToNull(cells[markIndex]);
        if (mark != null && mark.length() > MARK_MAX_CHARS) {
            throw new LabelException("mark '" + markName + "' dài hơn " + MARK_MAX_CHARS + " ký tự; ghi tên mark, không ghi cả đoạn văn.");
        }
        if (!claimed) {
            // the issue of a listing that was not claimed says nothing; it is not read, so a stale cell cannot refuse the row
            return new Labels(false, null, mark);
        }
        String issueText = issueIndex < 0 ? null : blankToNull(cells[issueIndex]);
        if (issueText == null) {
            return new Labels(true, IssueType.UNSPECIFIED, mark);
        }
        Optional<IssueType> issue = IssueType.parse(issueText);
        if (issue.isEmpty()) {
            throw new LabelException("loại vấn đề '" + issueName + "' = '" + clip(issueText) + "' không có trong danh sách ("
                    + IssueType.accepted() + "; hoặc để trống)");
        }
        return new Labels(true, issue.get(), mark);
    }

    private Boolean claimed(String text) {
        if (text.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (YES.contains(lower)) {
            return Boolean.TRUE;
        }
        if (NO.contains(lower)) {
            return Boolean.FALSE;
        }
        throw new LabelException("nhãn '" + claimedName + "' = '" + clip(text)
                + "' không hợp lệ (dùng yes/no, true/false hoặc 1/0; để trống nếu chưa biết)");
    }

    private static String blankToNull(String cell) {
        String value = cell.trim();
        return value.isEmpty() ? null : value;
    }

    private static String clip(String value) {
        return value.length() <= CELL_SHOWN ? value : value.substring(0, CELL_SHOWN) + "...";
    }

    /** The label column names, for the report. */
    List<String> columns() {
        List<String> names = new ArrayList<>();
        names.add(claimedName);
        if (issueName != null) {
            names.add(issueName);
        }
        if (markName != null) {
            names.add(markName);
        }
        return names;
    }
}
