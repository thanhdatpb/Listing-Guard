package com.thanhdatpb.listingguard.api.console.phrase;

import com.thanhdatpb.listingguard.api.console.bulk.DelimitedText;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningRequest.PhraseInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

record PhraseInputTable(List<String> headers, List<DelimitedText.Row> rows) {
    private static final int MAX_COLUMNS = 100;

    static PhraseInputTable read(String text) {
        var table = DelimitedText.parse(text, DelimitedText.detectDelimiter(text));
        if (table.rows().size() < 2 || table.rows().getFirst().cells().size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("phrase.error.table");
        }
        var headers = table.rows().getFirst().cells().stream().map(String::strip).toList();
        var rows = table.rows().subList(1, table.rows().size());
        return new PhraseInputTable(headers, rows);
    }

    int guess(boolean volume) {
        for (int i = 0; i < headers.size(); i++) {
            String label = headers.get(i).toLowerCase(Locale.ROOT).replaceAll("[ _-]+", " ");
            if (volume ? label.contains("volume") : label.contains("keyword") || label.contains("phrase")) {
                return i;
            }
        }
        return -1;
    }

    int remembered(String header) {
        return header == null || headers.indexOf(header) != headers.lastIndexOf(header) ? -1 : headers.indexOf(header);
    }

    List<PhraseInput> values(int phrase, int volume) {
        if (phrase < 0 || phrase >= headers.size() || volume >= headers.size() || volume < -1 || phrase == volume) {
            throw new IllegalArgumentException("phrase.error.mapping");
        }
        var values = new ArrayList<PhraseInput>();
        for (var row : rows) {
            values.add(new PhraseInput(cell(row, phrase), parseVolume(cell(row, volume))));
        }
        return values;
    }

    private static String cell(DelimitedText.Row row, int column) {
        return column >= 0 && column < row.cells().size() ? row.cells().get(column) : "";
    }

    static Long parseVolume(String text) {
        String value = text.strip();
        if (!value.matches("[0-9]+|[0-9]{1,3}(,[0-9]{3})+")) {
            return null;
        }
        try {
            return Long.valueOf(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
