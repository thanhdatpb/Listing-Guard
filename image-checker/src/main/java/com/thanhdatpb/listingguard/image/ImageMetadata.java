package com.thanhdatpb.listingguard.image;

public record ImageMetadata(String format, int width, int height, int orientation, boolean hadAlpha,
                            boolean hadIccProfile) { }
