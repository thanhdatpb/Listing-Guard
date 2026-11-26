package com.thanhdatpb.listingguard.hfconvert;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record HfOwnerColumns(String version, String schemaStatus, String datasetRevision, String table, String serial,
                      String name, String party, String date, String file, int firstParty, int lastParty,
                      List<String> suffixes) {
    private static final String DEFAULT = "classpath:hf/hf-owner-columns-2026-12.json";

    HfOwnerColumns {
        if (version == null || version.isBlank() || !("verified".equals(schemaStatus) || "unverified".equals(schemaStatus))
                || ("verified".equals(schemaStatus) && (datasetRevision == null || !datasetRevision.matches("[0-9a-f]{40}")))
                || !java.util.stream.Stream.of(table, serial, name, party, date, file)
                        .allMatch(s -> s != null && s.matches("[a-zA-Z_][a-zA-Z_0-9]*"))
                || firstParty < 0 || lastParty < firstParty || lastParty >= 70
                || suffixes == null || suffixes.isEmpty() || !suffixes.stream().allMatch(s -> s.matches("[A-Z]+"))) {
            throw new HfConvertException("Invalid owner column configuration.");
        }
        suffixes = List.copyOf(suffixes);
    }

    Map<String, String> expectedTypes() {
        return Map.of(serial, "VARCHAR", name, "VARCHAR", party, "VARCHAR", date, "DATE", file, "VARCHAR");
    }

    static HfOwnerColumns load(String location) throws IOException {
        String source = location == null ? DEFAULT : location;
        try (var input = source.startsWith("file:") ? Files.newInputStream(Path.of(source.substring(5)))
                : source.equals(DEFAULT) ? HfOwnerColumns.class.getResourceAsStream("/" + source.substring(10)) : null) {
            if (input == null) {
                throw new HfConvertException("--owner-columns must be file:<path> or the shipped classpath resource.");
            }
            return new ObjectMapper().readValue(input, HfOwnerColumns.class);
        }
    }
}
