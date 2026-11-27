package com.thanhdatpb.listingguard.api.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON and hash helpers of the stores. A column of {@code data} is a string by key; the order of the keys is the template's. */
final class WorkspaceJson {

    private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP = new TypeReference<>() { };

    private final ObjectMapper mapper;

    WorkspaceJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write JSON.", e);
        }
    }

    JsonNode tree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored JSON document cannot be read.", e);
        }
    }

    Map<String, String> stringMap(String json) {
        try {
            return mapper.readValue(json, STRING_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored JSON document cannot be read.", e);
        }
    }

    <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored JSON document cannot be read.", e);
        }
    }

    ObjectMapper mapper() {
        return mapper;
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The identity of a listing's content: its columns in order and its role. Owner and status are not content. */
    static String contentHash(Map<String, String> data, String role) {
        StringBuilder builder = new StringBuilder(role).append('\u0003');
        data.forEach((key, value) -> builder.append(key).append('\u0001').append(value).append('\u0002'));
        return sha256(builder.toString());
    }

    static String rowsHash(List<String> cells) {
        return sha256(String.join("\u0001", cells));
    }
}
