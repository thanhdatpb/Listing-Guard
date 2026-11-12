package com.thanhdatpb.listingguard.api.policymonitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.ruleset.RulesetProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/** Writes a timestamped JSON and HTML report; snapshots are changed only by a non-dry run. */
public final class PolicyMonitorJob {
    private static final DateTimeFormatter DIRECTORY = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final Pattern REPORT_DIRECTORY = Pattern.compile("\\d{8}T\\d{6}Z");
    private final PolicyDiffEngine engine;
    private final PolicyMonitorProperties properties;
    private final RulesetProperties rulesets;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PolicyMonitorJob(PolicyDiffEngine engine, PolicyMonitorProperties properties, RulesetProperties rulesets,
                            ResourceLoader resourceLoader, ObjectMapper mapper, Clock clock) {
        this.engine = engine;
        this.properties = properties;
        this.rulesets = rulesets;
        this.resourceLoader = resourceLoader;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Path run(boolean dryRun, Path outputOverride) {
        List<PolicyDiffEngine.RuleSource> sources = rulesets.locations().stream().map(resourceLoader::getResource)
                .map(this::ruleSource).toList();
        PolicyDiffEngine.RunFile report = engine.run(properties.manifest(), properties.rawDir(), sources,
                properties.staleDays(), dryRun);
        Path outRoot = (outputOverride == null ? properties.reportDir() : outputOverride).resolve("out");
        Path dir = outRoot.resolve(DIRECTORY.format(clock.instant()));
        try {
            Files.createDirectories(outRoot);
            Files.createDirectory(dir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("report.json").toFile(), report);
            Files.writeString(dir.resolve("report.html"), PolicyMonitorHtml.render(report), StandardCharsets.UTF_8);
            retainRecentReports(outRoot, properties.keepReports());
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("The report directory " + dir + " already exists; no report was overwritten.", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write policy monitor report to " + dir, e);
        }
        return dir;
    }

    /** Keeps only the newest timestamped report directories; unrelated files and symlinks are left alone. */
    static void retainRecentReports(Path outRoot, int keepReports) throws IOException {
        if (keepReports < 1) throw new IllegalArgumentException("keepReports must be at least one");
        Path root = outRoot.toAbsolutePath().normalize();
        List<Path> reports;
        try (Stream<Path> entries = Files.list(root)) {
            reports = entries.filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> REPORT_DIRECTORY.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), Comparator.reverseOrder()))
                    .toList();
        }
        for (Path oldReport : reports.stream().skip(keepReports).toList()) {
            Path target = oldReport.toAbsolutePath().normalize();
            if (!root.equals(target.getParent())) {
                throw new IOException("Refusing to remove a report outside " + root);
            }
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private PolicyDiffEngine.RuleSource ruleSource(Resource resource) {
        try (var input = resource.getInputStream()) {
            return new PolicyDiffEngine.RuleSource(resource.getFilename(),
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read active ruleset " + resource.getDescription(), e);
        }
    }
}
