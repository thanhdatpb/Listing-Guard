package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A fact the listing tells two ways, the usual trace of a copy that was only half edited (ADR-039): the title says {@code 11oz} and a bullet says
 * {@code 15 oz}. One class serves the size, quantity and recipient rules; each differs by its {@link FactExtractor} and its sentence. The
 * {@code anchor} text (the title) is compared with each text of {@code against}; a conflict is a family both state with no value in common. A fact
 * only one text states is not compared. The finding is on the other text, at the conflicting mention, and its message names both values and both
 * fields. No fix: which of the two is the leftover is the seller's to say.
 */
public final class FactConflictRule implements Rule {

    private final String id;
    private final Function<RuleSettings, FactExtractor> extractor;
    private final String what;

    private FactConflictRule(String id, Function<RuleSettings, FactExtractor> extractor, String what) {
        this.id = id;
        this.extractor = extractor;
        this.what = what;
    }

    public static FactConflictRule sizeUnit() {
        return new FactConflictRule("size_unit_conflict", SizeFacts::from, "Kích cỡ");
    }

    public static FactConflictRule quantity() {
        return new FactConflictRule("quantity_conflict", QuantityFacts::from, "Số lượng");
    }

    public static FactConflictRule recipient() {
        return new FactConflictRule("recipient_conflict", RecipientFacts::from, "Người nhận");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean optional() {
        return true;
    }

    @Override
    public void validate(RulesetConfig config) {
        RuleSettings settings = config.rule(id);
        ListingTexts.validate(id, List.of(settings.stringParam("anchor")));
        ListingTexts.validate(id, settings.stringListParam("against"));
        extractor.apply(settings);
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        RuleSettings settings = config.rule(id);
        List<Finding> findings = new ArrayList<>();
        for (FactConflicts.Conflict conflict : FactConflicts.find(listing, settings.stringParam("anchor"),
                settings.stringListParam("against"), extractor.apply(settings))) {
            Mention other = conflict.other();
            findings.add(finding(config, conflict.otherText().field(), other.text(),
                    what + " không khớp: " + conflict.anchorText().field() + " ghi " + TextRule.quote(conflict.anchor().text()) + " nhưng "
                            + conflict.otherText().field() + " ghi " + TextRule.quote(other.text()) + ".",
                    "Xem lại hai chỗ này: bản nhân bản thường còn sót giá trị của listing cũ. Sửa chỗ còn sai cho cùng một giá trị.")
                    .withSpan(other.start(), other.end()));
        }
        return findings;
    }
}
