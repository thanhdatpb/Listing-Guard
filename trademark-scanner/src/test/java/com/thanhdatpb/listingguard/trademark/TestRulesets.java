package com.thanhdatpb.listingguard.trademark;

import com.thanhdatpb.listingguard.trademark.config.TrademarkRuleset;
import com.thanhdatpb.listingguard.trademark.config.TrademarkRulesetLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The synthetic test ruleset. Tests read lists, distances and levels from it instead of assuming them. */
public final class TestRulesets {

    public static final String TEST = "rulesets/trademark-test.yaml";
    public static final String SHIPPED = "rulesets/trademark-risk-2026-11.yaml";

    private TestRulesets() {
    }

    public static String text(String path) {
        try (InputStream in = TestRulesets.class.getResourceAsStream("/" + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TrademarkRuleset test() {
        return TrademarkRulesetLoader.loadClasspath(TEST);
    }

    public static TrademarkRuleset shipped() {
        return TrademarkRulesetLoader.loadClasspath(SHIPPED);
    }

    /** The test ruleset with one text replaced (exactly once present), parsed. */
    public static TrademarkRuleset variant(String from, String to) {
        return TrademarkRulesetLoader.parse(variantText(from, to));
    }

    public static String variantText(String from, String to) {
        String text = text(TEST);
        if (!text.contains(from)) {
            throw new IllegalArgumentException("Test ruleset does not contain: " + from);
        }
        return text.replace(from, to);
    }
}
