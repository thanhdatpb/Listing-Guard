package com.thanhdatpb.listingguard.api.console.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.api.console.ConsoleDates;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.editor.EditorLimits;
import com.thanhdatpb.listingguard.api.ruleset.RulesetLimitsService;
import com.thanhdatpb.listingguard.api.ruleset.RulesetNotFoundException;
import com.thanhdatpb.listingguard.api.workspace.EtsyFields;
import com.thanhdatpb.listingguard.api.workspace.ListingStatusService;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceAccess.Actor;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceListings;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.SellerAccount;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * What the listing pages show of the release of a listing (ADR-063): when it was exported, the buttons that follow from its status, and, for an
 * Etsy listing, the panel "copy each item" in the order of {@code etsy-fields.yaml} with the counts the ruleset allows. Every value of the
 * listing is printed by the page with {@code th:text}; every word is a message of both languages.
 */
@Component
@ConditionalOnExpression("${listing-guard.console.enabled:false} && ${listing-guard.workspace.enabled:false}")
public class WorkspaceReleaseViews {

    /** The most tags that are joined into one copied text; a listing never holds more than the form of the marketplace allows. */
    private static final String TAG_SEPARATOR = ", ";

    /**
     * One item of the Etsy panel. {@code count} is a sentence of the language of the page ("42/140 characters"), or null when nothing is
     * known; {@code over} is true when it is above a limit of the ruleset (the sentence says so too: colour only helps).
     */
    public record CopyItem(String key, String label, String value, boolean empty, boolean multiline, String count, boolean over) {
    }

    private final EtsyFields etsy;
    private final ConsoleMessages messages;
    private final RulesetLimitsService rulesetLimits;
    private final ListingStatusService status;
    private final WorkspaceListings listings;
    private final WorkspaceAccess access;

    public WorkspaceReleaseViews(EtsyFields etsy, ConsoleMessages messages, RulesetLimitsService rulesetLimits, ListingStatusService status,
                                 WorkspaceListings listings, WorkspaceAccess access) {
        this.etsy = etsy;
        this.messages = messages;
        this.rulesetLimits = rulesetLimits;
        this.status = status;
        this.listings = listings;
        this.access = access;
    }

    /**
     * Adds to the model: {@code exportedAt} (a sentence, or null), {@code canRelease} (the person may change the listing), and for an Etsy listing
     * {@code etsyPanel} (the items), {@code etsyLimitsKnown}, {@code postBlocks} (why "posted" is held back, as words) and {@code postable}.
     */
    public void fill(Model model, Actor actor, Listing listing, SellerAccount account) {
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        Instant exported = listings.exportedAt(actor.clientId(), List.of(listing.id())).get(listing.id());
        boolean canRelease = access.canEdit(actor, listing.ownerAccountId());
        model.addAttribute("exportedAt", exported == null ? null : ConsoleDates.dateTime(messages, exported));
        model.addAttribute("canRelease", canRelease);
        model.addAttribute("needsReexport", !listings.needsReexport(actor.clientId(), List.of(listing.id())).isEmpty());
        model.addAttribute("isDraft", listing.status() == ListingStatus.DRAFT);
        model.addAttribute("isReady", listing.status() == ListingStatus.READY_TO_EXPORT);
        model.addAttribute("isExported", listing.status() == ListingStatus.EXPORTED);
        model.addAttribute("isEtsy", account.marketplace() == Marketplace.ETSY);
        if (account.marketplace() != Marketplace.ETSY) {
            return;
        }
        EditorLimits limits = limits(account, listing);
        model.addAttribute("etsyPanel", panel(listing, limits));
        model.addAttribute("etsyLimitsKnown", limits != null);
        if (listing.status() == ListingStatus.READY_TO_EXPORT) {
            var verdict = status.verdictsOf(actor.clientId(), List.of(listing)).get(listing.id());
            model.addAttribute("postBlocks", verdict.blocks().stream().map(labels::block).toList());
            model.addAttribute("postable", verdict.mayGo());
            model.addAttribute("postOpened", verdict.opened() != null && !verdict.blocks().isEmpty());
            model.addAttribute("postUnchecked", !verdict.trademarkChecked());
            model.addAttribute("postGate", status.gateActive());
            model.addAttribute("postAuditOff", !status.auditActive());
        }
    }

    private EditorLimits limits(SellerAccount account, Listing listing) {
        try {
            return EditorLimits.of(rulesetLimits.limits(account.marketplace().name(), "").limits());
        } catch (RulesetNotFoundException e) {
            return null;
        }
    }

    /** The items in the order of {@code etsy-fields.yaml}. */
    List<CopyItem> panel(Listing listing, EditorLimits limits) {
        WorkspaceLabels labels = new WorkspaceLabels(messages);
        List<CopyItem> items = new ArrayList<>();
        for (String key : etsy.fields()) {
            String value = valueOf(listing, key);
            boolean empty = value.isEmpty();
            String count = null;
            boolean over = false;
            if (!empty) {
                int characters = value.codePointCount(0, value.length());
                if ("tags".equals(key)) {
                    List<String> tags = tags(listing);
                    int tooLong = limits == null || limits.tagLengthMax() == null ? 0
                            : (int) tags.stream().filter(t -> t.codePointCount(0, t.length()) > limits.tagLengthMax()).count();
                    over = limits != null && limits.tagCountMax() != null && tags.size() > limits.tagCountMax() || tooLong > 0;
                    count = (limits != null && limits.tagCountMax() != null ? messages.get("workspace.copy.count.tags.max", tags.size(), limits.tagCountMax())
                            : messages.get("workspace.copy.count.tags", tags.size()))
                            + (tooLong > 0 ? "; " + messages.get("workspace.copy.count.taglong", tooLong, limits.tagLengthMax()) : "");
                } else if ("title".equals(key) && limits != null && limits.titleMax() != null) {
                    over = characters > limits.titleMax();
                    count = messages.get("workspace.copy.count.chars.max", characters, limits.titleMax());
                } else {
                    count = messages.get("workspace.copy.count.chars", characters);
                }
            }
            items.add(new CopyItem(key, labels.target(key), value, empty, value.indexOf('\n') >= 0 || "description".equals(key), count, over));
        }
        return items;
    }

    /** The text of one field as the person copies it: tags and materials joined by a comma and a space, the others as stored. */
    private static String valueOf(Listing listing, String key) {
        if ("tags".equals(key) || "materials".equals(key)) {
            JsonNode list = listing.fields().path(key);
            if (list.isArray()) {
                List<String> parts = new ArrayList<>();
                list.forEach(n -> parts.add(n.asText()));
                return String.join(TAG_SEPARATOR, parts);
            }
            if (list.isTextual()) {
                return list.asText();
            }
        }
        Map<String, String> data = listing.data();
        return data.getOrDefault(key, "");
    }

    private static List<String> tags(Listing listing) {
        List<String> tags = new ArrayList<>();
        JsonNode list = listing.fields().path("tags");
        if (list.isArray()) {
            list.forEach(n -> tags.add(n.asText()));
        }
        return tags;
    }
}
