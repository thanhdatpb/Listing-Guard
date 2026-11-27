package com.thanhdatpb.listingguard.api.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** The fixed main fields of an Etsy listing, read from {@code workspace/etsy-fields.yaml} (ADR-059). Marked unverified in the file. */
public record EtsyFields(String version, List<String> fields) {

    public static final String RESOURCE = "workspace/etsy-fields.yaml";

    public EtsyFields {
        fields = List.copyOf(fields);
    }

    public static EtsyFields load() {
        try (InputStream in = EtsyFields.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing.");
            }
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(in);
            if (!"unverified".equals(root.path("source").path("status").asText())) {
                throw new IllegalStateException(RESOURCE + ": source.status must stay unverified.");
            }
            List<String> fields = new ArrayList<>();
            root.path("fields").forEach(f -> fields.add(f.asText()));
            if (fields.isEmpty() || fields.stream().distinct().count() != fields.size()) {
                throw new IllegalStateException(RESOURCE + ": the field list must be non-empty and without repeats.");
            }
            return new EtsyFields(root.path("version").asText(), fields);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }
}
