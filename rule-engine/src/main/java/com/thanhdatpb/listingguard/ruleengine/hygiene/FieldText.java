package com.thanhdatpb.listingguard.ruleengine.hygiene;

/** A text of a listing and the path a finding names it by ({@code title}, {@code tags[2]}, {@code bullet_points[0]}). */
public record FieldText(String field, String text) {
}
