package com.thanhdatpb.listingguard.api.console.editor;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The {@code pod-profile} commands of the admin job (ADR-048). {@code pod-profile import <file.csv>} turns a supplier spec file
 * into new versions of the POD profiles it names and writes them as resources; it reads the file and the released profiles and
 * writes only the new files. Nothing is read from or written to the database or the network. The new resources reach the
 * Console with the next build, like any other released profile.
 */
public final class PodProfileAdminCommands {

    public static final String USAGE = """
              pod-profile import <file.csv> [--out=<dir>] [--version=<n>] [--dry-run]
                                            [--variation-rules=sku,size,color] [--personalization-fields=text] [--marketplaces=ETSY,AMAZON]
                (a new version per product type of the file; --out defaults to api/src/main/resources/pod, which must exist;
                 the options set the fields of a product type that has no released profile and override an existing one)
            """;

    private static final String DEFAULT_OUT = "api/src/main/resources/pod";

    private PodProfileAdminCommands() {
    }

    /**
     * @param args the arguments after the group and the command ({@code <file.csv>})
     * @throws IllegalArgumentException for a command or file that is refused; the message says why
     */
    public static void execute(String command, List<String> args, ApplicationArguments arguments, PrintStream out) {
        if (!"import".equals(command)) {
            throw new IllegalArgumentException("Unknown command 'pod-profile " + command + "'.");
        }
        if (args.isEmpty()) {
            throw new IllegalArgumentException("Missing the CSV file to import.");
        }
        Path csv = Path.of(args.get(0));
        if (!Files.isRegularFile(csv)) {
            throw new IllegalArgumentException("The file " + csv + " does not exist.");
        }
        Path outDir = Path.of(single(arguments, "out", DEFAULT_OUT));
        if (arguments.getOptionValues("out") == null && !Files.isDirectory(outDir)) {
            throw new IllegalArgumentException("The default output directory " + DEFAULT_OUT
                    + " is not here; run from the repository root or pass --out=<dir>.");
        }
        PodProfileImporter.Options options = new PodProfileImporter.Options(version(arguments),
                list(arguments, "variation-rules"), list(arguments, "personalization-fields"), marketplaces(arguments),
                arguments.containsOption("dry-run"));
        List<PodProfileImporter.Item> items = new PodProfileImporter(new PathMatchingResourcePatternResolver())
                .importCsv(csv, outDir, options);
        for (PodProfileImporter.Item item : items) {
            switch (item.outcome()) {
                case WRITTEN -> out.println("Wrote " + item.path() + " (" + item.verified() + " verified, " + item.unverified()
                        + " unverified print areas). SHA-256 " + item.sha256());
                case DRY_RUN -> out.println("Would write " + item.path() + " (" + item.verified() + " verified, "
                        + item.unverified() + " unverified print areas). SHA-256 " + item.sha256());
                case UNCHANGED -> out.println(item.key() + " already holds exactly this data; nothing written.");
            }
        }
        if (items.stream().anyMatch(item -> item.outcome() == PodProfileImporter.Outcome.WRITTEN)) {
            out.println("Commit the new files; they reach the Console with the next build. A profile with an unverified print "
                    + "area keeps the production readiness INCOMPLETE. Pin the SHA-256 above in PodProfilesTest.");
        }
    }

    private static String single(ApplicationArguments arguments, String name, String fallback) {
        List<String> values = arguments.getOptionValues(name);
        if (values == null) {
            return fallback;
        }
        if (values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("Pass --" + name + " once, with a value.");
        }
        return values.get(0);
    }

    private static Integer version(ApplicationArguments arguments) {
        String value = single(arguments, "version", null);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--version '" + value + "' is not a whole number.");
        }
    }

    private static List<String> list(ApplicationArguments arguments, String name) {
        String value = single(arguments, name, null);
        return value == null ? null : Arrays.stream(value.split(",")).map(String::strip).filter(item -> !item.isEmpty()).toList();
    }

    private static List<EditorMarketplace> marketplaces(ApplicationArguments arguments) {
        List<String> names = list(arguments, "marketplaces");
        if (names == null) {
            return null;
        }
        List<EditorMarketplace> marketplaces = new ArrayList<>();
        for (String name : names) {
            marketplaces.add(EditorMarketplace.parse(name).orElseThrow(() ->
                    new IllegalArgumentException("Unknown marketplace '" + name + "' in --marketplaces (ETSY or AMAZON).")));
        }
        return marketplaces;
    }
}
