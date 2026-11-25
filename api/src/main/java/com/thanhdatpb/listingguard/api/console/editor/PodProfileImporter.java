package com.thanhdatpb.listingguard.api.console.editor;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.thanhdatpb.listingguard.api.client.ApiKeyHasher;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Turns a supplier spec file (CSV) into released POD profile resources (ADR-048). One file can hold several product types; each
 * becomes a new version of its profile, written as {@code <id>-v<version>.json} and never over an existing file, so every older
 * version stays. A print area is {@code VERIFIED} only with a source reference and two positive dimensions; a row without them is
 * kept as {@code UNVERIFIED} and the readiness stays incomplete. A malformed file (a missing or unknown column, a dimension that
 * is not a positive number, a date that is not {@code yyyy-MM-dd}, a repeated variant) is refused whole, with the row.
 *
 * <p>Pure file and JSON work: no Spring context, no database, no network.
 */
final class PodProfileImporter {

    private static final List<String> REQUIRED = List.of("product_type", "print_area_width_mm", "print_area_height_mm",
            "source_ref", "verified_by", "verified_at");
    private static final Set<String> KNOWN = Set.of("product_type", "variant", "size", "print_area_width_mm",
            "print_area_height_mm", "max_chars", "source_ref", "verified_by", "verified_at");
    private static final Pattern DIMENSION = Pattern.compile("[0-9]{1,6}(\\.[0-9]{1,3})?");
    private static final Pattern MAX_CHARS = Pattern.compile("[0-9]{1,4}");
    private static final Pattern RESOURCE = Pattern.compile("(.+)-v([0-9]+)\\.json");

    private static final CsvMapper CSV = new CsvMapper()
            .enable(CsvParser.Feature.WRAP_AS_ARRAY)
            .enable(CsvParser.Feature.SKIP_EMPTY_LINES);

    /** What the operator can set for the profiles of the file; a null (or empty) field keeps the inherited or default value. */
    record Options(Integer version, List<String> variationRules, List<String> personalizationFields,
                   List<EditorMarketplace> marketplaces, boolean dryRun) {
        static final Options NONE = new Options(null, null, null, null, false);
    }

    enum Outcome { WRITTEN, UNCHANGED, DRY_RUN }

    /** One profile of the file: where it went (or would go), its SHA-256 and how many print areas are verified. */
    record Item(String key, Path path, String sha256, int verified, int unverified, Outcome outcome) { }

    private final ObjectMapper writer = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    private final ObjectMapper reader = new ObjectMapper();
    private final ResourcePatternResolver resources;
    private final DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n")).withArrayIndenter(new DefaultIndenter("  ", "\n"));

    PodProfileImporter(ResourcePatternResolver resources) {
        this.resources = resources;
    }

    /**
     * @param outDir where the new resources go; created when missing
     * @throws IllegalArgumentException for a file or option that is refused; the message says which row and why; nothing is
     *         written then
     */
    List<Item> importCsv(Path csv, Path outDir, Options options) {
        Map<String, List<PodProfiles.PrintArea>> byProduct = parse(csv);
        if (options.version() != null && byProduct.size() > 1) {
            throw new IllegalArgumentException("--version applies to one product type; the file has " + byProduct.keySet() + ".");
        }
        record Planned(String key, String content, String sha256, int verified, int unverified, Outcome outcome, Path path) { }
        List<Planned> planned = new ArrayList<>();
        for (Map.Entry<String, List<PodProfiles.PrintArea>> product : byProduct.entrySet()) {
            String id = product.getKey();
            TreeMap<Integer, PodProfiles.Profile> existing = existingVersions(id, outDir);
            PodProfiles.Profile latest = existing.isEmpty() ? null : existing.lastEntry().getValue();
            int version = nextVersion(id, existing, options.version());
            PodProfiles.Profile profile = build(id, version, product.getValue(), latest, options);
            int verified = (int) profile.areas().stream().filter(PodProfiles.PrintArea::verified).count();
            int unverified = profile.areas().size() - verified;
            Path path = outDir.resolve(profile.key() + ".json");
            if (latest != null && options.version() == null
                    && render(withVersion(profile, latest.version())).equals(render(latest))) {
                planned.add(new Planned(latest.key(), null, null, verified, unverified, Outcome.UNCHANGED,
                        outDir.resolve(latest.key() + ".json")));
                continue;
            }
            String content = render(profile);
            planned.add(new Planned(profile.key(), content, ApiKeyHasher.sha256Hex(content), verified, unverified,
                    options.dryRun() ? Outcome.DRY_RUN : Outcome.WRITTEN, path));
        }
        if (!options.dryRun()) {
            for (Planned plan : planned) {
                if (plan.outcome() == Outcome.WRITTEN && Files.exists(plan.path())) {
                    throw new IllegalArgumentException(plan.path().getFileName() + " already exists; released versions are immutable.");
                }
            }
            try {
                Files.createDirectories(outDir);
                for (Planned plan : planned) {
                    if (plan.outcome() == Outcome.WRITTEN) {
                        Files.writeString(plan.path(), plan.content(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    }
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot write to " + outDir + ": " + e.getMessage(), e);
            }
        }
        return planned.stream().map(plan -> new Item(plan.key(), plan.path(), plan.sha256(), plan.verified(),
                plan.unverified(), plan.outcome())).toList();
    }

    private static int nextVersion(String id, TreeMap<Integer, PodProfiles.Profile> existing, Integer requested) {
        int latest = existing.isEmpty() ? 0 : existing.lastKey();
        if (requested == null) {
            return latest + 1;
        }
        if (requested < 1) {
            throw new IllegalArgumentException("--version must be 1 or more.");
        }
        if (existing.containsKey(requested)) {
            throw new IllegalArgumentException(id + "-v" + requested + " already exists; released versions are immutable.");
        }
        if (requested < latest) {
            throw new IllegalArgumentException("--version " + requested + " is not new: " + id + " is already at v" + latest + ".");
        }
        return requested;
    }

    private PodProfiles.Profile build(String id, int version, List<PodProfiles.PrintArea> areas,
                                      PodProfiles.Profile latest, Options options) {
        List<EditorMarketplace> marketplaces = pick(options.marketplaces(), latest == null ? null : latest.marketplaces(),
                List.of(EditorMarketplace.ETSY, EditorMarketplace.AMAZON));
        List<String> rules = pick(options.variationRules(), latest == null ? null : latest.variationRules(), List.of("sku"));
        List<String> fields = pick(options.personalizationFields(), latest == null ? null : latest.personalizationFields(),
                List.of());
        if (areas.stream().anyMatch(area -> area.variant() != null) && !rules.contains("size")) {
            throw new IllegalArgumentException(id + ": the file names variants, so its variation rules need \"size\" "
                    + "(pass --variation-rules=sku,size,color).");
        }
        try {
            return new PodProfiles.Profile(id, version, marketplaces, rules, null, areas, fields);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(id + ": " + e.getMessage(), e);
        }
    }

    private static <T> List<T> pick(List<T> option, List<T> inherited, List<T> fallback) {
        return option != null && !option.isEmpty() ? option : inherited != null ? inherited : fallback;
    }

    private static PodProfiles.Profile withVersion(PodProfiles.Profile profile, int version) {
        return new PodProfiles.Profile(profile.id(), version, profile.marketplaces(), profile.variationRules(),
                profile.printArea(), profile.printAreas(), profile.personalizationFields());
    }

    private String render(PodProfiles.Profile profile) {
        try {
            return writer.writer(printer).writeValueAsString(profile) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException("Cannot render " + profile.key(), e);
        }
    }

    /** The released versions of a product type: the shipped resources and the files already in the output directory. */
    private TreeMap<Integer, PodProfiles.Profile> existingVersions(String id, Path outDir) {
        TreeMap<Integer, PodProfiles.Profile> versions = new TreeMap<>();
        try {
            for (Resource resource : resources.getResources(PodProfiles.LOCATION)) {
                collect(id, resource.getFilename(), resource.getContentAsString(StandardCharsets.UTF_8), versions);
            }
            if (Files.isDirectory(outDir)) {
                try (Stream<Path> files = Files.list(outDir)) {
                    for (Path file : files.toList()) {
                        collect(id, file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8), versions);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read the released profiles: " + e.getMessage(), e);
        }
        return versions;
    }

    private void collect(String id, String name, String content, Map<Integer, PodProfiles.Profile> into) throws IOException {
        Matcher match = name == null ? null : RESOURCE.matcher(name);
        if (match == null || !match.matches() || !match.group(1).equals(id)) {
            return;
        }
        into.put(Integer.parseInt(match.group(2)), reader.readValue(content, PodProfiles.Profile.class));
    }

    /** The print areas of the file by product type (the id), in file order. */
    private static Map<String, List<PodProfiles.PrintArea>> parse(Path csv) {
        List<String[]> rows = read(csv);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The file is empty; it needs a header row.");
        }
        Map<String, Integer> column = new HashMap<>();
        String[] header = rows.get(0);
        for (int index = 0; index < header.length; index++) {
            String name = header[index].replace("﻿", "").strip().toLowerCase(Locale.ROOT);
            if (!KNOWN.contains(name)) {
                throw new IllegalArgumentException("Unknown column '" + header[index].strip() + "'. The columns are "
                        + "product_type, variant (or size), print_area_width_mm, print_area_height_mm, max_chars (optional), "
                        + "source_ref, verified_by, verified_at.");
            }
            if (column.put(name, index) != null) {
                throw new IllegalArgumentException("Column '" + name + "' appears twice.");
            }
        }
        if (column.containsKey("variant") && column.containsKey("size")) {
            throw new IllegalArgumentException("Use one of the columns 'variant' and 'size', not both.");
        }
        List<String> missing = new ArrayList<>(REQUIRED.stream().filter(name -> !column.containsKey(name)).toList());
        if (!column.containsKey("variant") && !column.containsKey("size")) {
            missing.add(Math.min(1, missing.size()), "variant (or size)");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing column" + (missing.size() > 1 ? "s" : "") + ": "
                    + String.join(", ", missing) + ".");
        }
        int variantColumn = column.containsKey("variant") ? column.get("variant") : column.get("size");

        Map<String, List<PodProfiles.PrintArea>> byProduct = new LinkedHashMap<>();
        Map<String, Integer> firstRow = new HashMap<>();
        for (int index = 1; index < rows.size(); index++) {
            String[] cells = rows.get(index);
            int row = index + 1;
            if (cells.length > header.length) {
                throw new IllegalArgumentException("Row " + row + " has " + cells.length + " cells but the header has "
                        + header.length + ".");
            }
            String productType = slug(cell(cells, column.get("product_type")));
            if (productType.isEmpty()) {
                throw new IllegalArgumentException("Row " + row + ": product_type is required.");
            }
            if (!productType.matches(PodProfiles.Profile.ID_PATTERN)) {
                throw new IllegalArgumentException("Row " + row + ": product_type '" + cell(cells, column.get("product_type"))
                        + "' does not make a profile id (lowercase letters, digits and hyphens, starting with a letter).");
            }
            String variant = cell(cells, variantColumn);
            BigDecimal width = dimension(cell(cells, column.get("print_area_width_mm")), row, "print_area_width_mm");
            BigDecimal height = dimension(cell(cells, column.get("print_area_height_mm")), row, "print_area_height_mm");
            Integer maxChars = maxChars(column.containsKey("max_chars") ? cell(cells, column.get("max_chars")) : "", row);
            String sourceRef = cell(cells, column.get("source_ref"));
            String verifiedAt = cell(cells, column.get("verified_at"));
            if (!verifiedAt.isEmpty()) {
                try {
                    LocalDate.parse(verifiedAt);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Row " + row + ": verified_at '" + verifiedAt
                            + "' is not a date in the form yyyy-MM-dd.");
                }
            }
            boolean verified = !sourceRef.isEmpty() && width != null && height != null;
            PodProfiles.PrintArea area = new PodProfiles.PrintArea(variant,
                    verified ? PodProfiles.Verification.VERIFIED : PodProfiles.Verification.UNVERIFIED, sourceRef, width,
                    height, maxChars, cell(cells, column.get("verified_by")), verifiedAt);
            String identity = productType + "\u0000" + PodProfiles.Profile.variantKey(variant);
            Integer earlier = firstRow.putIfAbsent(identity, row);
            if (earlier != null) {
                throw new IllegalArgumentException("Row " + row + " repeats the variant of row " + earlier + " for "
                        + productType + ".");
            }
            byProduct.computeIfAbsent(productType, key -> new ArrayList<>()).add(area);
        }
        if (byProduct.isEmpty()) {
            throw new IllegalArgumentException("The file has a header and no rows.");
        }
        return byProduct;
    }

    private static List<String[]> read(Path csv) {
        try (Reader in = new BufferedReader(new InputStreamReader(Files.newInputStream(csv), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            MappingIterator<String[]> rows = CSV.readerFor(String[].class)
                    .with(CsvSchema.emptySchema().withColumnSeparator(',')).readValues(in);
            List<String[]> kept = new ArrayList<>();
            for (String[] row : rows.readAll()) {
                if (java.util.Arrays.stream(row).anyMatch(cell -> !cell.isBlank())) {
                    kept.add(row);
                }
            }
            return kept;
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot read " + csv.getFileName() + " as UTF-8 CSV: " + e.getMessage(), e);
        }
    }

    private static String cell(String[] cells, int index) {
        return index < cells.length ? cells[index].strip() : "";
    }

    private static String slug(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    /** Blank is no value; anything else is a positive number of millimetres with a decimal point. */
    private static BigDecimal dimension(String text, int row, String name) {
        if (text.isEmpty()) {
            return null;
        }
        if (!DIMENSION.matcher(text).matches()) {
            String shown = text.length() > 20 ? text.substring(0, 20) + "…" : text;
            throw new IllegalArgumentException("Row " + row + ": " + name + " '" + shown + "' is not a positive number of "
                    + "millimetres (use a decimal point, at most 3 decimals" + (text.startsWith("-") ? "; a dimension cannot be "
                    + "negative" : "") + ").");
        }
        BigDecimal value = new BigDecimal(text).stripTrailingZeros();
        if (value.signum() == 0) {
            throw new IllegalArgumentException("Row " + row + ": " + name + " must be positive, not zero.");
        }
        return value.scale() < 0 ? value.setScale(0) : value;
    }

    private static Integer maxChars(String text, int row) {
        if (text.isEmpty()) {
            return null;
        }
        if (!MAX_CHARS.matcher(text).matches() || Integer.parseInt(text) < 1) {
            throw new IllegalArgumentException("Row " + row + ": max_chars '" + text + "' is not a whole number from 1 to 9999.");
        }
        return Integer.valueOf(text);
    }
}
