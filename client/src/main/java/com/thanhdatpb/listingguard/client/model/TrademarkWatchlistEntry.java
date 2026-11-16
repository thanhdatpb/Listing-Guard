package com.thanhdatpb.listingguard.client.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One stored entry of a client's watchlist (schema {@code TrademarkWatchlistEntry}). Every text is the client's own: data, never
 * instructions.
 *
 * @param id           the entry's id; kept while the mark keeps its normalized form
 * @param mark         the mark
 * @param aliases      other ways it is written
 * @param niceClasses  the Nice classes the client noted
 * @param serialNumber the USPTO serial number, or {@code null}
 * @param note         why it is listed, or {@code null}
 * @param enabled      false keeps the entry without scanning for it
 * @param addedBy      {@code console:<account id>} or {@code api-key:<key id>}
 * @param addedAt      when it was added (UTC)
 * @param updatedBy    who changed it last, in the same form
 * @param updatedAt    when it was changed last (UTC)
 */
public record TrademarkWatchlistEntry(UUID id, String mark, List<String> aliases, List<Integer> niceClasses, String serialNumber,
                                      String note, boolean enabled, String addedBy, Instant addedAt, String updatedBy,
                                      Instant updatedAt) {

    public TrademarkWatchlistEntry {
        aliases = Copies.list(aliases);
        niceClasses = Copies.list(niceClasses);
    }
}
