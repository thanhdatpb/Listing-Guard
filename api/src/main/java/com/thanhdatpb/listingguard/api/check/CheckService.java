package com.thanhdatpb.listingguard.api.check;

import com.thanhdatpb.listingguard.api.listing.Listing;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Week 1 skeleton: normalizes the listing, records the check and returns no findings. Rule engine
 * and RAG analyzer are wired in later phases.
 */
@Service
public class CheckService {

    private final NormalizerRegistry normalizers;
    private final ContentHasher hasher;
    private final ListingCheckRepository checks;

    public CheckService(NormalizerRegistry normalizers, ContentHasher hasher, ListingCheckRepository checks) {
        this.normalizers = normalizers;
        this.hasher = hasher;
        this.checks = checks;
    }

    @Transactional
    public CheckResponse check(CheckRequest request) {
        var normalizer = normalizers.get(request.marketplace());
        Listing listing = normalizer.normalize(request.listing());
        String marketplace = normalizer.marketplace().toUpperCase(Locale.ROOT);
        String contentHash = hasher.hash(listing);

        ListingCheck saved = checks.save(new ListingCheck(contentHash, marketplace, CheckStatus.COMPLETED));
        return new CheckResponse(saved.getId(), marketplace, saved.getStatus(), contentHash, List.of());
    }
}
