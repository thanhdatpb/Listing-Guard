package com.thanhdatpb.listingguard.input;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads a JSON array of standard-schema listing objects without loading the whole document. */
public final class GenericJsonAdapter implements ListingInputAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonFactory FACTORY = new JsonFactory();
    private final String marketplace;
    private final Set<String> fields;

    public GenericJsonAdapter(String marketplace, Set<String> fields) {
        this.marketplace = marketplace;
        this.fields = Set.copyOf(fields);
    }

    @Override public String id() { return "generic-json"; }
    @Override public String marketplace() { return marketplace; }
    @Override public Set<String> formats() { return Set.of("application/json"); }
    @Override public Detection detect(List<String> header) {
        return new Detection(false, 0, List.of("JSON array of listing objects"));
    }

    @Override
    public ListingReader open(InputStream input, Limits limits) throws IOException {
        var parser = FACTORY.createParser(new LimitedStream(input, limits.maxBytes()));
        if (parser.nextToken() != JsonToken.START_ARRAY) throw new IOException("generic-json requires a top-level array");
        return new ListingReader() {
            private long row;
            @Override public ListingRecord next() throws IOException {
                JsonToken token = parser.nextToken();
                if (token == JsonToken.END_ARRAY || token == null) return null;
                if (token != JsonToken.START_OBJECT) throw new IOException("JSON listing row must be an object");
                if (++row > limits.maxRows()) throw new IOException("Input row limit exceeded");
                long sourceLine = parser.currentLocation().getLineNr();
                com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(parser);
                Map<String, Object> listing = new LinkedHashMap<>();
                List<FieldError> errors = new ArrayList<>();
                node.fields().forEachRemaining(entry -> {
                    if (fields.contains(entry.getKey())) listing.put(entry.getKey(), JSON.convertValue(entry.getValue(), Object.class));
                });
                return new ListingRecord(sourceLine, listing, errors);
            }
            @Override public void close() throws IOException { parser.close(); }
        };
    }

    private static final class LimitedStream extends FilterInputStream {
        private final long maximum;
        private long count;
        private LimitedStream(InputStream input, long maximum) { super(input); this.maximum = maximum; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > maximum) throw new IOException("Input byte limit exceeded");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0 && (count += read) > maximum) throw new IOException("Input byte limit exceeded");
            return read;
        }
    }
}
