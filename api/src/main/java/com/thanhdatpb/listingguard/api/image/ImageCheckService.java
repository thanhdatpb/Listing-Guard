package com.thanhdatpb.listingguard.api.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.listing.Normalizer;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.image.CanonicalImageInput;
import com.thanhdatpb.listingguard.image.ImageCheckInput;
import com.thanhdatpb.listingguard.image.ImageItem;
import com.thanhdatpb.listingguard.image.ImageItemResult.Completeness;
import com.thanhdatpb.listingguard.image.ImageMarketplace;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Checks the images of a listing and stores the result (ADR-008, ADR-010).
 *
 * <p>The request is its own shape (a multipart request, see {@link ImageCheckMetadata}), not the {@code /checks} envelope; only
 * the marketplace code goes through the {@link NormalizerRegistry}, so an unknown or planned marketplace is answered exactly as
 * for the other endpoints. Only the canonical input hash, one hash per image, the metadata the decoder read and the findings are
 * stored; the image bytes and the client's file names never are, and no log line here contains either.
 *
 * <p>Cache identity is {@code (client, marketplace, pack, input hash, ruleset version, analysis version)}; the analysis version
 * is the pack's fingerprint (ruleset, algorithm versions, image runtime). Only a complete, unexpired result answers a request.
 * An {@code INCOMPLETE} result is stored so that its id can be read again, and never reused. A complete result past its
 * retention period is replaced: the expired row and its children are deleted and the new result inserted in one transaction. Two
 * requests that replace the same row at the same time are settled by the unique index: the one that loses rolls back and answers
 * from the winner's row; any other integrity failure is not a cache hit and is raised as it is.
 *
 * <p>Plans (ADR-013), in this order: the pack, the cache, the quota before the analysis, and the charge inside the transaction
 * that stores a complete result. An image that cannot be decoded is not an error: the checker reports it as an INCOMPLETE image
 * and the request is answered 200.
 *
 * <p><b>Not here yet:</b> the process-wide bounded decode service and the private upload staging of ADR-008 "Image input
 * security". Until they exist the feature is off by default ({@code listing-guard.image.enabled}).
 */
@Service
public class ImageCheckService {

    private static final Logger log = LoggerFactory.getLogger(ImageCheckService.class);

    private final ImageProperties properties;
    private final NormalizerRegistry marketplaces;
    private final ImageRulesets rulesets;
    private final ImageCheckStore store;
    private final TransactionTemplate transactions;
    private final PlanService plans;
    private final ObjectMapper mapper;

    public ImageCheckService(ImageProperties properties, NormalizerRegistry marketplaces, ImageRulesets rulesets,
                             ImageCheckStore store, TransactionTemplate transactions, PlanService plans, ObjectMapper mapper) {
        this.properties = properties;
        this.marketplaces = marketplaces;
        this.rulesets = rulesets;
        this.store = store;
        this.transactions = transactions;
        this.plans = plans;
        this.mapper = mapper;
    }

    /**
     * @param metadataJson the text of the {@code metadata} part
     * @param files        the {@code images} parts, in the order of {@code metadata.images}
     * @throws ImageChecksUnavailableException if image checks are not enabled
     * @throws com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException if the client's plan has no {@code image-checks} pack
     * @throws IllegalArgumentException        if the metadata or the images do not fit the documented shape (a 400)
     * @throws TooManyImagesException          if there are more images than the deployment allows
     * @throws UnknownImageProfileException    if the profile is not declared by the marketplace's ruleset
     * @throws MaxUploadSizeExceededException  if one image is larger than the deployment allows
     * @throws com.thanhdatpb.listingguard.api.plan.QuotaExceededException if the answer is not stored yet and the month's quota is used up
     */
    public ImageCheckResult check(UUID clientId, String metadataJson, List<MultipartFile> files) {
        if (!properties.enabled()) {
            throw new ImageChecksUnavailableException();
        }
        PlanService.Admission admission = plans.requirePack(clientId, Pack.IMAGE_CHECKS);

        ImageCheckMetadata metadata = ImageCheckMetadata.parse(metadataJson, mapper);
        Normalizer normalizer = marketplaces.get(metadata.marketplace());
        String marketplace = normalizer.marketplace().toUpperCase(Locale.ROOT);
        ImageRulesets.Loaded loaded = rulesets.loaded(marketplace);
        String profile = metadata.profile() == null || metadata.profile().isBlank() ? properties.defaultProfile()
                : metadata.profile().strip();
        if (!loaded.profiles().contains(profile)) {
            throw new UnknownImageProfileException(profile, loaded.profiles());
        }
        List<ImageCheckMetadata.Parsed> declared = metadata.parsedImages();
        int count = files == null ? 0 : files.size();
        if (count < 1) {
            throw new IllegalArgumentException("At least one image part is required.");
        }
        if (count > properties.maxImages()) {
            throw new TooManyImagesException(properties.maxImages(), count);
        }
        if (declared.size() != count) {
            throw new IllegalArgumentException("metadata.images lists " + declared.size() + " entries but " + count
                    + " image parts were sent; they must match one to one, in the same order.");
        }

        ImageCheckInput input = new ImageCheckInput(ImageMarketplace.valueOf(marketplace), profile,
                items(files, declared));
        ImageCheckStore.Identity identity = new ImageCheckStore.Identity(clientId, marketplace, loaded.ruleset().packId(),
                CanonicalImageInput.of(input).hash(), rulesets.rulesetVersionId(marketplace), loaded.analysisVersion());

        Optional<ImageCheckResult> cached = store.findCached(identity);
        if (cached.isPresent()) {
            return cached.get();
        }

        // a cached answer costs nothing and was returned above; a new result is stored, and a complete one is counted
        // (ADR-013). An INCOMPLETE result is stored for GET and never reused, so the client is not charged for it.
        plans.requireQuota(admission);
        com.thanhdatpb.listingguard.image.ImageCheckResult result = rulesets.checker(marketplace, profile).check(input);
        try {
            return transactions.execute(status -> {
                if (result.completeness() == Completeness.COMPLETE) {
                    plans.charge(admission);
                }
                UUID id = store.insert(identity, profile, loaded.disclaimer(), loaded.sourceStatusByRule(), result);
                return store.find(clientId, id).orElseThrow();
            });
        } catch (DataIntegrityViolationException e) {
            if (result.completeness() == Completeness.COMPLETE && ImageCheckStore.isCacheIdentityViolation(e)) {
                // another request of this client stored the same complete result between our lookup and our insert
                log.debug("Concurrent identical image check for {}; answering from the stored result", marketplace);
                return store.findCached(identity).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    /**
     * A retained check of this client. One that belongs to another client, one that never existed and one past its retention
     * period are reported exactly alike, so the answer does not reveal which ids are in use.
     */
    public ImageCheckResult get(UUID clientId, UUID id) {
        return store.find(clientId, id).orElseThrow(() -> new ImageCheckNotFoundException(id));
    }

    private List<ImageItem> items(List<MultipartFile> files, List<ImageCheckMetadata.Parsed> declared) {
        List<ImageItem> items = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file.getSize() > properties.maxFileSize().toBytes()) {
                throw new MaxUploadSizeExceededException(properties.maxFileSize().toBytes());
            }
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException e) {
                throw new IllegalArgumentException("images[" + i + "] could not be read.");
            }
            items.add(new ImageItem(bytes, declared.get(i).role(), declared.get(i).source()));
        }
        return items;
    }
}
