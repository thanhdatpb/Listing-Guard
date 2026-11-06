package com.thanhdatpb.listingguard.ruleengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A guard for the next rule: a rule whose ruleset has a numeric {@code max_*} setting must declare it through
 * {@link Rule#limits}, or clients that read {@code RuleEngine.limits} would not see it. It reads every ruleset that ships in
 * {@code src/main/resources/rulesets}, defaults and product type overrides, so a ruleset added later is covered too.
 */
class LimitCoverageTest {

    private static final Path RULESETS = Path.of("src", "main", "resources", "rulesets");

    private final RuleRegistry registry = RuleRegistry.withDefaults();

    @Test
    void everyMaxSettingOfAShippedRulesetIsDeclaredAsALimitByItsRule() throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(RULESETS)) {
            files = listing.filter(path -> path.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
        assertThat(files).as("the shipped rulesets").isNotEmpty();

        int checked = 0;
        for (Path file : files) {
            RulesetConfig config = RulesetLoader.parse(Files.readString(file));
            for (Rule rule : registry.rulesFor(config.packId(), config.marketplace())) {
                RuleSettings defaults = config.rules().get(rule.id());
                if (defaults == null) {
                    continue;
                }
                for (RuleSettings settings : defaults.withOverrides()) {
                    for (Map.Entry<String, Object> param : settings.params().entrySet()) {
                        if (param.getKey().startsWith("max_") && param.getValue() instanceof Number number) {
                            List<Integer> declared = rule.limits(settings).stream().map(Limit::value).toList();
                            assertThat(declared)
                                    .as("%s: rule '%s' has %s=%s but declares no such limit; override Rule.limits",
                                            file.getFileName(), rule.id(), param.getKey(), number)
                                    .contains(number.intValue());
                            checked++;
                        }
                    }
                }
            }
        }
        assertThat(checked).as("at least one max_ setting was found").isPositive();
    }
}
