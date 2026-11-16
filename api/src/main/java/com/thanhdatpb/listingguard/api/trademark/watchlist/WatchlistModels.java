package com.thanhdatpb.listingguard.api.trademark.watchlist;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The request and answer bodies of {@code /api/v1/trademark-watchlist} (ADR-031). */
public final class WatchlistModels {

    private WatchlistModels() {
    }

    /** One entry as a client sends it. */
    @Schema(name = "TrademarkWatchlistEntryInput")
    @JsonPropertyOrder({"mark", "aliases", "niceClasses", "serialNumber", "note", "enabled"})
    public record EntryInput(
            @Schema(description = "The mark, as it should be reported. It identifies the entry: after normalization (case, accents, "
                    + "punctuation, look-alike letters) no two marks or aliases of one list may be equal.",
                    example = "Brimwick Otters", requiredMode = Schema.RequiredMode.REQUIRED) String mark,
            @Schema(description = "Other ways the mark is written: nicknames, short forms, slogans. Optional.",
                    example = "[\"Otterz\", \"BWO\"]", nullable = true) List<String> aliases,
            @Schema(description = "Nice classes (1 to 45) the client notes for the mark. Shown in findings; they do not change a level.",
                    example = "[25]", nullable = true) List<Integer> niceClasses,
            @Schema(description = "A USPTO serial number (eight digits), optional. A finding then carries the source id "
                    + "`uspto:<serialNumber>` and its look-up link.", example = "12345678", nullable = true) String serialNumber,
            @Schema(description = "Why the entry is listed, for example a claim received. Shown in the finding's message.",
                    example = "từng bị claim", nullable = true) String note,
            @Schema(description = "False keeps the entry in the list without scanning for it. Default true.", nullable = true)
            Boolean enabled) {
    }

    /** The body of {@code PUT /api/v1/trademark-watchlist}: the whole list, replacing the one stored. */
    @Schema(name = "TrademarkWatchlistReplaceRequest")
    public record ReplaceRequest(
            @NotNull @Schema(description = "Every entry of the list, in the order to keep. An empty list removes every entry.",
                    requiredMode = Schema.RequiredMode.REQUIRED) List<EntryInput> entries) {
    }

    /** One stored entry. */
    @Schema(name = "TrademarkWatchlistEntry")
    @JsonPropertyOrder({"id", "mark", "aliases", "niceClasses", "serialNumber", "note", "enabled", "addedBy", "addedAt",
            "updatedBy", "updatedAt"})
    public record EntryView(
            @Schema(description = "The entry's id. It is kept while the entry's mark keeps its normalized form.") UUID id,
            @Schema(example = "Brimwick Otters") String mark,
            List<String> aliases,
            List<Integer> niceClasses,
            @Schema(nullable = true, example = "12345678") String serialNumber,
            @Schema(nullable = true, example = "từng bị claim") String note,
            boolean enabled,
            @Schema(description = "Who added it: `console:<account id>` or `api-key:<key id>`.", example = "api-key:k1a2b3c4")
            String addedBy,
            Instant addedAt,
            @Schema(description = "Who changed it last, in the same form as `addedBy`.", example = "api-key:k1a2b3c4")
            String updatedBy,
            Instant updatedAt) {
    }

    /** The deployment's bounds of a list. */
    @Schema(name = "TrademarkWatchlistLimits")
    @JsonPropertyOrder({"maxEntries", "maxAliases", "maxTextChars", "maxNoteChars", "maxTokens"})
    public record LimitsView(
            @Schema(example = "500") int maxEntries,
            @Schema(example = "20") int maxAliases,
            @Schema(description = "Longest mark or alias, in characters.", example = "100") int maxTextChars,
            @Schema(description = "Longest note, in characters.", example = "500") int maxNoteChars,
            @Schema(description = "Most words in one mark or alias after normalization.", example = "12") int maxTokens) {
    }

    /** The answer of {@code GET} and {@code PUT /api/v1/trademark-watchlist}. */
    @Schema(name = "TrademarkWatchlist")
    @JsonPropertyOrder({"revision", "version", "updatedAt", "entries", "limits"})
    public record WatchlistView(
            @Schema(description = "Grows by one at every change; 0 when the client never had a list. It is the ETag, and "
                    + "`If-Match` on PUT compares it.", example = "3") long revision,
            @Schema(description = "SHA-256 of the enabled entries, the `watchlist.version` a trademark check reports; null when no "
                    + "entry is enabled (checks then use no watchlist).", nullable = true) String version,
            @Schema(description = "When the list last changed (UTC); null when it never existed.", nullable = true)
            Instant updatedAt,
            List<EntryView> entries,
            LimitsView limits) {
    }
}
