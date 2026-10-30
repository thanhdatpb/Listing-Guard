package com.thanhdatpb.listingguard.rag.analysis;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Always {@code proposed == kept + sum(dropped)}.
 *
 * @param dropped         every {@link DropReason} is present, zero when none
 * @param severityClamped kept findings whose {@code ERROR} was lowered to {@code WARNING}
 */
public record VerificationStats(int proposed, int kept, Map<DropReason, Integer> dropped, int severityClamped) {

    public VerificationStats {
        EnumMap<DropReason, Integer> all = new EnumMap<>(DropReason.class);
        for (DropReason reason : DropReason.values()) {
            all.put(reason, dropped.getOrDefault(reason, 0));
        }
        dropped = Collections.unmodifiableMap(all);
        int total = all.values().stream().mapToInt(Integer::intValue).sum();
        if (proposed != kept + total) {
            throw new IllegalArgumentException(
                    "proposed " + proposed + " != kept " + kept + " + dropped " + total);
        }
    }

    public int dropped(DropReason reason) {
        return dropped.get(reason);
    }

    public int totalDropped() {
        return proposed - kept;
    }
}
