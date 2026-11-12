package com.thanhdatpb.listingguard.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The contract of the latest published release, kept in {@code docs/api/baseline/} (ADR-022, sections 4 and 8): exactly one
 * {@code openapi-v<version>.json}, and at most one {@code input-schema-v<same version>.json}. Each is a byte copy of the file at
 * the release tag; the release workflow checks that.
 *
 * @param version     the release tag, for example {@code v0.6.0}
 * @param openApi     the OpenAPI document of that release
 * @param inputSchema the input schema of that release, when the release shipped one
 */
public record ContractBaseline(String version, Path openApi, Optional<Path> inputSchema) {

    private static final Pattern OPENAPI = Pattern.compile("openapi-(v\\d+\\.\\d+\\.\\d+)\\.json");
    private static final Pattern INPUT_SCHEMA = Pattern.compile("input-schema-(v\\d+\\.\\d+\\.\\d+)\\.json");

    /** Finds the baseline in a directory, or fails with a message that says what is wrong. */
    public static ContractBaseline find(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("No baseline directory at " + directory);
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(directory)) {
            files = list.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Path> openApis = new ArrayList<>();
        List<Path> inputSchemas = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (OPENAPI.matcher(name).matches()) {
                openApis.add(file);
            } else if (INPUT_SCHEMA.matcher(name).matches()) {
                inputSchemas.add(file);
            } else {
                unexpected.add(name);
            }
        }
        if (!unexpected.isEmpty()) {
            throw new IllegalStateException("Unexpected files in " + directory + ": " + unexpected
                    + "; it holds only openapi-v<version>.json and input-schema-v<version>.json (ADR-022)");
        }
        if (openApis.size() != 1) {
            throw new IllegalStateException("Expected exactly one openapi-v<version>.json in " + directory + ", found " + openApis.size()
                    + "; the baseline is the contract of the latest published release (ADR-022)");
        }
        String version = version(OPENAPI, openApis.getFirst());
        if (inputSchemas.size() > 1) {
            throw new IllegalStateException("Expected at most one input-schema-v<version>.json in " + directory + ", found " + inputSchemas.size());
        }
        Optional<Path> inputSchema = inputSchemas.stream().findFirst();
        if (inputSchema.isPresent() && !version.equals(version(INPUT_SCHEMA, inputSchema.get()))) {
            throw new IllegalStateException("The input schema baseline " + inputSchema.get().getFileName()
                    + " is not of the OpenAPI baseline's release " + version);
        }
        return new ContractBaseline(version, openApis.getFirst(), inputSchema);
    }

    private static String version(Pattern pattern, Path file) {
        Matcher matcher = pattern.matcher(file.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException("Not a baseline file name: " + file);
        }
        return matcher.group(1);
    }
}
