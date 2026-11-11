package com.thanhdatpb.listingguard.api.capabilities;

import com.thanhdatpb.listingguard.api.capabilities.Capabilities.FixesCapability;
import com.thanhdatpb.listingguard.api.capabilities.Capabilities.PackCapability;
import com.thanhdatpb.listingguard.api.capabilities.Capabilities.PlansCapability;
import com.thanhdatpb.listingguard.api.capabilities.Capabilities.TrademarkCatalogCapability;
import com.thanhdatpb.listingguard.api.check.FixesProperties;
import com.thanhdatpb.listingguard.api.image.ImageProperties;
import com.thanhdatpb.listingguard.api.image.ImageRulesets;
import com.thanhdatpb.listingguard.api.personalization.PersonalizationProperties;
import com.thanhdatpb.listingguard.api.personalization.PersonalizationRulesets;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.ruleset.ActiveRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.variation.VariationProperties;
import com.thanhdatpb.listingguard.api.variation.VariationRulesets;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Reads what the running deployment serves from the beans that serve it, so the answer cannot differ from the checks (ADR-017).
 * Nothing is stored, nothing is counted against a plan or a quota, and nothing depends on the calling client. It never reads
 * {@code TrademarkCatalogRuntime.Status#detail()}: that text can name the configured catalog.
 */
@Service
public class CapabilitiesService {

    private final ActiveRulesets listingRules;
    private final TrademarkRulesets trademarkRulesets;
    private final TrademarkProperties trademarkProperties;
    private final TrademarkCatalogRuntime trademarkRuntime;
    private final VariationRulesets variationRulesets;
    private final VariationProperties variationProperties;
    private final PersonalizationRulesets personalizationRulesets;
    private final PersonalizationProperties personalizationProperties;
    private final PlanService plans;
    private final FixesProperties fixes;
    private final ImageProperties imageProperties;
    private final ImageRulesets imageRulesets;

    public CapabilitiesService(ActiveRulesets listingRules, TrademarkRulesets trademarkRulesets,
                               TrademarkProperties trademarkProperties, TrademarkCatalogRuntime trademarkRuntime,
                               VariationRulesets variationRulesets, VariationProperties variationProperties,
                               PersonalizationRulesets personalizationRulesets,
                               PersonalizationProperties personalizationProperties, PlanService plans,
                               FixesProperties fixes, ImageProperties imageProperties, ImageRulesets imageRulesets) {
        this.listingRules = listingRules;
        this.trademarkRulesets = trademarkRulesets;
        this.trademarkProperties = trademarkProperties;
        this.trademarkRuntime = trademarkRuntime;
        this.variationRulesets = variationRulesets;
        this.variationProperties = variationProperties;
        this.personalizationRulesets = personalizationRulesets;
        this.personalizationProperties = personalizationProperties;
        this.plans = plans;
        this.fixes = fixes;
        this.imageProperties = imageProperties;
        this.imageRulesets = imageRulesets;
    }

    public Capabilities capabilities() {
        TrademarkCatalogRuntime.State state = trademarkRuntime.status().state();

        List<PackCapability> packs = new ArrayList<>();
        packs.add(new PackCapability(RulesetConfig.DEFAULT_PACK_ID, true, listingRulesVersions()));
        packs.add(new PackCapability(Pack.TRADEMARK_RISK.id(), state == TrademarkCatalogRuntime.State.READY,
                versions(trademarkProperties.marketplaces(), trademarkRulesets.ruleset().version())));
        packs.add(new PackCapability(Pack.VARIATION_CHECKS.id(), true,
                versions(variationProperties.marketplaces(), variationRulesets.ruleset().version())));
        packs.add(new PackCapability(Pack.PERSONALIZATION_SCREENING.id(), true,
                versions(personalizationProperties.marketplaces(), personalizationRulesets.ruleset().version())));
        packs.add(new PackCapability(Pack.IMAGE_CHECKS.id(), imageProperties.enabled(), imageVersions()));
        packs.sort(Comparator.comparing(PackCapability::id));

        return new Capabilities(List.copyOf(packs),
                new TrademarkCatalogCapability(state.name(), trademarkProperties.demoCatalog()),
                new PlansCapability(plans.enabled()),
                new FixesCapability(fixes.enabled()));
    }

    /** The image ruleset of each marketplace: unlike the other packs, each marketplace has a ruleset of its own. */
    private Map<String, String> imageVersions() {
        SortedMap<String, String> versions = new TreeMap<>();
        for (String marketplace : imageRulesets.marketplaces()) {
            versions.put(marketplace, imageRulesets.loaded(marketplace).ruleset().version());
        }
        return Collections.unmodifiableSortedMap(versions);
    }

    private Map<String, String> listingRulesVersions() {
        SortedMap<String, String> versions = new TreeMap<>();
        for (String marketplace : listingRules.marketplaces(RulesetConfig.DEFAULT_PACK_ID)) {
            listingRules.find(RulesetConfig.DEFAULT_PACK_ID, marketplace)
                    .ifPresent(active -> versions.put(marketplace, active.config().version()));
        }
        return Collections.unmodifiableSortedMap(versions);
    }

    private static Map<String, String> versions(List<String> marketplaces, String version) {
        SortedMap<String, String> versions = new TreeMap<>();
        for (String marketplace : marketplaces) {
            versions.put(marketplace.trim().toUpperCase(Locale.ROOT), version);
        }
        return Collections.unmodifiableSortedMap(versions);
    }
}
