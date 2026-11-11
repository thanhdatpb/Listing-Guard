package com.thanhdatpb.listingguard.api.image;

/** More images than {@code listing-guard.image.max-images} in one request. */
public class TooManyImagesException extends RuntimeException {

    private final int maxImages;
    private final int submitted;

    public TooManyImagesException(int maxImages, int submitted) {
        super("A request may hold at most " + maxImages + " images; " + submitted + " were sent.");
        this.maxImages = maxImages;
        this.submitted = submitted;
    }

    public int maxImages() {
        return maxImages;
    }

    public int submitted() {
        return submitted;
    }
}
